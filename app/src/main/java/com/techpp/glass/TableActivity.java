package com.techpp.glass;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Norton TV's full-screen football table, opened from the Arsenal tile on Control Room: the competition of the match
 * on the tile. A league (Premier League, the Champions League's league phase) shows its table with Arsenal picked out;
 * a knockout cup (FA Cup, Carabao Cup) has no table, so it shows Arsenal's ties round by round instead. Left and right
 * switch between the competitions Arsenal are in this season; Back closes it. From ESPN's public API, like the tile.
 */
public final class TableActivity extends Activity {
    static final String EXTRA_LEAGUE = "league", EXTRA_LIGHT = "light";
    private static final String TAG = "GlassTable";
    private static final String SITE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    private static final String STANDINGS = "https://site.api.espn.com/apis/v2/sports/soccer/";
    private static final int RED = 0xFFE20520;
    /** The usual order of Arsenal's competitions, for the switcher. */
    private static final String[] ORDER = {"eng.1", "uefa.champions", "uefa.europa", "eng.fa", "eng.league_cup", "eng.charity"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> crests = new HashMap<>();
    private DashboardActivity.Look look;
    private boolean light;
    private float scale;
    private Typeface sans, mono;
    private FrameLayout root;

    private final List<String> comps = new ArrayList<>();
    private int at;
    /** What's been fetched: a table (rows of standings) or a list of Arsenal's ties, per competition. */
    private final Map<String, Page> pages = new HashMap<>();

    private static final class Row { String pos, team, abbr, logo; int p, w, d, l, pts; String gd; }
    private static final class Tie { String round, when, home, away, homeLogo, awayLogo, score, note; boolean played, ours1; }
    private static final class Page {
        String title = "";
        List<List<Row>> groups = new ArrayList<>();
        List<String> groupNames = new ArrayList<>();
        List<Tie> ties = new ArrayList<>();
        long fetched;
        String error;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        light = getIntent().getBooleanExtra(EXTRA_LIGHT, false);
        look = DashboardActivity.Look.of(light);
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        sans = getResources().getFont(R.font.geist);
        mono = getResources().getFont(R.font.geist_mono);
        root = new FrameLayout(this);
        root.setBackground(new DashboardActivity.Ground(light, null, 0, scale));
        getWindow().getDecorView().setBackgroundColor(look.ground);
        setContentView(root);
        String first = getIntent().getStringExtra(EXTRA_LEAGUE);
        if (first == null || first.isEmpty()) first = "eng.1";
        comps.add(first);
        draw();
        String start = first;
        background.execute(() -> {
            List<String> found = competitions(start);
            main.post(() -> { comps.clear(); comps.addAll(found); at = Math.max(0, comps.indexOf(start)); draw(); load(); });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        background.shutdownNow();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && at > 0) { at--; draw(); load(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT && at < comps.size() - 1) { at++; draw(); load(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        return super.onKeyDown(code, e);
    }

    // ---------------------------------------------------------------- data

    /**
     * The competitions Arsenal are in this season, in the usual order: the one asked for, the Premier League, and any
     * with a match played or coming (friendlies left out). Blocking.
     */
    private List<String> competitions(String first) {
        Set<String> seen = new LinkedHashSet<>();
        seen.add(first);
        seen.add("eng.1");
        for (String q : new String[]{"", "?fixture=true"}) {
            try {
                JSONArray ev = Football.get(SITE + "all/teams/" + Football.TEAM_ID + "/schedule" + q).optJSONArray("events");
                for (int i = 0; ev != null && i < ev.length(); i++) {
                    JSONObject lg = ev.getJSONObject(i).optJSONObject("league");
                    String slug = lg == null ? "" : lg.optString("slug");
                    if (!slug.isEmpty() && !slug.contains("friendly")) seen.add(slug);
                }
            } catch (Exception e) {
                Log.w(TAG, "Arsenal's competitions: " + e.getMessage());
            }
        }
        List<String> out = new ArrayList<>();
        for (String o : ORDER) if (seen.contains(o)) out.add(o);
        for (String s : seen) if (!out.contains(s)) out.add(s);
        return out;
    }

    private void load() {
        String slug = comps.get(at);
        Page have = pages.get(slug);
        if (have != null && System.currentTimeMillis() - have.fetched < 10 * 60_000L) return;
        background.execute(() -> {
            Page p = fetch(slug);
            main.post(() -> { pages.put(slug, p); if (comps.get(at).equals(slug)) draw(); });
        });
    }

    /** A competition's table, or for a cup (no table) Arsenal's ties in it. Blocking. */
    private Page fetch(String slug) {
        Page p = new Page();
        p.fetched = System.currentTimeMillis();
        p.title = Football.leagueName(slug);
        try {
            JSONObject s = Football.get(STANDINGS + slug + "/standings");
            JSONArray children = s.optJSONArray("children");
            for (int c = 0; children != null && c < children.length(); c++) {
                JSONObject child = children.getJSONObject(c);
                JSONObject st = child.optJSONObject("standings");
                JSONArray entries = st == null ? null : st.optJSONArray("entries");
                if (entries == null || entries.length() == 0) continue;
                List<Row> rows = new ArrayList<>();
                for (int i = 0; i < entries.length(); i++) rows.add(row(entries.getJSONObject(i), i + 1));
                rows.sort((a, b) -> Integer.compare(Integer.parseInt(a.pos), Integer.parseInt(b.pos)));
                p.groups.add(rows);
                p.groupNames.add(child.optString("name"));
            }
        } catch (Exception e) {
            Log.i(TAG, slug + " has no table: " + e.getMessage());
        }
        if (!p.groups.isEmpty()) return p;
        // A cup: Arsenal's ties, played and to come.
        try {
            Map<String, Tie> byId = new HashMap<>();
            List<String> order = new ArrayList<>();
            for (String q : new String[]{"", "?fixture=true"}) {
                JSONArray ev = Football.get(SITE + slug + "/teams/" + Football.TEAM_ID + "/schedule" + q).optJSONArray("events");
                for (int i = 0; ev != null && i < ev.length(); i++) {
                    JSONObject e = ev.getJSONObject(i);
                    Tie t = tie(e);
                    if (t == null) continue;
                    String id = e.optString("id");
                    if (!byId.containsKey(id)) order.add(id);
                    byId.put(id, t);
                }
            }
            for (String id : order) p.ties.add(byId.get(id));
            p.ties.sort((a, b) -> a.when.compareTo(b.when));
        } catch (Exception e) {
            p.error = "Couldn't reach ESPN just now";
            Log.w(TAG, slug + " ties: " + e.getMessage());
        }
        return p;
    }

    private static Row row(JSONObject entry, int fallbackPos) {
        Row r = new Row();
        JSONObject team = entry.optJSONObject("team");
        r.team = team == null ? "" : team.optString("shortDisplayName", team.optString("displayName"));
        r.abbr = team == null ? "" : team.optString("abbreviation");
        r.logo = team == null ? "" : Football.logo(team);
        Map<String, String> stats = new HashMap<>();
        JSONArray a = entry.optJSONArray("stats");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            if (s == null) continue;
            String k = s.optString("abbreviation", s.optString("name"));
            stats.put(k, s.optString("displayValue"));
        }
        r.pos = num(stats.get("R"), fallbackPos) + "";
        r.p = num(stats.get("GP"), 0);
        r.w = num(stats.get("W"), 0);
        r.d = num(stats.get("D"), 0);
        r.l = num(stats.get("L"), 0);
        r.pts = num(stats.get("P"), 0);
        r.gd = stats.get("GD") == null ? "0" : stats.get("GD");
        return r;
    }

    private static int num(String s, int fallback) {
        try { return Integer.parseInt(s == null ? "" : s.trim().replace("+", "")); } catch (Exception e) { return fallback; }
    }

    private Tie tie(JSONObject e) throws Exception {
        JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
        JSONArray teams = c.getJSONArray("competitors");
        if (teams.length() != 2) return null;
        JSONObject home = teams.getJSONObject(0), away = teams.getJSONObject(1);
        if ("away".equals(home.optString("homeAway"))) { JSONObject t = home; home = away; away = t; }
        Date when = Football.parseUtc(e.optString("date"));
        if (when == null) return null;
        Tie t = new Tie();
        t.when = String.format(Locale.UK, "%013d", when.getTime());
        JSONObject st = c.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        String state = type == null ? "" : type.optString("state");
        t.played = "post".equals(state) || "in".equals(state);
        JSONObject season = e.optJSONObject("seasonType");
        t.round = season == null ? "" : season.optString("name");
        t.home = home.getJSONObject("team").optString("shortDisplayName", home.getJSONObject("team").optString("displayName"));
        t.away = away.getJSONObject("team").optString("shortDisplayName", away.getJSONObject("team").optString("displayName"));
        t.homeLogo = Football.logo(home.getJSONObject("team"));
        t.awayLogo = Football.logo(away.getJSONObject("team"));
        t.ours1 = Football.TEAM_ID.equals(home.getJSONObject("team").optString("id"));
        boolean h24 = DateFormat.is24HourFormat(this);
        t.score = t.played ? Football.score(home) + "  –  " + Football.score(away)
                : String.valueOf(DateFormat.format(h24 ? "HH:mm" : "h:mma", when)).toLowerCase(Locale.UK);
        JSONArray notes = c.optJSONArray("notes");
        t.note = notes != null && notes.length() > 0 ? notes.optJSONObject(0).optString("headline") : "";
        if (t.note.isEmpty() && !t.played) t.note = String.valueOf(DateFormat.format("EEEE d MMMM", when));
        else if (t.note.isEmpty()) t.note = String.valueOf(DateFormat.format("EEE d MMM", when));
        return t;
    }

    // ---------------------------------------------------------------- drawing

    private int px(float design) { return Math.round(design * scale); }

    private TextView text(String s, float size, int colour, Typeface face, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(colour);
        t.setTypeface(face);
        t.setFontVariationSettings("'wght' " + weight);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private TextView label(String s, int colour) {
        TextView t = text(s.toUpperCase(Locale.UK), 18, colour, sans, 500);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radius));
        if (stroke != 0) g.setStroke(Math.max(1, px(1)), stroke);
        return g;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    private void draw() {
        root.removeAllViews();
        LinearLayout pageView = new LinearLayout(this);
        pageView.setOrientation(LinearLayout.VERTICAL);
        pageView.setPadding(px(56), px(44), px(56), px(44));
        root.addView(pageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String slug = comps.get(at);
        Page p = pages.get(slug);

        // Heading: the competition large, then one chip per competition (the shown one filled).
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("Arsenal  ·  " + (p != null && !p.groups.isEmpty() ? "Table" : p != null ? "Fixtures and results" : "Loading"), look.text2));
        titles.addView(gapV(10));
        titles.addView(text(Football.leagueName(slug), 56, look.text, sans, 400));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (int i = 0; i < comps.size(); i++) {
            boolean on = i == at;
            TextView chip = text(Football.leagueShort(comps.get(i)), 21, on ? look.ground : look.text2, sans, 500);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(px(20), 0, px(20), 0);
            chip.setBackground(box(on ? look.focus : Color.TRANSPARENT, on ? 0 : look.chipEdge, 999));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52));
            cl.leftMargin = px(12);
            head.addView(chip, cl);
        }
        pageView.addView(head);
        pageView.addView(gapV(16));
        TextView hint = text(comps.size() > 1 ? "◀ ▶  other competitions    ·    Back to close" : "Back to close", 18, look.text3, sans, 400);
        hint.setGravity(Gravity.END);
        pageView.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageView.addView(gapV(16));

        if (p == null) {
            pageView.addView(text("Loading…", 28, look.text2, sans, 400));
            return;
        }
        if (!p.groups.isEmpty()) drawTable(pageView, p);
        else drawTies(pageView, p);
    }

    private View gapV(float h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, px(h)));
        return v;
    }

    /** The table: up to 20 rows in one card; longer ones (the Champions League's 36) split over two cards. */
    private void drawTable(LinearLayout pageView, Page p) {
        List<Row> rows = new ArrayList<>();
        for (List<Row> g : p.groups) rows.addAll(g);
        int per = rows.size() > 20 ? (rows.size() + 1) / 2 : rows.size();
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        for (int start = 0; start < rows.size(); start += per) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(box(look.tile, look.edge, 22));
            card.setPadding(px(24), px(16), px(24), px(16));
            card.addView(tableRow(null, per));
            for (int i = start; i < Math.min(rows.size(), start + per); i++) card.addView(tableRow(rows.get(i), per));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (start > 0) lp.leftMargin = px(20);
            cards.addView(card, lp);
        }
        pageView.addView(cards, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** One line of the table (or its column heads, for null). Arsenal's in red. */
    private View tableRow(Row r, int per) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(px(12), 0, px(12), 0);
        boolean ours = r != null && Football.TEAM.equals(r.abbr);
        if (ours) line.setBackground(box(light ? 0x1AE20520 : 0x33E20520, 0, 10));
        int fg = ours ? (light ? 0xFFB0001A : 0xFFFF6B7A) : look.text, dim = ours ? fg : look.text2;
        int weight = ours ? 600 : 400;
        float size = per > 20 ? 20 : 22;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        line.setLayoutParams(lp);
        if (r == null) {
            line.addView(fixed(label("#", look.text3), 56));
            line.addView(hgap(52));
            line.addView(label("Team", look.text3), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            for (String h : new String[]{"P", "W", "D", "L", "GD", "Pts"}) line.addView(fixed(right(label(h, look.text3)), 72));
            return line;
        }
        line.addView(fixed(text(r.pos, size, dim, mono, weight), 56));
        line.addView(crest(r.logo), new LinearLayout.LayoutParams(px(34), px(34)));
        line.addView(hgap(18));
        line.addView(text(r.team, size + 2, fg, sans, ours ? 600 : 500), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int[] n = {r.p, r.w, r.d, r.l};
        for (int v : n) line.addView(fixed(right(text(String.valueOf(v), size, dim, mono, weight)), 72));
        line.addView(fixed(right(text(r.gd, size, dim, mono, weight)), 72));
        line.addView(fixed(right(text(String.valueOf(r.pts), size, fg, mono, 600)), 72));
        return line;
    }

    private View fixed(View v, float w) {
        v.setLayoutParams(new LinearLayout.LayoutParams(px(w), ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    /** An empty horizontal gap (a plain View left to wrap its content would take the whole height). */
    private View hgap(float w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px(w), 1));
        return v;
    }

    private TextView right(TextView t) { t.setGravity(Gravity.END); return t; }

    /** A cup: Arsenal's ties, oldest first, each round on its own line. */
    private void drawTies(LinearLayout pageView, Page p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(look.tile, look.edge, 22));
        card.setPadding(px(32), px(24), px(32), px(24));
        if (p.ties.isEmpty()) {
            card.addView(text(p.error != null ? p.error : "No Arsenal matches in this competition yet", 26, look.text2, sans, 400));
        }
        for (int i = 0; i < p.ties.size() && i < 9; i++) {
            Tie t = p.ties.get(i);
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, px(14), 0, px(14));
            line.addView(fixed(label(t.round.isEmpty() ? "Round" : t.round, t.played ? look.text2 : RED), 300));
            TextView home = text(t.home, 26, t.ours1 ? RED : look.text, sans, t.ours1 ? 600 : 500);
            home.setGravity(Gravity.END);
            line.addView(home, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(hgap(18));
            line.addView(crest(t.homeLogo), new LinearLayout.LayoutParams(px(44), px(44)));
            TextView score = text(t.score, t.played ? 30 : 26, look.text, t.played ? sans : mono, t.played ? 400 : 500);
            score.setGravity(Gravity.CENTER);
            line.addView(fixed(score, 180));
            line.addView(crest(t.awayLogo), new LinearLayout.LayoutParams(px(44), px(44)));
            line.addView(hgap(18));
            TextView away = text(t.away, 26, !t.ours1 ? RED : look.text, sans, !t.ours1 ? 600 : 500);
            line.addView(away, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView note = text(t.note, 20, look.text3, sans, 400);
            note.setGravity(Gravity.END);
            line.addView(fixed(note, 420));
            card.addView(line);
            if (i < p.ties.size() - 1) {
                View rule = new View(this);
                rule.setBackgroundColor(look.edge);
                card.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
            }
        }
        pageView.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** A club crest from ESPN (its dark-background version in the dark look), cached on disk. */
    private ImageView crest(String url) {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (url == null || url.isEmpty()) return img;
        String want = light ? url : url.replace("/500/", "/500-dark/");
        Bitmap known = crests.get(want);
        if (known != null) { img.setImageBitmap(known); return img; }
        File dir = new File(getCacheDir(), "crests");
        background.execute(() -> {
            File f = WatchNext.cached(this, dir, want, 96);
            if (f == null && !want.equals(url)) f = WatchNext.cached(this, dir, url, 96);
            Bitmap b = f == null ? null : BitmapFactory.decodeFile(f.getPath());
            if (b != null) main.post(() -> { crests.put(want, b); img.setImageBitmap(b); });
        });
        return img;
    }
}
