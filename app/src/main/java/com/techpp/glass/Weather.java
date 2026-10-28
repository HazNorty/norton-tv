package com.techpp.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Current weather for the top-left line: the nearest airport's measured temperature (METAR, via the US National
 * Weather Service's aviationweather.gov, free and keyless) with rain and cloud from Open-Meteo for your exact
 * coordinates. When no airport within ~200 km has a report from the last two hours, all of it comes from Open-Meteo. The area comes from files/location.txt
 * (set with set-location.sh): a place name, looked up once with Open-Meteo's geocoding, or "lat,lon".
 * Refreshed at most every 30 minutes, and only when the home screen asks.
 */
final class Weather {
    private static final String TAG = "GlassWeather";
    private static final long REFRESH_MS = 30 * 60_000L, RETRY_MS = 10 * 60_000L, MAX_AGE_S = 2 * 3600;

    interface Listener { void onWeather(String line); }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;

    Weather(Context c) {
        ctx = c.getApplicationContext();
        prefs = c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** The last line shown, or null when no location is set (the greeting stays). */
    String cached() {
        if (area() == null || !unit().equals(prefs.getString("unit", "C"))) return null;  // °C/°F changed: refetch
        return prefs.getString("line", null);
    }

    private String unit() { return Region.fahrenheit(Region.country(ctx)) ? "F" : "C"; }

    void refreshIfStale(Listener l) {
        String area = area();
        if (area == null || running) return;
        long now = System.currentTimeMillis();
        // Running set-location.sh again (even with the same place) rewrites the file: fetch right away.
        long stamp = new File(ctx.getExternalFilesDir(null), "location.txt").lastModified();
        boolean moved = !area.equals(prefs.getString("area", null)) || stamp != prefs.getLong("file_at", 0)
                || !unit().equals(prefs.getString("unit", "C"))  // the TV now in a °F country, or back
                // Saved before Norton TV kept the hourly forecast: fetch now, but not every minute if that fails.
                || !prefs.contains("hours") && now - prefs.getLong("try_at", 0) > 60_000L;
        prefs.edit().putLong("file_at", stamp).apply();
        if (!moved && (now - prefs.getLong("ok_at", 0) < REFRESH_MS || now - prefs.getLong("try_at", 0) < RETRY_MS)) return;
        prefs.edit().putLong("try_at", now).apply();
        running = true;
        worker.execute(() -> {
            try {
                double[] where = locate(area);
                String line = observe(where[0], where[1]);
                prefs.edit().putString("line", line).putString("unit", unit()).putLong("ok_at", System.currentTimeMillis()).apply();
                Log.i(TAG, "Weather: " + line);
                main.post(() -> l.onWeather(line));
            } catch (Exception e) {
                Log.w(TAG, "Weather failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                main.post(() -> running = false);
            }
        });
    }

    private String area() {
        File f = new File(ctx.getExternalFilesDir(null), "location.txt");
        try (InputStream in = new FileInputStream(f)) {
            String s = new String(readAll(in), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- where

    /** Coordinates for the area: "lat,lon" as given, else a place-name search (cached until the area changes). */
    private double[] locate(String area) throws Exception {
        if (area.equals(prefs.getString("area", null)) && prefs.contains("lat")) {
            return new double[]{Double.longBitsToDouble(prefs.getLong("lat", 0)), Double.longBitsToDouble(prefs.getLong("lon", 0))};
        }
        double[] ll = null;
        String[] parts = area.split(",");
        if (parts.length == 2) {
            try { ll = new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())}; }
            catch (NumberFormatException ignored) { }
        }
        String found = area;
        if (ll == null) {
            // The search takes one place name: try the whole text, then each part ("Indiranagar", "Bengaluru").
            List<String> tries = new ArrayList<>();
            tries.add(area);
            for (String p : parts) if (!p.trim().isEmpty()) tries.add(p.trim());
            for (String q : tries) {
                JSONArray r = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                        + URLEncoder.encode(q, "UTF-8")).optJSONArray("results");
                if (r != null && r.length() > 0) {
                    JSONObject o = r.getJSONObject(0);
                    ll = new double[]{o.getDouble("latitude"), o.getDouble("longitude")};
                    found = o.optString("name") + ", " + o.optString("admin1") + ", " + o.optString("country");
                    break;
                }
            }
        }
        if (ll == null) throw new IOException("Couldn't find \"" + area + "\"");
        prefs.edit().putString("area", area).putLong("lat", Double.doubleToLongBits(ll[0]))
                .putLong("lon", Double.doubleToLongBits(ll[1])).apply();
        Log.i(TAG, String.format(Locale.US, "Location \"%s\" → %s (%.3f, %.3f)", area, found, ll[0], ll[1]));
        return ll;
    }

    // ---------------------------------------------------------------- what

    /**
     * The nearest airport's measured temperature, with rain and cloud from Open-Meteo for your exact spot (an airport
     * 15–20 km away can be dry while it rains here). Without a recent airport report, both come from the model.
     */
    private String observe(double lat, double lon) throws Exception {
        JSONObject forecast = get(String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code,is_day"
                        + "&hourly=temperature_2m,weather_code,is_day&forecast_hours=13"
                        + "&daily=sunrise,sunset&timezone=auto&forecast_days=2", lat, lon));
        JSONObject here = forecast.getJSONObject("current");
        saveHours(forecast.optJSONObject("hourly"));
        prefs.edit().putInt("code", here.getInt("weather_code")).putBoolean("code_day", here.optInt("is_day", 1) == 1).apply();
        // Today's sunrise and sunset ("2026-10-21T06:07"), for the launcher's automatic day/night look.
        JSONObject daily = forecast.optJSONObject("daily");
        if (daily != null) {
            String rise = daily.getJSONArray("sunrise").optString(0, ""), set = daily.getJSONArray("sunset").optString(0, "");
            if (rise.length() >= 16 && set.length() >= 16) {
                prefs.edit().putString("sunrise", rise.substring(11, 16)).putString("sunset", set.substring(11, 16)).apply();
            }
        }
        String condition = wmoCondition(here.getInt("weather_code"), here.optInt("is_day", 1) == 1);
        JSONObject station = nearestReport(lat, lon);
        if (station == null) return line(here.getDouble("temperature_2m"), condition, null);
        return line(station.getDouble("temp"), condition, null);  // the station is named in the log, not on screen
    }

    /** The closest airport report from the last two hours within about 200 km, or null. */
    private JSONObject nearestReport(double lat, double lon) throws Exception {
        for (double box : new double[]{1.0, 2.0}) {
            String url = String.format(Locale.US, "https://aviationweather.gov/api/data/metar?format=json&hours=2&bbox=%.3f,%.3f,%.3f,%.3f",
                    lat - box, lon - box, lat + box, lon + box);
            JSONArray all;
            try {
                all = getArray(url);
            } catch (Exception e) {
                Log.w(TAG, "Airport reports unavailable: " + e.getClass().getSimpleName());
                return null;
            }
            JSONObject best = null;
            double bestKm = Double.MAX_VALUE;
            long now = System.currentTimeMillis() / 1000;
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.getJSONObject(i);
                if (m.isNull("temp") || now - m.optLong("obsTime", 0) > MAX_AGE_S) continue;
                double km = km(lat, lon, m.getDouble("lat"), m.getDouble("lon"));
                // Newest report per station wins on equal distance (the list holds a couple of hours).
                if (km < bestKm || (km == bestKm && m.optLong("obsTime") > best.optLong("obsTime"))) { best = m; bestKm = km; }
            }
            if (best != null) {
                Log.i(TAG, String.format(Locale.US, "Nearest report: %s (%s), %.0f km", best.optString("icaoId"),
                        station(best.optString("name"), best.optString("icaoId")), bestKm));
                return best;
            }
        }
        return null;
    }

