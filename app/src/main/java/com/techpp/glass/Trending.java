package com.techpp.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two tabs of picks, kept only when TMDB says the title streams (subscription, not rent/buy) in the TV's country
 * (Region) on an app installed on this TV. Idea from Free My TV (MIT).
 *   Trending      what's popular now on your apps here: titles from this country and English titles, mixed 2:1
 *                 (TMDB's own trending list is global and almost all English), minus anything in "New in";
 *                 in English-speaking countries simply what's popular here
 *   New in …      this country's films (last 6 months) and series (new episodes in the last 60 days)
 *
 * The TMDB key lives in a file on the TV (files/tmdb_key.txt), never in the APK. Picks and their backdrops are
 * cached in files/trending/. The list is refreshed at most every 6 hours, and only when the home screen asks.
 */
final class Trending {
    private static final String TAG = "GlassTrending";
    private static final String API = "https://api.themoviedb.org/3";
    /** Backdrops at 1280 px for the large card (about 1040 px on screen); posters at 342 px for the small ones. */
    private static final String BACKDROPS = "https://image.tmdb.org/t/p/w1280", POSTERS = "https://image.tmdb.org/t/p/w342";
    /** The TV's country, read at each refresh (Region). */
    private String region = "US";
    private static final long REFRESH_MS = 6 * 3600_000L, RETRY_MS = 30 * 60_000L;
    private static final int MAX_PICKS = 24, MAX_CHECKS = 72;
    static final int SHOWN = 4;
    static final String TRENDING = "trending", INDIA = "india";
    /** Bump when picks.json changes shape; the next home screen refreshes right away. */
    private static final int CACHE_VERSION = 6;
    /** TMDB genre ids → names, fetched once per refresh. */
    private final Map<Integer, String> genres = new java.util.HashMap<>();

    /** TMDB provider name (lowercase substring) → TV app package, and the name we show. */
    private static final String[][] SERVICES = {
        {"netflix", "com.netflix.ninja", "Netflix"},
        {"prime video", "com.amazon.amazonvideo.livingroom", "Prime Video"},
        {"hotstar", "in.startv.hotstar", "JioHotstar"},
        {"zee5", "com.graymatrix.did", "ZEE5"},
        {"sony liv", "com.sonyliv", "SonyLIV"},
        {"sonyliv", "com.sonyliv", "SonyLIV"},
        {"apple tv", "com.apple.atve.androidtv.appletv", "Apple TV"},
        {"mx player", "com.mxtech.videoplayer.television", "MX Player"},
        {"airtel xstream", "tv.airtel.xstream.tvapp", "Xstream Play"},
        {"fancode", "com.fancode.tv", "FanCode"},
        {"disney plus", "com.disney.disneyplus", "Disney+"},
        {"hulu", "com.hulu.livingroomplus", "Hulu"},
        {"max", "com.wbd.stream", "Max"},
        {"peacock", "com.peacocktv.peacockandroid", "Peacock"},
        {"paramount", "com.cbs.ott", "Paramount+"},
        {"crunchyroll", "com.crunchyroll.crunchyroid", "Crunchyroll"},
        {"mubi", "com.mubi", "MUBI"},
    };

    static final class Pick {
        int id;
        String kind;     // "movie" or "tv"
        String title, pkg, service, tab, lang = "";
        File image;          // landscape picture (TMDB backdrop, or the app's artwork)
        File poster;         // TMDB poster, for the small portrait cards; null when there is none
        transient String backdropPath, posterPath;  // TMDB image paths, while a refresh downloads them
        long at;            // Recently watched: when it was last watched
        String intentUri;   // Recently watched (Watch Next): opens the right title or episode
        String meta = "";   // the line under a big card, e.g. "Film · Action · Telugu · Netflix"
        float progress = -1; // Continue: how far in (0–1), or -1 when unknown
        Object extra;        // Cricket: the Cricket.Match this card shows

        String key() { return "pick:" + tab + ":" + kind + ":" + id; }
    }

    interface Listener { void onPicks(List<Pick> all); }

    private final Context ctx;
    private final File dir;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** For one refresh: several TMDB requests and image downloads at a time (set in fetch, shut down after). */
    private ExecutorService net;
    private final Map<String, java.util.concurrent.Future<JSONObject>> providerLookups = new java.util.HashMap<>();
    private static final int PARALLEL = 6, LOOKAHEAD = 6;
    private static final long SETUP_MS = 7 * 24 * 3600_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;

    Trending(Context c) {
        ctx = c.getApplicationContext();
        File base = c.getExternalFilesDir(null);
        dir = new File(base, "trending");
        prefs = c.getSharedPreferences("trending", Context.MODE_PRIVATE);
    }

    private File keyFile() { return new File(dir.getParentFile(), "tmdb_key.txt"); }

    String key() {
        try (InputStream in = new FileInputStream(keyFile())) {
            String k = new String(readAll(in), StandardCharsets.UTF_8).trim();
            return k.isEmpty() ? null : k;
        } catch (IOException e) {
            return null;
        }
    }

    /** Saved picks for one tab whose app is still installed and whose backdrop is on disk. */
    List<Pick> cached(String tab) {
        List<Pick> out = new ArrayList<>();
        for (Pick p : cached()) if (p.tab.equals(tab)) out.add(p);
        return out;
    }

    List<Pick> cached() {
        List<Pick> out = new ArrayList<>();
        if (key() == null) return out;
        try (InputStream in = new FileInputStream(new File(dir, "picks.json"))) {
            JSONArray a = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Pick p = new Pick();
                p.id = o.getInt("id");
                p.kind = o.getString("kind");
                p.title = o.getString("title");
                p.pkg = o.getString("pkg");
                p.service = o.getString("service");
                p.image = new File(dir, o.getString("image"));
                String poster = o.optString("poster", "");
                p.poster = poster.isEmpty() || !new File(dir, poster).isFile() ? null : new File(dir, poster);
                p.tab = o.optString("tab", TRENDING);
                p.lang = o.optString("lang", "");
                p.meta = o.optString("meta", "");
                if (p.image.isFile() && installed(p.pkg)) out.add(p);
            }
        } catch (Exception e) {
            // Nothing saved yet.
        }
        return out;
    }

    /** Hours since the epoch in local time, so the picks change on the hour here (not at :30 in India). */
    static long localHour() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 3600_000L;
    }

    /** The picks for this hour: SHOWN of them, rotating through the saved list. */
    static List<Pick> forThisHour(List<Pick> all) {
        if (all.size() <= SHOWN) return all;
        long hour = localHour();
        int start = (int) ((hour * SHOWN) % all.size());
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < SHOWN; i++) out.add(all.get((start + i) % all.size()));
        return out;
    }

    /** Fetches a new list in the background when the saved one is old. Calls back on the main thread. */
    void refreshIfStale(Listener l) {
        String key = key();
        if (key == null || running) return;
        long now = System.currentTimeMillis();
        // A new key fetches right away instead of waiting out the old key's timers.
        String cc = Region.country(ctx), was = prefs.getString("region", null);
        if (was == null) prefs.edit().putString("region", cc).apply();  // first run with countries: just remember it
        if (prefs.getInt("key_hash", 0) != key.hashCode() || prefs.getInt("version", 0) != CACHE_VERSION
                || (was != null && !cc.equals(was))) {
            // A new key, a new cache layout, or the TV now in another country: fetch right away.
            prefs.edit().putInt("key_hash", key.hashCode()).putInt("version", CACHE_VERSION).putString("region", cc)
                    .remove("ok_at").remove("try_at").apply();
        }
        if (now - prefs.getLong("ok_at", 0) < REFRESH_MS || now - prefs.getLong("try_at", 0) < RETRY_MS) return;
        prefs.edit().putLong("try_at", now).apply();
        running = true;
        worker.execute(() -> {
            try {
                fetch(key);
                prefs.edit().putLong("ok_at", System.currentTimeMillis()).apply();
                List<Pick> all = cached();
                main.post(() -> l.onPicks(all));
            } catch (Exception e) {
                Log.w(TAG, "Refresh failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
            } finally {
                main.post(() -> running = false);
            }
        });
    }

    // ---------------------------------------------------------------- fetching

    private void fetch(String key) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        long started = System.currentTimeMillis();
        region = Region.country(ctx);
        net = Executors.newFixedThreadPool(PARALLEL);
        providerLookups.clear();
        try {
            fetchWith(key);
        } finally {
            net.shutdownNow();
            providerLookups.clear();
        }
        Log.i(TAG, "Refreshed for " + region + " in " + (System.currentTimeMillis() - started) + " ms");
    }

    /**
     * Genre names and India's list of streaming services hardly ever change: kept for a week (prefs "setup"), which
     * saves four requests per refresh.
     */
    private JSONObject setup(String key) throws Exception {
        try {
            JSONObject saved = new JSONObject(prefs.getString("setup", "{}"));
            if (System.currentTimeMillis() - saved.optLong("at") < SETUP_MS && saved.has("tv_providers")
                    && region.equals(saved.optString("region"))) return saved;
        } catch (Exception ignored) {
            // Fetch afresh.
        }
        JSONObject o = new JSONObject().put("at", System.currentTimeMillis()).put("region", region);
        for (String kind : new String[]{"movie", "tv"}) {
            o.put(kind + "_genres", get(key, "/genre/" + kind + "/list?language=en").getJSONArray("genres"));
            o.put(kind + "_providers", get(key, "/watch/providers/" + kind + "?watch_region=" + region).getJSONArray("results"));
        }
        prefs.edit().putString("setup", o.toString()).apply();
        return o;
    }

    private void fetchWith(String key) throws Exception {
        JSONObject setup = setup(key);
        genres.clear();
        for (String kind : new String[]{"movie", "tv"}) {
            JSONArray g = setup.getJSONArray(kind + "_genres");
            for (int i = 0; i < g.length(); i++) genres.put(g.getJSONObject(i).getInt("id"), g.getJSONObject(i).getString("name"));
        }
        // 1. Which of TMDB's Indian streaming services are installed here.
        Map<Integer, String[]> services = new LinkedHashMap<>();
        for (String kind : new String[]{"movie", "tv"}) {
            JSONArray a = setup.getJSONArray(kind + "_providers");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String[] s = service(o.getString("provider_name"));
                if (s != null && installed(s[1])) services.put(o.getInt("provider_id"), s);
            }
        }
        Log.i(TAG, "Matched " + services.size() + " TMDB services to installed apps");
        if (services.isEmpty()) { save(new ArrayList<>()); return; }

        // 2. Candidates for each tab, then 3. keep those that stream on an installed app (and fetch backdrops).
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&watch_region=" + region + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + ids;

        // The six candidate lists, all at once.
        String popular = "&vote_count.gte=10" + common;
        java.util.concurrent.Future<JSONObject> newFilms = later(key, "/discover/movie?with_origin_country=" + region
                + "&primary_release_date.gte=" + day(-180) + "&primary_release_date.lte=" + day(0) + common);
        java.util.concurrent.Future<JSONObject> newShows = later(key, "/discover/tv?with_origin_country=" + region
                + "&air_date.gte=" + day(-60) + "&air_date.lte=" + day(0) + common);
        java.util.concurrent.Future<JSONObject> localFilms = later(key, "/discover/movie?with_origin_country=" + region + popular);
        java.util.concurrent.Future<JSONObject> localShows = later(key, "/discover/tv?with_origin_country=" + region + popular);
        java.util.concurrent.Future<JSONObject> englishFilms = later(key, "/discover/movie?with_original_language=en" + popular);
        java.util.concurrent.Future<JSONObject> englishShows = later(key, "/discover/tv?with_original_language=en" + popular);

        // "New in …" first, so Trending can leave its titles out.
        List<Pick> india = keepMixed(key, services, INDIA, new HashSet<>(), new int[]{1},
                merge(tag(newFilms.get().getJSONArray("results"), "movie"), tag(newShows.get().getJSONArray("results"), "tv")));
        Set<String> inIndia = new HashSet<>();
        for (Pick p : india) inIndia.add(p.kind + p.id);

        // Trending: popular titles from this country and popular English titles, two of the first for each one of the
        // second. Where the country's own titles are English anyway (US, UK…), just what's popular here.
        List<JSONObject> local = merge(tag(localFilms.get().getJSONArray("results"), "movie"),
                tag(localShows.get().getJSONArray("results"), "tv"));
        List<JSONObject> english = merge(tag(englishFilms.get().getJSONArray("results"), "movie"),
                tag(englishShows.get().getJSONArray("results"), "tv"));
        List<Pick> trend = Region.englishSpeaking(region)
                ? keepMixed(key, services, TRENDING, inIndia, new int[]{0, 1}, english, local)
                : keepMixed(key, services, TRENDING, inIndia, new int[]{0, 0, 1}, local, english);

        List<Pick> picks = new ArrayList<>(trend);
        picks.addAll(india);
        save(downloadImages(picks));
    }

    private java.util.concurrent.Future<JSONObject> later(String key, String path) {
        return net.submit(() -> get(key, path));
    }

    /** Starts the "where does it stream?" lookups for the next few titles in a queue, so they're ready when needed. */
    private void lookAhead(String key, List<JSONObject> queue, int from, Set<String> exclude) throws Exception {
        for (int i = from, n = 0; i < queue.size() && n < LOOKAHEAD; i++) {
            JSONObject o = queue.get(i);
            String id = o.getString("_kind") + o.getInt("id");
            if (exclude.contains(id)) continue;
            n++;
            if (!providerLookups.containsKey(id)) {
                providerLookups.put(id, later(key, "/" + o.getString("_kind") + "/" + o.getInt("id") + "/watch/providers"));
            }
        }
    }

    /** Backdrops and posters for the chosen picks, several at a time; a pick whose backdrop fails is dropped. */
    private List<Pick> downloadImages(List<Pick> picks) throws Exception {
        List<java.util.concurrent.Future<Boolean>> jobs = new ArrayList<>();
        for (Pick p : picks) {
            jobs.add(net.submit(() -> {
                if (!p.image.isFile()) download(BACKDROPS + p.backdropPath, p.image);
                if (p.poster != null && !p.poster.isFile()) {
                    try { download(POSTERS + p.posterPath, p.poster); } catch (IOException e) { p.poster = null; }
                }
                return true;
            }));
        }
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            try {
                jobs.get(i).get();
                out.add(picks.get(i));
            } catch (java.util.concurrent.ExecutionException e) {
                Log.w(TAG, "No backdrop for " + picks.get(i).title + ": " + e.getCause());
            }
        }
        return out;
    }

    /** Films and series alternating, without repeats or titles that have no backdrop. */
    private static List<JSONObject> merge(JSONArray movies, JSONArray shows) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.max(movies.length(), shows.length()); i++) {
            for (JSONArray a : new JSONArray[]{movies, shows}) {
                if (i >= a.length()) continue;
                JSONObject o = a.getJSONObject(i);
                if (o.optString("backdrop_path", "").isEmpty()) continue;
                if (seen.add(o.getString("_kind") + o.getInt("id"))) out.add(o);
            }
        }
        return out;
    }

    /**
     * Up to MAX_PICKS titles that stream in India on an installed app, taken from the queues in the given pattern
     * ({0, 0, 1} = two from the first queue, then one from the second). When a queue runs dry the others fill in,
     * so the mix only drifts if TMDB really has too few. Titles in {@code exclude} are skipped.
     */
    @SafeVarargs
    private final List<Pick> keepMixed(String key, Map<Integer, String[]> services, String tab, Set<String> exclude,
                                 int[] pattern, List<JSONObject>... queues) throws Exception {
        int[] next = new int[queues.length];
        List<Pick> picks = new ArrayList<>();
        Map<String, Integer> langs = new LinkedHashMap<>();
        int checks = 0;
        for (int slot = 0; picks.size() < MAX_PICKS && checks < MAX_CHECKS; slot++) {
            Pick got = null;
            boolean anyLeft = false;
            // Try this slot's queue first, then the others.
            for (int t = 0; t < queues.length && got == null; t++) {
                int q = (pattern[slot % pattern.length] + t) % queues.length;
                List<JSONObject> queue = queues[q];
                while (got == null && next[q] < queue.size() && checks < MAX_CHECKS) {
                    lookAhead(key, queue, next[q], exclude);
                    JSONObject o = queue.get(next[q]++);
                    if (exclude.contains(o.getString("_kind") + o.getInt("id"))) continue;
                    checks++;
                    got = accept(key, services, tab, o);
                }
                if (next[q] < queue.size()) anyLeft = true;
            }
            if (got != null) {
                picks.add(got);
                exclude.add(got.kind + got.id);
                langs.merge(got.lang, 1, Integer::sum);
            } else if (!anyLeft) break;
        }
        Log.i(TAG, tab + ": " + picks.size() + " picks from " + checks + " checks, languages " + langs);
        return picks;
    }

    /** The title as a pick if it streams (subscription) in India on an installed app; downloads its backdrop. */
    private Pick accept(String key, Map<Integer, String[]> services, String tab, JSONObject o) throws Exception {
        String kind = o.getString("_kind");
        java.util.concurrent.Future<JSONObject> lookup = providerLookups.get(kind + o.getInt("id"));
        JSONObject providers;
        try {
            providers = lookup != null ? lookup.get() : get(key, "/" + kind + "/" + o.getInt("id") + "/watch/providers");
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
        JSONObject in = providers.getJSONObject("results").optJSONObject(region);
        JSONArray flat = in == null ? null : in.optJSONArray("flatrate");
        if (flat == null) return null;
        String[] s = null;
        for (int i = 0; i < flat.length() && s == null; i++) s = services.get(flat.getJSONObject(i).getInt("provider_id"));
        if (s == null) return null;
        Pick p = new Pick();
        p.id = o.getInt("id");
        p.kind = kind;
        p.tab = tab;
        p.lang = o.optString("original_language", "");
        p.title = o.optString(kind.equals("tv") ? "name" : "title", "");
        p.pkg = s[1];
        p.service = s[2];
        // "Film · Action · Telugu · Netflix": kind, first genre, language when not English, the app.
        StringBuilder m = new StringBuilder(kind.equals("tv") ? "Series" : "Film");
        JSONArray g = o.optJSONArray("genre_ids");
        if (g != null && g.length() > 0 && genres.containsKey(g.getInt(0))) {
            m.append(" · ").append(genres.get(g.getInt(0)).replace("Action & Adventure", "Action").replace("Sci-Fi & Fantasy", "Sci-Fi"));
        }
        if (!p.lang.equals("en") && !Region.language(p.lang).isEmpty()) m.append(" · ").append(Region.language(p.lang));
        m.append(" · ").append(p.service);
        p.meta = m.toString();
        // Pictures are downloaded once all picks are chosen (downloadImages), several at a time.
        p.image = new File(dir, kind + "_" + p.id + "_w1280.jpg");
        p.backdropPath = o.getString("backdrop_path");
        String posterPath = o.optString("poster_path", "");
        if (!posterPath.isEmpty() && !posterPath.equals("null")) {
            p.poster = new File(dir, kind + "_" + p.id + "_poster.jpg");
            p.posterPath = posterPath;  // the backdrop alone is fine if this fails
        }
        return p;
    }

    private static String[] service(String providerName) {
        String n = providerName.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) if (n.contains(s[0])) return s;
        return null;
    }

    private static JSONArray tag(JSONArray a, String kind) throws Exception {
        for (int i = 0; i < a.length(); i++) a.getJSONObject(i).put("_kind", kind);
        return a;
    }

    private void save(List<Pick> picks) throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        JSONArray a = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (Pick p : picks) {
            a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("tab", p.tab).put("title", p.title)
                    .put("pkg", p.pkg).put("service", p.service).put("lang", p.lang).put("meta", p.meta).put("image", p.image.getName())
                    .put("poster", p.poster == null ? "" : p.poster.getName()));
            keep.add(p.image.getName());
            if (p.poster != null) keep.add(p.poster.getName());
        }
        File tmp = new File(dir, "picks.json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(a.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(new File(dir, "picks.json"))) throw new IOException("Cannot save picks");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.getName().endsWith(".jpg") && !keep.contains(f.getName())) f.delete();
    }

    private boolean installed(String pkg) {
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String day(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Whether TMDB takes this key (Keys.OK, REJECTED or OFFLINE), for Control Center. Blocking. */
    static int check(String key) {
        try {
            get(key, "/configuration");
            return Keys.OK;
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            return m.startsWith("HTTP 401") || m.startsWith("HTTP 404") ? Keys.REJECTED : Keys.OFFLINE;
        } catch (Exception e) {
            return Keys.REJECTED;  // a malformed key (spaces, odd characters) doesn't even make a URL
        }
    }

    /** TMDB accepts either a v3 API key (query parameter) or a v4 read access token (Bearer header). */
    private static JSONObject get(String key, String path) throws Exception {
        boolean bearer = key.length() > 40;
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + key);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            // The path is logged without the key.
            if (code != 200) throw new IOException("HTTP " + code + " for " + path.replaceAll("[?].*", ""));
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
        // No disconnect() on success: reading the reply to the end returns the connection to the pool, so the next
        // request skips the TLS handshake (each new one cost about a second on this TV).
    }

    private static void download(String url, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        } catch (IOException e) {
            c.disconnect();
            throw e;
        }
        if (!tmp.renameTo(to)) throw new IOException("Cannot save " + to.getName());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }
}
