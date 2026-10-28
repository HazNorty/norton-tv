package com.techpp.glass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Norton TV's home screen, "Control Room": everything on one screen, no scrolling, laid out like a dashboard on a
 * 1920×1080 design grid (scaled to the TV). Live TV leads (now and next on your channel, optionally its picture);
 * beside it Arsenal's next match, the weather and today's and tomorrow's plans; then a row of apps and inputs; then
 * the Hue lights and Continue watching. Light and dark looks (Auto follows sunrise and sunset, like Talkies).
 * The Talkies screen (HomeActivity) stays as All apps, with its Inputs menu and settings. Ordinary Android focus
 * drives the remote: every tile you can press is a focusable view with a ring.
 */
public final class DashboardActivity extends Activity {
    private static final String TAG = "GlassDash";
    private static final String TVX = "com.sony.dtv.tvx", VPN_APP = "com.nordvpn.android";
    private static final int RED = 0xFFE20520;

    /** The two looks. Brand colours (apps, Arsenal, calendars) are the same in both. */
    static final class Look {
        int ground, tile, edge, text, text2, text3, focus, inset, track, chipEdge;
        int lightOnBg, lightOnEdge, lightOnFg, lightOffBg, lightOffEdge, lightOffFg, green, greenEdge, neutralTile;

        static Look of(boolean light) {
            Look l = new Look();
            if (light) {
                l.ground = 0xFFF1EEE8; l.tile = 0xD6FFFFFF; l.edge = 0x140E141B; l.text = 0xFF0E141B; l.text2 = 0xFF566273;
                l.text3 = 0xFF6E7A8A; l.focus = 0xFF0E141B; l.inset = 0xFFE6EBF1; l.track = 0xFFD5DCE5; l.chipEdge = 0xFFD3DAE3;
                l.lightOnBg = 0xFFFFF1D6; l.lightOnEdge = 0xFFE0A030; l.lightOnFg = 0xFF6A4300;
                l.lightOffBg = 0xFFF4F6F9; l.lightOffEdge = 0xFFDCE2EA; l.lightOffFg = 0xFF5E6A7A;
                l.green = 0xFF0B7A52; l.greenEdge = 0xFF8FD6B6; l.neutralTile = 0xD9E3E8EE;
            } else {
                l.ground = 0xFF0D0F12; l.tile = 0xD110161E; l.edge = 0x12FFFFFF; l.text = 0xFFE8EDF3; l.text2 = 0xFF8A97A8;
                l.text3 = 0xFF6F7D8E; l.focus = 0xFFFFFFFF; l.inset = 0xFF182230; l.track = 0xFF243041; l.chipEdge = 0xFF1F2834;
                l.lightOnBg = 0xFF3B2A0E; l.lightOnEdge = 0xFFFFC46B; l.lightOnFg = 0xFFFFDCA0;
                l.lightOffBg = 0xFF141B24; l.lightOffEdge = 0xFF232D3A; l.lightOffFg = 0xFF8A97A8;
                l.green = 0xFF5EE0A8; l.greenEdge = 0xFF1D4A3A; l.neutralTile = 0xD91A212B;
            }
            return l;
        }
    }

    private static final String[] APPEARANCE = {"Auto", "Light", "Dark"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs, homePrefs;
    private PackageManager pm;
    private float scale = 1f;
    private Typeface sans, mono;
    private Look look;
    private boolean light;

    private Weather weather;
    private Football football;
    private Calendars calendars;
    private Hue hue;

    private String weatherLine;
    private List<Cricket.Match> matches = new ArrayList<>();
    private Cricket.Match teamMatch;
    private List<Calendars.Event> events = new ArrayList<>();
    private List<Hue.Light> lights = new ArrayList<>();
    private List<Trending.Pick> continuePicks = new ArrayList<>(), trendPicks = new ArrayList<>();
    private Trending trending;
    private final java.util.Map<String, Bitmap> art = new java.util.HashMap<>();
    private Programme now;
    private boolean vpnOn;

    private FrameLayout root;
    private TvView tvView;
    private Uri tunedChannel;
    private LinearLayout page;
    private int focusId = View.NO_ID;
    private boolean inFront;

    /** What's on the home channel now, from the TV's programme guide. */
    private static final class Programme {
        Uri channel;
        String channelName = "", number = "", title = "", next = "";
        long start, end;
    }

    // ---------------------------------------------------------------- life cycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("dashboard", MODE_PRIVATE);
        homePrefs = getSharedPreferences("home", MODE_PRIVATE);
        pm = getPackageManager();
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        sans = getResources().getFont(R.font.geist);
        mono = getResources().getFont(R.font.geist_mono);
        AppEntry.setFont(sans);
        AppEntry.setBannerSize(px(200), px(112));
        weather = new Weather(this);
        football = new Football(this);
        calendars = new Calendars(this);
        hue = new Hue(this);
        trending = new Trending(this);
        root = new FrameLayout(this);
        setContentView(root);
        weatherLine = weather.cached();
        light = isLight();
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        inFront = true;
        boolean l = isLight();
        if (l != light) { light = l; build(); }
        registerReceiver(tick, new IntentFilter(Intent.ACTION_TIME_TICK));
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        cm.registerDefaultNetworkCallback(net);
        Network active = cm.getActiveNetwork();
        setVpn(isVpn(active == null ? null : cm.getNetworkCapabilities(active)));
        background.execute(() -> {
            List<Cricket.Match> saved = football.cached();
            Cricket.Match team = football.teamNext();
            List<Calendars.Event> ev = calendars.configured() ? calendars.cached() : new ArrayList<>();
            Programme p = readGuide();
            main.post(() -> {
                matches = saved; teamMatch = team; events = ev; now = p;
                build();
                refresh();
            });
        });
        loadContinue();
    }

    @Override protected void onPause() {
        super.onPause();
        inFront = false;
        try { unregisterReceiver(tick); } catch (IllegalArgumentException ignored) { }
        try { getSystemService(ConnectivityManager.class).unregisterNetworkCallback(net); } catch (Exception ignored) { }
        if (tvView != null) tvView.reset();
        tunedChannel = null;  // tuned again on the way back
    }