    /** "27° Showers"; in Fahrenheit where that's the custom (the US and a few others, Region). Both sources give °C. */
    private String line(double temp, String condition, String where) {
        if (Region.fahrenheit(Region.country(ctx))) temp = temp * 9 / 5 + 32;
        String s = Math.round(temp) + "° " + condition;
        return where == null || where.isEmpty() ? s : s + " · " + where;
    }

    /** Daytime by today's sunrise and sunset here, once known; until then 6:00 to 18:30. */
    boolean isDay() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int minutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        return minutes >= toMinutes(prefs.getString("sunrise", "06:00")) && minutes < toMinutes(prefs.getString("sunset", "18:30"));
    }

    private static int toMinutes(String hhmm) {
        try { return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5)); }
        catch (Exception e) { return 0; }
    }

    /** "Bengaluru/Kempegowda Intl, KA, IN" → "Kempegowda Airport". */
    private static String station(String name, String icao) {
        String s = name.split(",")[0].trim();
        if (s.contains("/")) s = s.substring(s.lastIndexOf('/') + 1).trim();
        s = s.replaceAll("\\b(Intl|International|Arpt|Apt)\\b", "Airport").replaceAll("\\s+", " ").trim();
        // Short words in station names are codes ("Hal" is HAL): capitalise them.
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            if (b.length() > 0) b.append(' ');
            b.append(w.length() <= 3 && !w.equalsIgnoreCase("the") && !w.equalsIgnoreCase("of") ? w.toUpperCase(Locale.ROOT) : w);
        }
        s = b.toString().replace("Airport Airport", "Airport");
        if (!s.toLowerCase(Locale.ROOT).contains("airport") && !s.isEmpty()) s += " Airport";
        return s.isEmpty() ? icao : s;
    }

    // ---------------------------------------------------------------- Norton TV: icon and hourly forecast

    /** One hour of the forecast: its local hour (0–23), temperature in the TV's unit and WMO code. */
    static final class Hour {
        int hour, temp, code;
        boolean day;
    }

    /** "HH|temp|code|day;…" for the next 12 hours, from the forecast's local-time "2026-10-28T14:00" stamps. */
    private void saveHours(JSONObject hourly) {
        if (hourly == null) return;
        try {
            JSONArray time = hourly.getJSONArray("time"), temp = hourly.getJSONArray("temperature_2m"),
                    code = hourly.getJSONArray("weather_code"), day = hourly.getJSONArray("is_day");
            boolean f = Region.fahrenheit(Region.country(ctx));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < time.length(); i++) {
                double t = temp.getDouble(i);
                if (f) t = t * 9 / 5 + 32;
                if (b.length() > 0) b.append(';');
                b.append(time.getString(i).substring(11, 13)).append('|').append(Math.round(t)).append('|')
                        .append(code.getInt(i)).append('|').append(day.optInt(i, 1));
            }
            prefs.edit().putString("hours", b.toString()).putLong("hours_at", System.currentTimeMillis()).apply();
        } catch (Exception e) {
            Log.w(TAG, "No hourly forecast: " + e);
        }
    }

    /** The saved forecast from this hour on, skipping hours that have passed since it was fetched. */
    List<Hour> hours() {
        List<Hour> all = new ArrayList<>();
        for (String part : prefs.getString("hours", "").split(";")) {
            String[] p = part.split("\\|");
            if (p.length < 4) continue;
            try {
                Hour h = new Hour();
                h.hour = Integer.parseInt(p[0]); h.temp = Integer.parseInt(p[1]); h.code = Integer.parseInt(p[2]);
                h.day = !"0".equals(p[3]);
                all.add(h);
            } catch (NumberFormatException ignored) { }
        }
        long passed = Math.max(0, (System.currentTimeMillis() - prefs.getLong("hours_at", 0)) / 3600_000L);
        int thisHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int start = -1;
        for (int i = 0; i < all.size() && i <= passed + 1; i++) if (all.get(i).hour == thisHour) { start = i; break; }
        if (start < 0) start = (int) Math.min(passed, all.size());
        return all.subList(start, all.size());
    }

    /** The current WMO weather code (−1 before the first fetch) and whether it's daytime there. */
    int code() { return prefs.getInt("code", -1); }
    boolean codeIsDay() { return prefs.getBoolean("code_day", isDay()); }

    /** The line icon for a WMO code: sun or moon, part cloud, cloud, fog, rain, snow or thunder. */
    static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_weather_sun : R.drawable.ic_weather_moon;
        if (code == 2) return day ? R.drawable.ic_weather_part : R.drawable.ic_weather_part_night;
        if (code == 3) return R.drawable.ic_weather_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_weather_fog;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.drawable.ic_weather_snow;
        if (code >= 95) return R.drawable.ic_weather_thunder;
        if (code >= 51) return R.drawable.ic_weather_rain;
        return R.drawable.ic_weather_cloud;
    }

    /** WMO weather codes, as Open-Meteo uses them. */
    private static String wmoCondition(int code, boolean day) {
        if (code == 0) return day ? "Sunny" : "Clear";
        if (code == 1) return day ? "Mostly sunny" : "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    private static double km(double la1, double lo1, double la2, double lo2) {
        double r = 6371, dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    // ---------------------------------------------------------------- http

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Talkies/1.0 (Android TV launcher)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " from " + new URL(url).getHost());
            try (InputStream in = c.getInputStream()) { return new String(readAll(in), StandardCharsets.UTF_8); }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject get(String url) throws Exception { return new JSONObject(fetch(url)); }

    private static JSONArray getArray(String url) throws Exception {
        String s = fetch(url).trim();
        return s.isEmpty() ? new JSONArray() : new JSONArray(s);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }
}
