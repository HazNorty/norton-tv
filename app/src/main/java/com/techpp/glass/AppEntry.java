package com.techpp.glass;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

/** One launchable TV app. Drawables and the glow colour are loaded lazily and cached. */
final class AppEntry {
    final String pkg;
    final String label;
    final ComponentName component;
    private final ActivityInfo info;

    private boolean loaded;
    private Drawable banner;
    private Drawable icon;
    private int glow = 0;

    AppEntry(ActivityInfo info, String label) {
        this.info = info;
        // A UK player tile (PlayerActivity alias) gets a key of its own, as the four share Norton TV's package.
        String player = info.metaData == null ? null : info.metaData.getString("player");
        this.pkg = player != null ? PlayerActivity.KEY_PREFIX + player : info.packageName;
        this.label = label;
        this.component = new ComponentName(info.packageName, info.name);
    }

    /** One of the UK catch-up player tiles: not a real app, so it can't be uninstalled and has no App info. */
    boolean isPlayer() { return pkg.startsWith(PlayerActivity.KEY_PREFIX); }

    private static android.graphics.Typeface font;

    /** The typeface player tiles are lettered in (Outfit, as the rest of the home screen). */
    static void setFont(android.graphics.Typeface f) { font = f; }

    Intent launchIntent() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private static int bannerW = 320, bannerH = 180;

    /** The pixel size banners are prepared at: the largest a tile is ever drawn. */
    static void setBannerSize(int w, int h) { bannerW = w; bannerH = h; }

    private void load(PackageManager pm) {
        if (loaded) return;
        loaded = true;
        if (isPlayer()) { banner = playerBanner(pm); return; }
        banner = sharpBanner(pm);
        if (banner == null) banner = info.loadBanner(pm);
        if (banner == null) banner = info.applicationInfo.loadBanner(pm);
        if (banner == null) icon = info.loadIcon(pm);
    }

    /**
     * The app's banner at its best: the highest-density version it ships (Android otherwise picks the one for this
     * TV's density, e.g. Netflix's 320 px instead of its 480 px), scaled once with good filtering to exactly the tile's
     * pixel size, with mipmaps so it stays clean when the tile is drawn smaller. Vector banners are drawn at that size.
     */
    private Drawable sharpBanner(PackageManager pm) {
        try {
            int id = info.banner != 0 ? info.banner : info.applicationInfo.banner;
            if (id == 0) return null;
            android.content.res.Resources res = pm.getResourcesForApplication(info.applicationInfo);
            Drawable d = res.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null);
            if (d == null) return null;
            Bitmap src;
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                src = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            } else {
                // Vector or layered banner: draw it straight at the target size.
                src = Bitmap.createBitmap(bannerW, bannerH, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, bannerW, bannerH);
                d.draw(new Canvas(src));
            }
            Bitmap out = fit(src, bannerW, bannerH);
            out.setHasMipMap(true);
            out.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
            return new android.graphics.drawable.BitmapDrawable(res, out);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A player tile's banner, drawn here as the players ship none: its colour (the alias's "colour" meta-data) with
     * a soft lighter band at the top, and its name centred in Outfit.
     */
    private Drawable playerBanner(PackageManager pm) {
        int colour = 0xFF3A3A3C;
        // Android turns a "#RRGGBB" meta-data value into an int when it reads the manifest.
        Object v = info.metaData.get("colour");
        if (v instanceof Integer) colour = (Integer) v;
        else if (v instanceof String) try { colour = Color.parseColor((String) v); } catch (RuntimeException ignored) { }
        Bitmap b = Bitmap.createBitmap(bannerW, bannerH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(colour);
        android.graphics.Paint sheen = new android.graphics.Paint();
        sheen.setShader(new android.graphics.LinearGradient(0, 0, 0, bannerH, 0x33FFFFFF, 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, bannerW, bannerH, sheen);
        android.graphics.Paint text = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTypeface(font != null ? font : android.graphics.Typeface.DEFAULT_BOLD);
        text.setFakeBoldText(font != null);
        text.setTextAlign(android.graphics.Paint.Align.CENTER);
        text.setTextSize(bannerH * 0.22f);
        float max = bannerW * 0.82f;
        if (text.measureText(label) > max) text.setTextSize(text.getTextSize() * max / text.measureText(label));
        android.graphics.Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, bannerW / 2f, bannerH / 2f - (fm.ascent + fm.descent) / 2f, text);
        b.setHasMipMap(true);
        b.setDensity(android.util.DisplayMetrics.DENSITY_DEFAULT);
        try {
            return new android.graphics.drawable.BitmapDrawable(pm.getResourcesForApplication(info.applicationInfo), b);
        } catch (PackageManager.NameNotFoundException e) {
            return new android.graphics.drawable.BitmapDrawable(null, b);
        }
    }

    /** Center-crops to w×h, halving first while the source is over twice as big (cleaner than one big jump). */
    private static Bitmap fit(Bitmap src, int w, int h) {
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        Bitmap b = src;
        while (scale < 0.5f) {
            b = Bitmap.createScaledBitmap(b, Math.max(1, b.getWidth() / 2), Math.max(1, b.getHeight() / 2), true);
            scale *= 2;
        }
        int sw = Math.round(w / scale), sh = Math.round(h / scale);
        int sx = Math.max(0, (b.getWidth() - sw) / 2), sy = Math.max(0, (b.getHeight() - sh) / 2);
        sw = Math.min(sw, b.getWidth() - sx);
        sh = Math.min(sh, b.getHeight() - sy);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
        c.drawBitmap(b, new android.graphics.Rect(sx, sy, sx + sw, sy + sh), new android.graphics.Rect(0, 0, w, h), p);
        return out;
    }

    Drawable banner(PackageManager pm) { load(pm); return banner; }

    Drawable icon(PackageManager pm) { load(pm); return icon; }

    /** A bright, saturated version of the banner's colourful pixels' average (grey if it has none), for the glow. */
    int glowColor(PackageManager pm) {
        if (glow != 0) return glow;
        load(pm);
        Drawable d = banner != null ? banner : icon;
        int avg = 0xFF8E8E93;
        if (d != null) {
            Bitmap b = Bitmap.createBitmap(12, 7, Bitmap.Config.ARGB_8888);
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            copy.setBounds(0, 0, 12, 7);
            copy.draw(new Canvas(b));
            long r = 0, g = 0, bl = 0, wsum = 0;
            for (int y = 0; y < 7; y++) {
                for (int x = 0; x < 12; x++) {
                    int c = b.getPixel(x, y);
                    if (Color.alpha(c) < 128) continue;
                    float[] hsv = new float[3];
                    Color.colorToHSV(c, hsv);
                    // Only colourful pixels count (Netflix's banner is mostly white around red letters).
                    if (hsv[1] < 0.35f || hsv[2] < 0.25f) continue;
                    int w = 1 + (int) (hsv[1] * hsv[2] * 8);
                    r += (long) Color.red(c) * w; g += (long) Color.green(c) * w; bl += (long) Color.blue(c) * w;
                    wsum += w;
                }
            }
            b.recycle();
            if (wsum > 0) avg = Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
        }
        float[] hsv = new float[3];
        Color.colorToHSV(avg, hsv);
        if (avg != 0xFF8E8E93) {
            hsv[1] = Math.min(1f, Math.max(hsv[1], 0.8f));
            hsv[2] = Math.max(hsv[2], 0.9f);
        }
        glow = Color.HSVToColor(hsv);
        return glow;
    }
}
