package com.techpp.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Norton TV's Lights row: every Philips Hue bulb on the home network, on or off, and OK switches one. It talks only
 * to the Hue Bridge on the local network (its v1 API over plain HTTP, the way the Hue apps do on a LAN), apart from
 * one look-up at discovery.meethue.com to find the bridge if its address isn't known. Linking is the usual Hue way:
 * press the round button on the bridge, then OK on the "Link your Hue lights" card within 30 seconds; the app key the
 * bridge hands back stays in this app's private storage. Refreshes every 30 s while the home screen is in front.
 */
final class Hue {
    private static final String TAG = "GlassHue";
    private static final String DISCOVERY = "https://discovery.meethue.com/";
    private static final long REFRESH_MS = 30_000L, LINK_MS = 30_000L, LINK_TRY_MS = 2_000L;

    /** One bulb, as the row shows it. */
    static final class Light {
        String id, name;
        boolean on, reachable;
        int bri;  // 1–254 when on

        /** "On · 60%", "Off" or "Not reachable". */
        String status() {
            if (!reachable) return "Not reachable";
            return on ? "On  ·  " + Math.max(1, Math.round(bri * 100f / 254f)) + "%" : "Off";
        }
    }

    interface Listener { void onLights(List<Light> lights); }

    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private long linkUntil;
    private List<Light> last = new ArrayList<>();

    Hue(Context c) {
        prefs = c.getSharedPreferences("hue", Context.MODE_PRIVATE);
    }

    /** True once the bridge has given Norton TV a key. */
    boolean linked() { return prefs.getString("user", null) != null; }

    /** True while we're waiting for the bridge button (the 30 s after OK on the link card). */
    boolean linking() { return System.currentTimeMillis() < linkUntil; }

    /** True when a bridge is known (found on the network) but not linked yet: the row shows the link card. */
    boolean found() { return prefs.getString("bridge", null) != null; }

    List<Light> lights() { return last; }

    /** Finds the bridge if needed, then reads every bulb (at most every 30 s). */
    void refreshIfStale(Listener l) {
        if (running) return;
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("ok_at", 0) < REFRESH_MS && now - prefs.getLong("try_at", 0) < REFRESH_MS) return;
        prefs.edit().putLong("try_at", now).apply();
        running = true;
        worker.execute(() -> {
            try {
                String bridge = bridge();
                String user = prefs.getString("user", null);
                if (bridge != null && user != null) {
                    List<Light> lights = read(bridge, user);
                    prefs.edit().putLong("ok_at", now).apply();
                    main.post(() -> { last = lights; l.onLights(lights); });
                } else {
                    main.post(() -> l.onLights(last));
                }
            } catch (Exception e) {
                Log.w(TAG, "Hue failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
                // The bridge may have a new address: look it up again next time.
                if (e instanceof IOException) prefs.edit().remove("bridge").apply();
            } finally {
                main.post(() -> running = false);
            }
        });
    }

    /**
     * Asks the bridge for a key every 2 s for 30 s, while you press its button. Calls back with true once linked, or
     * false when the 30 s run out (or there's no bridge).
     */
    void link(Consumer<Boolean> done) {
        if (linking()) return;
        linkUntil = System.currentTimeMillis() + LINK_MS;
        worker.execute(() -> {
            boolean ok = false;
            try {
                String bridge = bridge();
                while (bridge != null && System.currentTimeMillis() < linkUntil && !ok) {
                    JSONArray a = new JSONArray(request("POST", "http://" + bridge + "/api",
                            new JSONObject().put("devicetype", "norton_tv#bravia").toString()));
                    JSONObject r = a.optJSONObject(0);
                    JSONObject success = r == null ? null : r.optJSONObject("success");
                    if (success != null && !success.optString("username").isEmpty()) {
                        prefs.edit().putString("user", success.optString("username")).remove("ok_at").apply();
                        ok = true;
                    } else {
                        Thread.sleep(LINK_TRY_MS);  // error 101: the button isn't pressed yet
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Hue link failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            boolean result = ok;
            main.post(() -> { linkUntil = 0; done.accept(result); });
        });
    }

    /** Switches one bulb on or off, then hands back the bulbs as they are now. */
    void toggle(Light light, Listener l) {
        String bridge = prefs.getString("bridge", null), user = prefs.getString("user", null);
        if (bridge == null || user == null) return;
        boolean on = !light.on;
        worker.execute(() -> {
            try {
                request("PUT", "http://" + bridge + "/api/" + user + "/lights/" + light.id + "/state",
                        new JSONObject().put("on", on).toString());
                List<Light> lights = read(bridge, user);
                prefs.edit().putLong("ok_at", System.currentTimeMillis()).apply();
                main.post(() -> { last = lights; l.onLights(lights); });
            } catch (Exception e) {
                Log.w(TAG, "Hue switch failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        });
    }

    /** Forgets the bridge and its key (Norton TV settings → Hue lights → Unlink). */
    void unlink() {
        prefs.edit().clear().apply();
        last = new ArrayList<>();
    }

    // ---------------------------------------------------------------- bridge

    /** The bridge's address: saved, else asked of Philips' discovery service (it answers with bridges on this network). */
    private String bridge() throws Exception {
        String saved = prefs.getString("bridge", null);
        if (saved != null) return saved;
        JSONArray a = new JSONArray(request("GET", DISCOVERY, null));
        if (a.length() == 0) return null;
        String ip = a.getJSONObject(0).optString("internalipaddress");
        if (!ip.matches("[0-9.]+")) return null;
        prefs.edit().putString("bridge", ip).apply();
        return ip;
    }

    /** Every bulb, A–Z by name. */
    private static List<Light> read(String bridge, String user) throws Exception {
        String body = request("GET", "http://" + bridge + "/api/" + user + "/lights", null);
        if (body.trim().startsWith("[")) throw new IllegalStateException("bridge refused the key: " + body);
        JSONObject all = new JSONObject(body);
        List<Light> out = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONObject o = all.getJSONObject(id), st = o.optJSONObject("state");
            Light l = new Light();
            l.id = id;
            l.name = o.optString("name", "Light " + id);
            l.on = st != null && st.optBoolean("on");
            l.bri = st == null ? 0 : st.optInt("bri", 254);
            l.reachable = st == null || st.optBoolean("reachable", true);
            out.add(l);
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    private static String request(String method, String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4_000);
        c.setReadTimeout(6_000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
