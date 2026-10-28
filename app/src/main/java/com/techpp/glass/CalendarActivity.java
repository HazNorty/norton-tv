package com.techpp.glass;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Norton TV's full-screen calendar, opened from the dashboard's calendar tile: a week, Monday to Sunday, of all
 * your calendars in their colours. Left and right on the remote change the week, Back closes it. Reads the feeds
 * the dashboard saved (it refreshes them), so it opens without waiting on the network.
 */
public final class CalendarActivity extends Activity {
    static final String EXTRA_LIGHT = "light";
    private static final int MAX_PER_DAY = 9;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private Calendars calendars;
    private DashboardActivity.Look look;
    private float scale;
    private Typeface sans, mono;
    private LocalDate monday;
    private FrameLayout root;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        calendars = new Calendars(this);
        look = DashboardActivity.Look.of(getIntent().getBooleanExtra(EXTRA_LIGHT, false));
        scale = getResources().getDisplayMetrics().widthPixels / 1920f;
        sans = getResources().getFont(R.font.geist);
        mono = getResources().getFont(R.font.geist_mono);
        monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        root = new FrameLayout(this);
        root.setBackground(new DashboardActivity.Ground(getIntent().getBooleanExtra(EXTRA_LIGHT, false), null, 0, scale));
        getWindow().getDecorView().setBackgroundColor(look.ground);
        setContentView(root);
        load();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_REWIND) { monday = monday.minusWeeks(1); load(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { monday = monday.plusWeeks(1); load(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); load(); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        background.shutdownNow();
    }

    private void load() {
        LocalDate week = monday;
        background.execute(() -> {
            List<Calendars.Event> events = calendars.range(week, 7);
            main.post(() -> { if (week.equals(monday)) build(events); });
        });
    }

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
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable box(int fill, int stroke, float radius, float strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radius));
        if (stroke != 0) g.setStroke(Math.max(1, px(strokeW)), stroke);
        return g;
    }

    private void build(List<Calendars.Event> events) {
        root.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(px(56), px(44), px(56), px(44));
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LocalDate today = LocalDate.now(), sunday = monday.plusDays(6);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        DateTimeFormatter dm = DateTimeFormatter.ofPattern("d MMM", Locale.UK);
        String range = monday.getMonth() == sunday.getMonth()
                ? monday.getDayOfMonth() + " – " + sunday.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK))
                : monday.format(dm) + " – " + sunday.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK));
        head.addView(text(range, 52, look.text, sans, 300));
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        long weeks = ChronoUnit.WEEKS.between(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), monday);
        String when = weeks == 0 ? "This week" : weeks == 1 ? "Next week" : weeks == -1 ? "Last week"
                : weeks > 0 ? "In " + weeks + " weeks" : -weeks + " weeks ago";
        head.addView(text(when + "   ·   ◀ ▶ change week   ·   OK this week   ·   Back to close", 21, look.text2, sans, 400));
        page.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(64)));
        View g = new View(this);
        page.addView(g, new LinearLayout.LayoutParams(1, px(26)));

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        boolean h24 = DateFormat.is24HourFormat(this);
        for (int d = 0; d < 7; d++) {
            LocalDate day = monday.plusDays(d);
            boolean isToday = day.equals(today);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(px(20), px(20), px(20), px(20));
            col.setBackground(box(look.tile, isToday ? look.focus : look.edge, 20, isToday ? 2.5f : 1));
            col.addView(text(day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK).toUpperCase(Locale.UK)
                    + (isToday ? "  ·  TODAY" : ""), 17, isToday ? look.text : look.text2, sans, 600));
            col.addView(new View(this), new LinearLayout.LayoutParams(1, px(6)));
            col.addView(text(String.valueOf(day.getDayOfMonth()), 46, look.text, sans, isToday ? 500 : 300));
            col.addView(new View(this), new LinearLayout.LayoutParams(1, px(18)));

            List<Calendars.Event> on = new ArrayList<>();
            for (Calendars.Event e : events) if (e.day.equals(day)) on.add(e);
            int shown = 0;
            for (Calendars.Event e : on) {
                if (shown == MAX_PER_DAY - 1 && on.size() > MAX_PER_DAY) break;
                col.addView(event(e, h24));
                col.addView(new View(this), new LinearLayout.LayoutParams(1, px(12)));
                shown++;
            }
            if (on.size() > shown) col.addView(text("+" + (on.size() - shown) + " more", 18, look.text2, sans, 500));
            if (on.isEmpty()) col.addView(text("Nothing on", 19, look.text3, sans, 400));
            if (d > 0) cols.addView(new View(this), new LinearLayout.LayoutParams(px(16), 1));
            cols.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        page.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (!calendars.configured()) {
            page.addView(text("No calendars linked yet", 22, look.text2, sans, 400));
        }
    }

    /** One event: a bar in its calendar's colour, the time, and the title over up to two lines. */
    private View event(Calendars.Event e, boolean h24) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View bar = new View(this);
        bar.setBackground(box(e.colour, 0, 3, 0));
        row.addView(bar, new LinearLayout.LayoutParams(px(5), ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(12), px(2), 0, px(2));
        String time = e.allDay ? "All day" : String.format(Locale.UK, h24 ? "%02d:%02d" : "%d:%02d",
                h24 ? e.start.getHour() : (e.start.getHour() + 11) % 12 + 1, e.start.getMinute());
        body.addView(text(time, 17, e.colour, mono, 500));
        body.addView(new View(this), new LinearLayout.LayoutParams(1, px(4)));
        TextView title = text(e.title, 20, look.text, sans, 400);
        title.setSingleLine(false);
        title.setMaxLines(2);
        title.setLineSpacing(px(2), 1f);
        body.addView(title);
        row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }
}