    @Override public void onBackPressed() { /* the home screen: Back stays here */ }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            boolean l = isLight();
            if (l != light) light = l;
            build();
            refresh();
        }
    };

    private final ConnectivityManager.NetworkCallback net = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { main.post(() -> setVpn(isVpn(c))); }
        @Override public void onLost(Network n) { main.post(() -> setVpn(false)); }
    };

    private static boolean isVpn(NetworkCapabilities c) { return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN); }

    private void setVpn(boolean on) {
        if (on == vpnOn) return;
        vpnOn = on;
        build();
    }

    /** Network refreshes, each at its own pace (the classes keep their own timers), only while Home is in front. */
    private void refresh() {
        if (!inFront) return;
        weather.refreshIfStale(line -> { weatherLine = line; boolean l = isLight(); if (l != light) light = l; build(); });
        football.refreshTeamIfStale(() -> { teamMatch = football.teamNext(); build(); });
        football.refreshIfStale(list -> { matches = list; build(); });
        calendars.refreshIfStale(list -> { events = list; build(); });
        hue.refreshIfStale(list -> { lights = list; build(); });
        trending.refreshIfStale(all -> background.execute(() -> {
            List<Trending.Pick> t = trendingNow();
            main.post(() -> { trendPicks = t; build(); });
        }));
    }

    private boolean isLight() {
        int mode = prefs.getInt("appearance", 0);
        return mode == 1 || (mode == 0 && weather.isDay());
    }

    // ---------------------------------------------------------------- sizes and drawing helpers

    /** A length on the 1920×1080 design grid, in this screen's pixels. */
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

    /** A small upper-case label, like the dashboard's "ARSENAL · NEXT". */
    private TextView label(String s, int colour) {
        TextView t = text(s.toUpperCase(Locale.UK), 18, colour, sans, 500);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, float radius, float strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radius));
        if (stroke != 0) g.setStroke(Math.max(1, px(strokeW)), stroke);
        return g;
    }

    /** A dashboard card: the tile colour, a hairline edge, generous padding. */
    private LinearLayout card(boolean vertical, float pad) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        c.setBackground(box(look.tile, look.edge, 22, 1));
        c.setPadding(px(pad), px(pad - 2), px(pad), px(pad - 2));
        return c;
    }

    private static LinearLayout.LayoutParams weight(float w, boolean vertical) {
        return vertical ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, w)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w);
    }

    private View gap(float design, boolean vertical) {
        View v = new View(this);
        v.setLayoutParams(vertical ? new LinearLayout.LayoutParams(1, px(design)) : new LinearLayout.LayoutParams(px(design), 1));
        return v;
    }

    private View spacer() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    /**
     * Makes a tile pressable with the remote: focusable, a ring in the look's focus colour while focused (drawn over
     * its own edge), a slight lift, and OK runs the action.
     */
    private void pressable(View v, int fill, int edge, float radius, float lift, Runnable action) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setId(View.generateViewId());
        v.setBackground(box(fill, edge, radius, 1));
        v.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? box(fill, look.focus, radius, 3) : box(fill, edge, radius, 1));
            view.animate().scaleX(has ? lift : 1f).scaleY(has ? lift : 1f).setDuration(140).start();
            view.setElevation(has ? px(8) : 0);
            if (has) focusTag = (String) view.getTag();
        });
        v.setOnClickListener(view -> action.run());
        // A lifted tile and its ring may spill a few pixels past its row: let every parent draw outside its bounds.
        v.post(() -> {
            for (android.view.ViewParent p = v.getParent(); p instanceof ViewGroup; p = p.getParent()) {
                ((ViewGroup) p).setClipChildren(false);
                ((ViewGroup) p).setClipToPadding(false);
            }
        });
    }

    /** Which tile had focus, by tag, so a rebuild (new score, new minute) puts focus back where it was. */
    private String focusTag = "live";

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "That isn't available on this TV", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- the screen

    /**
     * Builds the whole screen from what's known now. Cheap enough to redo on every change, except the live picture,
     * which stays playing underneath (see placeLivePicture).
     */
    private void build() {
        look = Look.of(light);
        getWindow().getDecorView().setBackgroundColor(look.ground);
        if (page != null) root.removeView(page);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(56), px(44), px(56), px(44));
        // The ground, with a hole where the live picture (under the page) shows through the Live TV tile.
        page.setBackground(new Ground(light, previewWanted() ? pictureAt : null, px(22), scale));
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        page.addView(header(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60)));
        page.addView(gap(22, true));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(liveTile(), weight(7, false));
        top.addView(gap(20, false));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sideTop = new LinearLayout(this);
        sideTop.setOrientation(LinearLayout.HORIZONTAL);
        sideTop.addView(arsenalTile(), weight(3, false));
        sideTop.addView(gap(20, false));
        sideTop.addView(weatherTile(), weight(2, false));
        side.addView(sideTop, weight(1.3f, true));  // the weather tile needs room for its hourly strip
        side.addView(gap(20, true));
        side.addView(calendarTile(), weight(1, true));
        top.addView(side, weight(5, false));
        page.addView(top, weight(1, true));
        page.addView(gap(20, true));

        page.addView(appsRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(150)));
        page.addView(gap(20, true));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(lightsTile(), weight(5, false));
        bottom.addView(gap(20, false));
        bottom.addView(continueTile(), weight(7, false));
        page.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(200)));

        placeLivePicture();

        // Focus goes back to the tile that had it (or Live TV, the first time).
        View f = root.findViewWithTag(focusTag);
        if (f == null || !f.isFocusable()) f = root.findViewWithTag("live");
        if (f != null) f.requestFocus();
    }

    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        boolean h24 = DateFormat.is24HourFormat(this);
        long t = System.currentTimeMillis();
        h.addView(text(String.valueOf(DateFormat.format(h24 ? "HH:mm" : "h:mm", t)), 56, look.text, sans, 300));
        h.addView(gap(22, false));
        h.addView(text(String.valueOf(DateFormat.format("EEEE d MMMM", t)), 24, look.text2, sans, 400));
        h.addView(spacer());

        String w = weatherLine == null ? null : weatherLine.trim();
        if (w != null && !w.isEmpty()) {
            int sky = weather.code() >= 0 ? Weather.icon(weather.code(), weather.codeIsDay()) : R.drawable.ic_weather_sun;
            TextView chip = chip(cap(w), look.text2, look.chipEdge, sky);
            h.addView(chip);
            h.addView(gap(14, false));
        }
        boolean vpnInstalled = pm.getLeanbackLaunchIntentForPackage(VPN_APP) != null;
        if (vpnOn || vpnInstalled) {
            TextView vpn = chip(vpnOn ? "VPN · On" : "VPN · Off", vpnOn ? look.green : look.text3, vpnOn ? look.greenEdge : look.chipEdge, R.drawable.ic_vpn);
            vpn.setTag("vpn");
            pressable(vpn, Color.TRANSPARENT, vpnOn ? look.greenEdge : look.chipEdge, 999, 1.04f, () -> {
                Intent i = pm.getLeanbackLaunchIntentForPackage(VPN_APP);
                if (i != null) start(i);
            });
            h.addView(vpn);
            h.addView(gap(14, false));
        }
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.ic_settings);
        gear.setImageTintList(ColorStateList.valueOf(look.text2));
        gear.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        gear.setPadding(px(14), px(14), px(14), px(14));
        gear.setContentDescription("Settings");
        gear.setTag("settings");
        pressable(gear, Color.TRANSPARENT, look.chipEdge, 999, 1.08f, this::openSettings);
        h.addView(gear, new LinearLayout.LayoutParams(px(56), px(56)));
        return h;
    }

    /** "10° clear" → "10° Clear". */
    private static String cap(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase(Locale.UK).split(" ")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.isLetter(w.charAt(0)) ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : w);
        }
        return b.toString();
    }

    private TextView chip(String s, int colour, int edge, int icon) {
        TextView t = text(s, 21, colour, sans, 500);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(18), 0, px(20), 0);
        t.setBackground(box(Color.TRANSPARENT, edge, 999, 1));  // the linen shows through the chips
        android.graphics.drawable.Drawable d = getDrawable(icon);
        if (d != null) {
            d = d.mutate();
            d.setTint(colour);
            d.setBounds(0, 0, px(24), px(24));
            t.setCompoundDrawablesRelative(d, null, null, null);
            t.setCompoundDrawablePadding(px(10));
        }
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(52)));
        return t;
    }

    // ---------------------------------------------------------------- live TV

    private View liveTile() {
        FrameLayout tile = new FrameLayout(this);
        tile.setTag("live");
        tile.setClipToOutline(true);
        boolean preview = previewWanted();
        // The picture area stays dark in both looks; with the live picture on it is clear, so the picture shows.
        int fill = preview ? Color.TRANSPARENT : light ? 0xFF1A222D : 0xFF121A24;
        pressable(tile, fill, look.edge, 22, 1.012f, this::openLiveTv);

        if (!preview) {
            // No picture: the channel's name large and quiet, so the tile still reads as "the TV".
            TextView big = text(now != null && !now.channelName.isEmpty() ? now.channelName : "Live TV", 120, 0x22FFFFFF, sans, 700);
            big.setGravity(Gravity.CENTER);
            tile.addView(big, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(32), px(26), px(32), px(28));
        info.setBackgroundColor(0xD9060A0F);
        LinearLayout badge = new LinearLayout(this);
        badge.setOrientation(LinearLayout.HORIZONTAL);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        TextView live = text("LIVE", 18, Color.WHITE, mono, 500);
        live.setPadding(px(10), px(5), px(10), px(5));
        live.setBackground(box(RED, 0, 6, 0));
        badge.addView(live);
        badge.addView(gap(14, false));
        String ch = now == null ? "Freeview and YouView" : now.channelName + (now.number.isEmpty() ? "" : "  ·  " + now.number);
        badge.addView(text(ch, 22, 0xFFA9B6C6, sans, 400));
        info.addView(badge);
        info.addView(gap(14, true));
        info.addView(text(now != null && !now.title.isEmpty() ? now.title : "Watch TV", 42, Color.WHITE, sans, 500));
        info.addView(gap(14, true));
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        if (now != null && now.end > now.start) {
            boolean h24 = DateFormat.is24HourFormat(this);
            String f = h24 ? "HH:mm" : "h:mm";
            prog.addView(text(String.valueOf(DateFormat.format(f, now.start)), 18, 0xFF8A97A8, mono, 400));
            prog.addView(gap(16, false));
            float done = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - now.start) / (float) (now.end - now.start)));
            prog.addView(bar(done, 0xFF243041, Color.WHITE, 6), new LinearLayout.LayoutParams(0, px(6), 1f));
            prog.addView(gap(16, false));
            prog.addView(text(String.valueOf(DateFormat.format(f, now.end)), 18, 0xFF8A97A8, mono, 400));
            if (!now.next.isEmpty()) {
                prog.addView(gap(22, false));
                prog.addView(text("Next · " + now.next, 18, 0xFFC9D3DE, sans, 400));
            }
        } else {
            prog.addView(text("Press OK for live TV", 20, 0xFF8A97A8, sans, 400));
        }
        info.addView(prog);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        il.gravity = Gravity.BOTTOM;
        tile.addView(info, il);
        return tile;
    }

    /** Where the live picture sits on the screen, once known (so a rebuild can leave its hole straight away). */
    private android.graphics.Rect pictureAt;

    /**
     * The page's ground: Linen (chosen from five on the design canvas). A warm linen by day, a charcoal felt by
     * night: a very fine weave of hairlines every few pixels over the ground colour, darkening softly towards the
     * edges. Optionally with a rounded hole for the live picture: the window behind turns see-through once a TV
     * picture plays under it, so the page paints the ground itself (or light mode shows the dark video plane).
     */
    static final class Ground extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint weave = new android.graphics.Paint(), edge = new android.graphics.Paint();
        private final int base;
        private final boolean light;
        private final android.graphics.Rect hole;
        private final float radius, scale;

        Ground(boolean light, android.graphics.Rect hole, float radius, float scale) {
            this.light = light;
            this.hole = hole;
            this.radius = radius;
            this.scale = scale;
            base = Look.of(light).ground;
            // One tile of the weave: a thread across its top and one down its left side.
            int pitch = Math.max(3, Math.round(6 * scale));
            Bitmap tile = Bitmap.createBitmap(pitch, pitch, Bitmap.Config.ARGB_8888);
            int across = light ? Color.argb(9, 90, 70, 40) : Color.argb(6, 255, 255, 255);
            int down = light ? Color.argb(8, 90, 70, 40) : Color.argb(5, 255, 255, 255);
            for (int i = 0; i < pitch; i++) { tile.setPixel(i, 0, across); tile.setPixel(0, i, down); }
            tile.setPixel(0, 0, across);
            weave.setShader(new android.graphics.BitmapShader(tile, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT));
        }

        @Override protected void onBoundsChange(android.graphics.Rect b) {
            // An ellipse 120% of the screen each way, centred a little above the middle: clear to 55–60%, then the
            // edge tint (warm brown by day, black at night).
            float w = Math.max(1, b.width()), h = Math.max(1, b.height());
            android.graphics.RadialGradient g = new android.graphics.RadialGradient(0, 0, 1.2f * w,
                    new int[]{Color.TRANSPARENT, Color.TRANSPARENT, light ? Color.argb(26, 120, 95, 60) : Color.argb(115, 0, 0, 0)},
                    new float[]{0f, light ? 0.6f : 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(1f, h / w);
            m.postTranslate(b.left + 0.5f * w, b.top + 0.4f * h);
            g.setLocalMatrix(m);
            edge.setShader(g);
        }

        @Override public void draw(android.graphics.Canvas c) {
            int saved = c.save();
            if (hole != null) {
                android.graphics.Path p = new android.graphics.Path();
                p.setFillType(android.graphics.Path.FillType.EVEN_ODD);
                p.addRect(new android.graphics.RectF(getBounds()), android.graphics.Path.Direction.CW);
                p.addRoundRect(new android.graphics.RectF(hole), radius, radius, android.graphics.Path.Direction.CW);
                c.clipPath(p);
            }
            c.drawColor(base);
            c.drawRect(getBounds(), weave);
            c.drawRect(getBounds(), edge);
            c.restoreToCount(saved);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { }
        @Override public int getOpacity() { return hole == null ? android.graphics.PixelFormat.OPAQUE : android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private boolean previewWanted() {
        return prefs.getBoolean("live_preview", false) && now != null && now.channel != null && inFront;
    }

    /**
     * The live picture (Settings → Live picture). It sits under the page, lined up with the Live TV tile, and is
     * kept across rebuilds: rebuilding the page (a light switched, a new minute) leaves it playing, and it is only
     * tuned again when the channel changes.
     */
    private void placeLivePicture() {
        if (!previewWanted()) {
            if (tvView != null) { tvView.reset(); root.removeView(tvView); tvView = null; tunedChannel = null; }
            return;
        }
        if (tvView == null) {
            tvView = new TvView(this);
            tvView.setFocusable(false);
            tvView.setStreamVolume(0f);
            root.addView(tvView, 0, new FrameLayout.LayoutParams(0, 0));
        }
        LinearLayout built = page;
        View tile = page.findViewWithTag("live");
        page.post(() -> {
            if (tvView == null || tile == null || built != page) return;
            // The tile's place before its focus lift, so the picture doesn't move as focus comes and goes.
            int x = 0, y = 0;
            for (View v = tile; v != root && v != null; v = (View) v.getParent()) { x += v.getLeft(); y += v.getTop(); }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tvView.getLayoutParams();
            if (lp.leftMargin != x || lp.topMargin != y || lp.width != tile.getWidth() || lp.height != tile.getHeight()) {
                lp.leftMargin = x; lp.topMargin = y; lp.width = tile.getWidth(); lp.height = tile.getHeight();
                tvView.setLayoutParams(lp);
            }
            android.graphics.Rect at = new android.graphics.Rect(x, y, x + tile.getWidth(), y + tile.getHeight());
            if (!at.equals(pictureAt)) {
                pictureAt = at;
                page.setBackground(new Ground(light, at, px(22), scale));
            }
        });
        if (!now.channel.equals(tunedChannel)) {
            try {
                String input = inputFor(now.channel);
                if (input != null) { tvView.tune(input, now.channel); tunedChannel = now.channel; }
            } catch (Exception e) {
                Log.w(TAG, "Live picture failed: " + e);
            }
        }
    }

    /** A thin progress bar: `done` of it filled. */
    private View bar(float done, int track, int fill, float h) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setBackground(box(track, 0, h / 2, 0));
        View f = new View(this);
        f.setBackground(box(fill, 0, h / 2, 0));
        b.addView(f, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.001f, done)));
        b.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0.001f, 1f - done)));
        return b;
    }

    /**
     * Live TV on the tile's channel: Sony's TV app takes a channel from the guide to tune to (as the HDMI tiles do
     * with an input). Without one, the TV app as it was left, which hands over to YouView on this TV.
     */
    private void openLiveTv() {
        if (now != null && now.channel != null) {
            Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(now.channel, "vnd.android.cursor.item/channel").setPackage(TVX);
            if (pm.resolveActivity(v, 0) != null) {
                Log.i(TAG, "Tuning to " + now.channel);
                start(v);
                return;
            }
        }
        Intent tv = pm.getLeanbackLaunchIntentForPackage(TVX);
        if (tv == null) tv = new Intent(Intent.ACTION_MAIN).setClassName(TVX, TVX + ".MainActivity");
        start(tv);
    }

    /**
     * The home channel (Norton TV settings → Home channel; BBC One, 1, by default) and what's on it now and next,
     * from the TV's programme guide. Null when the guide can't be read. Blocking.
     */
    private Programme readGuide() {
        String want = prefs.getString("home_channel", "1");
        try (Cursor c = getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (c == null) return null;
            long id = -1; String num = "", name = "";
            while (c.moveToNext()) {
                String n = c.getString(1) == null ? "" : c.getString(1).trim();
                if (id < 0 || n.replaceFirst("^0+", "").equals(want)) {
                    id = c.getLong(0); num = n; name = c.getString(2) == null ? "" : c.getString(2);
                    if (n.replaceFirst("^0+", "").equals(want)) break;
                }
            }
            if (id < 0) return null;
            Programme p = new Programme();
            p.channel = TvContract.buildChannelUri(id);
            p.number = num;
            p.channelName = name;
            long t = System.currentTimeMillis();
            try (Cursor g = getContentResolver().query(TvContract.buildProgramsUriForChannel(id, t - 3 * 3600_000L, t + 4 * 3600_000L),
                    new String[]{TvContract.Programs.COLUMN_TITLE, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                            TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS}, null, null,
                    TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS)) {
                while (g != null && g.moveToNext()) {
                    long s = g.getLong(1), e = g.getLong(2);
                    if (s <= t && e > t) { p.title = g.getString(0); p.start = s; p.end = e; }
                    else if (s >= t && p.next.isEmpty() && !p.title.isEmpty()) p.next = g.getString(0);
                }
            } catch (Exception e) {
                Log.i(TAG, "No programme guide: " + e);
            }
            return p;
        } catch (Exception e) {
            Log.i(TAG, "No channels: " + e);
            return null;
        }
    }

    private String inputFor(Uri channel) {
        try (Cursor c = getContentResolver().query(channel, new String[]{TvContract.Channels.COLUMN_INPUT_ID}, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- Arsenal, weather, calendars

    private View arsenalTile() {
        LinearLayout c = card(true, 28);
        Cricket.Match m = null;
        for (Cricket.Match x : matches) if (x.ours && x.live) { m = x; break; }
        if (m == null) m = teamMatch;
        if (m == null) for (Cricket.Match x : matches) if (x.ours && !x.ended) { m = x; break; }
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(label(m != null && m.live ? "Arsenal · live" : "Arsenal · next", look.text2));
        head.addView(spacer());
        if (m != null) head.addView(label(m.live ? m.status : countdown(m.at), RED));
        c.addView(head);
        c.addView(spacer(true));
        if (m == null) {
            c.addView(text("No fixture yet", 30, look.text, sans, 400));
            c.addView(spacer(true));
            return c;
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(crest(m.short1, m.t1, m.img1));
        row.addView(spacer());
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER_HORIZONTAL);
        if (m.live || m.ended) {
            mid.addView(text(m.s1 + "  –  " + m.s2, 44, look.text, sans, 400));
            mid.addView(text(m.live ? "Live" : "Full time", 18, look.text2, sans, 400));
        } else {
            String when = m.start == null ? "" : m.start;
            int sp = when.indexOf(' ');
            mid.addView(text(sp > 0 ? when.substring(sp + 1) : when, 42, look.text, sans, 300));
            mid.addView(text(m.at > 0 ? String.valueOf(DateFormat.format("EEE d MMM", m.at)) : (sp > 0 ? when.substring(0, sp) : "Today"), 18, look.text2, sans, 400));
        }
        row.addView(mid);
        row.addView(spacer());
        row.addView(crest(m.short2, m.t2, m.img2));
        c.addView(row);
        c.addView(spacer(true));
        boolean home = Football.TEAM.equals(m.short1);
        c.addView(text(m.kind + "  ·  " + (home ? "Home" : "Away"), 18, look.text2, sans, 400));
        return c;
    }

    private View spacer(boolean vertical) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f));
        return v;
    }

    /**
     * The team's crest from ESPN (its dark-background version in the dark look, so Spurs or Newcastle don't vanish),
     * cached on disk. Until it arrives, or with no connection, a round stand-in with the team's three letters.
     */
    private View crest(String abbr, String name, String url) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        boolean ours = Football.TEAM.equals(abbr);
        FrameLayout slot = new FrameLayout(this);
        TextView dot = text(abbr == null ? "" : abbr, 20, ours ? Color.WHITE : look.text, sans, 600);
        dot.setGravity(Gravity.CENTER);
        dot.setBackground(box(ours ? (light ? RED : 0xFF3A0A10) : look.inset, ours ? RED : look.edge, 999, 1));
        slot.addView(dot, new FrameLayout.LayoutParams(px(76), px(76), Gravity.CENTER));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        slot.addView(img, new FrameLayout.LayoutParams(px(96), px(96), Gravity.CENTER));
        col.addView(slot, new LinearLayout.LayoutParams(px(96), px(96)));
        col.addView(gap(6, true));
        col.addView(text(name == null ? "" : name, 18, ours ? RED : look.text2, sans, ours ? 600 : 400));
        if (url != null && !url.isEmpty()) {
            String want = light ? url : url.replace("/500/", "/500-dark/");
            File dir = new File(getCacheDir(), "crests");
            background.execute(() -> {
                File f = WatchNext.cached(this, dir, want, 192);
                if (f == null && !want.equals(url)) f = WatchNext.cached(this, dir, url, 192);
                Bitmap b = f == null ? null : BitmapFactory.decodeFile(f.getPath());
                if (b != null) main.post(() -> { img.setImageBitmap(b); dot.setVisibility(View.GONE); });
            });
        }
        return col;
    }

    /** "4d 17h", "5h 20m", "starting". */
    private static String countdown(long at) {
        long ms = at - System.currentTimeMillis();
        if (at <= 0) return "";
        if (ms <= 0) return "starting";
        long m = ms / 60_000L, h = m / 60, d = h / 24;
        return d > 0 ? d + "d " + (h % 24) + "h" : h > 0 ? h + "h " + (m % 60) + "m" : (m % 60) + "m";
    }

    private View weatherTile() {
        LinearLayout c = card(true, 28);
        String w = weatherLine == null ? "" : weatherLine.trim();
        int sp = w.indexOf(' ');
        // Now: the temperature large with today's sky beside it, its condition underneath.
        LinearLayout nowRow = new LinearLayout(this);
        nowRow.setOrientation(LinearLayout.HORIZONTAL);
        nowRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(label("Weather", look.text2));
        left.addView(gap(10, true));
        left.addView(text(sp > 0 ? w.substring(0, sp) : (w.isEmpty() ? "–" : w), 54, look.text, sans, 300));
        left.addView(gap(6, true));
        left.addView(text(sp > 0 ? cap(w.substring(sp + 1)) : "", 19, look.text2, sans, 400));
        nowRow.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (weather.code() >= 0) nowRow.addView(icon(Weather.icon(weather.code(), weather.codeIsDay()), look.text, 64));
        c.addView(nowRow);
        c.addView(spacer(true));
        // Through the day, like Apple's: now, then every three hours.
        List<Weather.Hour> hours = weather.hours();
        if (!hours.isEmpty()) {
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < hours.size() && i <= 9; i += 3) {
                Weather.Hour h = hours.get(i);
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView at = text(i == 0 ? "Now" : String.format(Locale.UK, "%02d", h.hour), 15, look.text3, mono, 500);
                at.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(at);
                col.addView(gap(6, true));
                col.addView(icon(Weather.icon(h.code, h.day), look.text2, 26));
                col.addView(gap(6, true));
                TextView deg = text(h.temp + "°", 18, look.text, sans, 500);
                deg.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(deg);
                strip.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            c.addView(strip);
        }
        return c;
    }

    /** A line icon at a design size, tinted. */
    private ImageView icon(int res, int colour, float size) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setImageTintList(ColorStateList.valueOf(colour));
        v.setLayoutParams(new LinearLayout.LayoutParams(px(size), px(size)));
        return v;
    }

    private View calendarTile() {
        LinearLayout c = card(true, 28);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(label("Today · tomorrow", look.text2));
        head.addView(spacer());
        // When the calendars were last fetched (OK on the tile opens the full week).
        long at = calendars.updatedAt();
        if (at > 0) {
            boolean today = DateUtils.isToday(at);
            String time = String.valueOf(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mma", at)).toLowerCase(Locale.UK);
            head.addView(label("Updated " + (today ? time : DateFormat.format("EEE", at) + " " + time), look.text3));
        }
        c.addView(head);
        c.setTag("calendar");
        pressable(c, look.tile, look.edge, 22, 1.012f,
                () -> start(new Intent(this, CalendarActivity.class).putExtra(CalendarActivity.EXTRA_LIGHT, light)));
        c.addView(gap(16, true));
        LocalDate today = LocalDate.now();
        int shown = 0;
        for (Calendars.Event e : events) {
            if (shown == 3) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View dot = new View(this);
            dot.setBackground(box(e.colour, 0, 999, 0));
            row.addView(dot, new LinearLayout.LayoutParams(px(12), px(12)));
            row.addView(gap(18, false));
            String when = e.allDay ? "All day" : String.format(Locale.UK, DateFormat.is24HourFormat(this) ? "%02d:%02d" : "%d:%02d",
                    DateFormat.is24HourFormat(this) ? e.start.getHour() : (e.start.getHour() + 11) % 12 + 1, e.start.getMinute());
            TextView t = text(when, 22, e.colour, mono, 500);
            row.addView(t, new LinearLayout.LayoutParams(px(140), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView title = text(e.title, 23, look.text, sans, 400);
            row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(text(e.day.equals(today) ? "Today" : "Tomorrow", 18, look.text3, sans, 400));
            c.addView(row);
            c.addView(gap(16, true));
            shown++;
        }
        if (shown == 0) c.addView(text(calendars.configured() ? "Nothing today or tomorrow" : "No calendars linked yet", 23, look.text2, sans, 400));
        return c;
    }

    // ---------------------------------------------------------------- apps and inputs

    /** One tile in the apps row. */
    private static final class Tile {
        String name, kind, key;
        int colour;
        Runnable open;
        android.graphics.drawable.Drawable icon;  // an installed app's own icon
        String[] logos;                           // or logo addresses to try in turn (the UK players, HDMI devices)
    }

    /**
     * Each service's own app icon, from its website (the UK players are web apps on this TV, with no icon of
     * their own). Downloaded once and kept on disk; the second address is Google's copy of the site icon.
     */
    private static String[] siteLogo(String direct, String domain) {
        String google = "https://www.google.com/s2/favicons?sz=256&domain=" + domain;
        return direct == null ? new String[]{google} : new String[]{direct, google};
    }

    /** Logos kept between rebuilds (once a minute), so the tiles don't blink. */
    private final java.util.Map<String, Bitmap> logoCache = new java.util.HashMap<>();

    private View appsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        List<Tile> tiles = appTiles();
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            LinearLayout v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setPadding(px(20), px(18), px(20), px(18));
            v.setTag("app:" + t.key);
            boolean neutral = t.colour == 0;
            int fill = neutral ? look.neutralTile : t.colour;
            int fg = neutral ? look.text : Color.WHITE;
            pressable(v, fill, neutral ? look.edge : fill, 18, 1.06f, t.open);
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            boolean hasLogo = t.icon != null || t.logos != null;
            if (hasLogo) {
                top.addView(logo(t));
                top.addView(spacer());
            }
            // With a logo the kind ("BBC") says nothing more; HDMI tiles keep theirs ("HDMI 1").
            if (!hasLogo || t.key.startsWith("hdmi:")) {
                TextView kind = text(t.kind, 16, fg, mono, 500);
                kind.setAlpha(0.72f);
                top.addView(kind);
            }
            v.addView(top);
            v.addView(spacer(true));
            // Long names (a device over HDMI, "PlayStation 4") go smaller over two lines rather than being cut off.
            boolean longName = t.name.length() > 10;
            TextView name = text(t.name, longName ? 21 : 26, fg, sans, 600);
            if (longName) { name.setSingleLine(false); name.setMaxLines(2); name.setLineSpacing(0, 0.95f); }
            v.addView(name);
            if (i > 0) row.addView(gap(16, false));
            row.addView(v, weight(1, false));
        }
        return row;
    }

    /** A tile's logo: a small rounded square, like an app icon on a phone. */
    private View logo(Tile t) {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int size = px(52), radius = px(12);
        img.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        img.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius); }
        });
        img.setClipToOutline(true);
        if (t.icon != null) { img.setImageDrawable(t.icon); return img; }
        Bitmap known = logoCache.get(t.key);
        if (known != null) { img.setImageBitmap(known); return img; }
        img.setVisibility(View.INVISIBLE);
        File dir = new File(getCacheDir(), "logos");
        background.execute(() -> {
            Bitmap b = null;
            for (String url : t.logos) {
                File f = WatchNext.cached(this, dir, url, 192);
                if (f != null && (b = BitmapFactory.decodeFile(f.getPath())) != null) break;
            }
            Bitmap got = b;
            if (got != null) main.post(() -> { logoCache.put(t.key, got); img.setImageBitmap(got); img.setVisibility(View.VISIBLE); });
        });
        return img;
    }

    /** The UK players' logos. */
    private static String[] playerLogo(String pkg) {
        switch (pkg) {
            case "player:iplayer": return siteLogo("https://iplayer-web.files.bbci.co.uk/page-builder/58.2.9/img/icons/touch-icon-192.png", "bbc.co.uk");
            case "player:itvx": return siteLogo(null, "www.itv.com");
            case "player:channel4": return siteLogo("https://www.channel4.com/apple-touch-icon.png", "channel4.com");
            case "player:my5": return siteLogo("https://www.channel5.com/my5_192.png", "channel5.com");
            default: return null;
        }
    }

    /** A logo for what's plugged in, from its name as the TV hears it over HDMI-CEC ("PlayStation 4", "Fire TV"). */
    private static String[] deviceLogo(String name) {
        String n = name.toLowerCase(Locale.UK);
        if (n.contains("playstation") || n.matches(".*\\bps[345]\\b.*")) return siteLogo(null, "playstation.com");
        if (n.contains("fire") || n.contains("amazon")) return siteLogo(null, "amazon.co.uk");
        if (n.contains("xbox")) return siteLogo(null, "xbox.com");
        if (n.contains("nintendo") || n.contains("switch")) return siteLogo(null, "nintendo.com");
        if (n.contains("apple")) return siteLogo(null, "apple.com");
        if (n.contains("sky")) return siteLogo(null, "sky.com");
        if (n.contains("roku")) return siteLogo(null, "roku.com");
        if (n.contains("chromecast") || n.contains("google")) return siteLogo(null, "store.google.com");
        return null;
    }

    /**
     * Your dock (as arranged in All apps; Live TV left out, it has the big tile), HDMI 1 and 2, then All apps:
     * nine tiles in one row.
     */
    private List<Tile> appTiles() {
        List<AppEntry> all = apps();
        List<String> order = new ArrayList<>();
        String saved = homePrefs.getString("order_dock", null);
        if (saved != null) order.addAll(Arrays.asList(saved.split(",")));
        for (String d : getResources().getStringArray(R.array.dock_packages)) if (!order.contains(d)) order.add(d);
        List<Tile> out = new ArrayList<>();
        for (String key : order) {
            if (out.size() >= 6 || key.equals(TVX)) continue;
            for (AppEntry a : all) {
                if (!a.pkg.equals(key)) continue;
                Tile t = new Tile();
                t.key = a.pkg;
                t.name = shortName(a);
                t.kind = kindOf(a);
                t.colour = tileColour(a);
                t.open = () -> start(a.launchIntent());
                if (a.isPlayer()) t.logos = playerLogo(a.pkg);
                else try { t.icon = pm.getApplicationIcon(a.pkg); } catch (PackageManager.NameNotFoundException ignored) { }
                out.add(t);
                break;
            }
        }
        // HDMI 1 and HDMI 2, connected or not (the others are under All apps → Inputs), named after what's plugged
        // in when the TV knows it over HDMI-CEC: such devices are inputs of their own, under their port.
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim != null) {
            try {
                List<TvInputInfo> ports = new ArrayList<>(), devices = new ArrayList<>();
                for (TvInputInfo i : tim.getTvInputList()) {
                    if (i.getType() != TvInputInfo.TYPE_HDMI) continue;
                    (i.getParentId() == null ? ports : devices).add(i);
                }
                ports.sort((a, b) -> String.valueOf(a.loadLabel(this)).compareTo(String.valueOf(b.loadLabel(this))));
                for (TvInputInfo i : ports.subList(0, Math.min(2, ports.size()))) {
                    Tile t = new Tile();
                    t.key = "hdmi:" + i.getId();
                    String port = String.valueOf(i.loadLabel(this)), device = null;
                    for (TvInputInfo d : devices) {
                        if (!i.getId().equals(d.getParentId())) continue;
                        device = String.valueOf(d.loadLabel(this));
                        break;
                    }
                    boolean named = device != null && !device.trim().isEmpty() && !device.equals(port);
                    t.name = named ? device.trim() : port;
                    t.kind = named ? port.toUpperCase(Locale.UK) : "INPUT";
                    if (named) t.logos = deviceLogo(device);
                    t.colour = 0;
                    t.open = () -> {
                        Intent v = new Intent(Intent.ACTION_VIEW);
                        v.setDataAndType(TvContract.buildChannelUriForPassthroughInput(i.getId()), "vnd.android.cursor.item/channel");
                        start(v);
                    };
                    out.add(t);
                }
            } catch (Exception e) {
                Log.i(TAG, "No inputs: " + e);
            }
        }
        Tile apps = new Tile();
        apps.key = "all"; apps.name = "All apps"; apps.kind = "···"; apps.colour = 0;
        apps.open = () -> start(new Intent(this, HomeActivity.class));
        out.add(apps);
        return out;
    }

    /** The TV's apps, with the UK players (Norton TV's own tiles) in place of Norton TV itself. */
    private List<AppEntry> apps() {
        List<AppEntry> all = new ArrayList<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        boolean players = PlayerActivity.available(pm) || new File(getExternalFilesDir(null), "players_preview").isFile();
        for (ResolveInfo ri : pm.queryIntentActivities(q, PackageManager.GET_META_DATA)) {
            AppEntry a = new AppEntry(ri.activityInfo, String.valueOf(ri.loadLabel(pm)).trim());
            if (ri.activityInfo.packageName.equals(getPackageName()) && !(a.isPlayer() && players)) continue;
            all.add(a);
        }
        return all;
    }

    private static String shortName(AppEntry a) {
        switch (a.pkg) {
            case "player:iplayer": return "iPlayer";
            case "com.google.android.youtube.tvmusic": return "YT Music";
            default: return a.label;
        }
    }

    private static String kindOf(AppEntry a) {
        switch (a.pkg) {
            case "player:iplayer": return "BBC";
            case "player:itvx": return "ITV";
            case "player:channel4": return "C4";
            case "player:my5": return "C5";
            case "com.google.android.youtube.tv": case "com.google.android.youtube.tvmusic": return "YT";
            default: return "APP";
        }
    }

    /** Each app's own colour (the players' set colours, else its banner's), deepened so white text reads on it. */
    private int tileColour(AppEntry a) {
        switch (a.pkg) {
            case "player:iplayer": return 0xFFC2006B;
            case "player:itvx": return 0xFF1C3A50;
            case "player:channel4": return 0xFF0F7D7F;
            case "player:my5": return 0xFF4F2775;
            case "com.google.android.youtube.tv": return 0xFFB3121B;
            case "com.google.android.youtube.tvmusic": return 0xFF8E1018;
            default:
                float[] hsv = new float[3];
                Color.colorToHSV(a.glowColor(pm), hsv);
                hsv[1] = Math.min(hsv[1], 0.75f);
                hsv[2] = Math.min(hsv[2], 0.55f);
                return Color.HSVToColor(hsv);
        }
    }

    // ---------------------------------------------------------------- lights

    private View lightsTile() {
        LinearLayout c = card(true, 24);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(label("Lights", look.text2));
        head.addView(spacer());
        int on = 0;
        for (Hue.Light l : lights) if (l.on && l.reachable) on++;
        if (hue.linked() && !lights.isEmpty()) head.addView(label(on == 0 ? "All off" : on + " on", look.lightOnEdge));
        c.addView(head);
        c.addView(gap(14, true));
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        if (!hue.linked()) {
            TextView link = text(hue.linking() ? "Press the button on the Hue Bridge now" : "Link your Hue lights  ·  press OK", 22,
                    look.text, sans, 500);
            link.setGravity(Gravity.CENTER_VERTICAL);
            link.setPadding(px(20), 0, px(20), 0);
            link.setTag("hue-link");
            pressable(link, look.lightOffBg, look.lightOffEdge, 14, 1.02f, this::linkHue);
            grid.addView(link, weight(1, false));
        } else {
            int max = lights.size() > 4 ? 3 : 4;
            for (int i = 0; i < Math.min(max, lights.size()); i++) {
                if (i > 0) grid.addView(gap(12, false));
                grid.addView(bulb(lights.get(i)), weight(1, false));
            }
            if (lights.size() > 4) {
                grid.addView(gap(12, false));
                TextView more = text("+" + (lights.size() - 3) + " more", 20, look.text2, sans, 500);
                more.setGravity(Gravity.CENTER);
                more.setTag("lights-more");
                pressable(more, look.lightOffBg, look.lightOffEdge, 14, 1.05f, () -> start(new Intent(this, HomeActivity.class)));
                grid.addView(more, weight(1, false));
            }
            if (lights.isEmpty()) grid.addView(text("Looking for your lights…", 22, look.text2, sans, 400));
        }
        c.addView(grid, weight(1, true));
        return c;
    }

    private View bulb(Hue.Light l) {
        boolean lit = l.on && l.reachable;
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(px(14), px(12), px(14), px(12));
        b.setTag("light:" + l.id);
        int fill = lit ? look.lightOnBg : look.lightOffBg, edge = lit ? look.lightOnEdge : look.lightOffEdge, fg = lit ? look.lightOnFg : look.lightOffFg;
        pressable(b, fill, edge, 14, 1.06f, () -> hue.toggle(l, list -> { lights = list; build(); }));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_bulb);
        icon.setImageTintList(ColorStateList.valueOf(fg));
        b.addView(icon, new LinearLayout.LayoutParams(px(24), px(24)));
        b.addView(spacer(true));
        TextView name = text(l.name, 19, fg, sans, 500);
        b.addView(name);
        b.setContentDescription(l.name + ", " + l.status());
        return b;
    }

    private void linkHue() {
        Toast.makeText(this, "Press the round button on your Hue Bridge now", Toast.LENGTH_LONG).show();
        hue.link(ok -> {
            Toast.makeText(this, ok ? "Hue lights linked" : "The bridge button wasn't pressed in time. Try again", Toast.LENGTH_LONG).show();
            hue.refreshIfStale(list -> { lights = list; build(); });
            build();
        });
        build();
    }

    // ---------------------------------------------------------------- continue watching

    private void loadContinue() {
        background.execute(() -> {
            List<Trending.Pick> list;
            try { list = WatchNext.load(this); } catch (Exception e) { list = new ArrayList<>(); }
            List<Trending.Pick> out = list, trend = trendingNow();
            main.post(() -> { continuePicks = out; trendPicks = trend; build(); });
        });
    }

    /** This hour's trending picks on your UK players (TMDB, refreshed by Trending), "New in the UK" first. */
    private List<Trending.Pick> trendingNow() {
        List<Trending.Pick> out = new ArrayList<>(Trending.forThisHour(trending.cached(Trending.INDIA)));
        out.addAll(Trending.forThisHour(trending.cached(Trending.TRENDING)));
        return out;
    }

    /**
     * Continue watching (Android TV's Watch Next list, filled by apps such as YouTube), topped up with what's
     * trending on iPlayer, ITVX, Channel 4 and My5, so the tile is never empty.
     */
    private View continueTile() {
        LinearLayout c = card(true, 24);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        int cont = Math.min(4, continuePicks.size());
        List<Trending.Pick> shown = new ArrayList<>(continuePicks.subList(0, cont));
        for (Trending.Pick p : trendPicks) if (shown.size() < 4) shown.add(p);
        head.addView(label(cont == 0 && !shown.isEmpty() ? "Trending in the UK" : cont < shown.size() ? "Continue watching · trending" : "Continue watching", look.text2));
        head.addView(spacer());
        c.addView(head);
        c.addView(gap(14, true));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int n = shown.size();
        for (int i = 0; i < n; i++) {
            Trending.Pick p = shown.get(i);
            boolean trend = i >= cont;
            FrameLayout card = new FrameLayout(this);
            card.setTag("cont:" + i);
            card.setClipToOutline(true);
            pressable(card, look.inset, look.inset, 12, 1.06f, () -> openPick(p));
            if (p.image != null && p.image.isFile()) {
                ImageView img = new ImageView(this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                // Kept between rebuilds (one a minute), not decoded again each time. TMDB's pictures are 1280 wide.
                String path = p.image.getPath();
                Bitmap bm = art.get(path);
                if (bm == null) { bm = BitmapFactory.decodeFile(path, sample(trend ? 4 : 2)); if (bm != null) art.put(path, bm); }
                if (art.size() > 12) art.clear();
                img.setImageBitmap(bm);
                card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            if (trend && p.service != null && !p.service.isEmpty()) {
                TextView svc = text(p.service, 13, Color.WHITE, mono, 500);
                svc.setPadding(px(8), px(4), px(8), px(4));
                svc.setBackground(box(0xB3000000, 0, 6, 0));
                FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sl.leftMargin = px(10); sl.topMargin = px(10);
                card.addView(svc, sl);
            }
            TextView title = text(p.title, 17, Color.WHITE, sans, 500);
            title.setPadding(px(12), px(26), px(12), px(12));
            title.setBackgroundColor(0x99000000);
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.gravity = Gravity.BOTTOM;
            card.addView(title, tl);
            if (p.progress > 0) {
                View bar = bar(p.progress, 0x55FFFFFF, Color.WHITE, 4);
                FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(4));
                bl.gravity = Gravity.BOTTOM;
                card.addView(bar, bl);
            }
            if (i > 0) row.addView(gap(14, false));
            row.addView(card, weight(1, false));
        }
        if (n == 0) row.addView(text("Programmes you start in your apps show up here", 22, look.text2, sans, 400));
        c.addView(row, weight(1, true));
        return c;
    }

    private static BitmapFactory.Options sample(int s) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return o;
    }

    private void openPick(Trending.Pick p) {
        if (p.intentUri != null) {
            try { start(Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME)); return; } catch (Exception ignored) { }
        }
        Intent i = null;
        if (p.pkg != null && p.pkg.startsWith(PlayerActivity.KEY_PREFIX)) {
            for (AppEntry a : apps()) if (a.pkg.equals(p.pkg)) { i = a.launchIntent(); break; }
        } else if (p.pkg != null) {
            i = pm.getLeanbackLaunchIntentForPackage(p.pkg);
        }
        if (i != null) start(i);
    }

    // ---------------------------------------------------------------- settings

    private void openSettings() {
        int mode = prefs.getInt("appearance", 0);
        boolean preview = prefs.getBoolean("live_preview", false);
        String[] rows = {
                "Appearance: " + APPEARANCE[mode],
                "Live picture on the home screen: " + (preview ? "On" : "Off"),
                "Home channel: " + prefs.getString("home_channel", "1"),
                "All apps and Norton TV settings",
                "TV settings"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Norton TV")
                .setItems(rows, (d, which) -> {
                    switch (which) {
                        case 0:
                            prefs.edit().putInt("appearance", (mode + 1) % APPEARANCE.length).apply();
                            light = isLight();
                            build();
                            openSettings();
                            break;
                        case 1:
                            prefs.edit().putBoolean("live_preview", !preview).apply();
                            build();
                            openSettings();
                            break;
                        case 2: {
                            String[] chans = {"1", "2", "3", "4", "5"};
                            String cur = prefs.getString("home_channel", "1");
                            String next = chans[(Arrays.asList(chans).indexOf(cur) + 1) % chans.length];
                            prefs.edit().putString("home_channel", next).apply();
                            background.execute(() -> { Programme p = readGuide(); main.post(() -> { now = p; build(); openSettings(); }); });
                            break;
                        }
                        case 3: start(new Intent(this, HomeActivity.class)); break;
                        default: start(new Intent(Settings.ACTION_SETTINGS)); break;
                    }
                })
                .show();
    }
}
