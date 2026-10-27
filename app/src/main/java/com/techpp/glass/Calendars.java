package com.techpp.glass;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Norton TV's Today row: the owner's public iCloud calendars (webcal links), merged, for today and tomorrow.
 * The links arrive as calendars.txt ("Name  webcal://…" per line), pushed over adb to the app's external files
 * folder; on first read they move into private storage and the pushed copy is deleted, as anyone with a link can
 * read that calendar. Each feed is fetched at most every 30 minutes, only while the home screen is in front, and
 * kept so the row shows straight away after a restart. A small iCalendar reader expands repeating events
 * (FREQ, INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH, EXDATE, moved occurrences).
 */
final class Calendars {
    private static final String TAG = "GlassCalendar";
    private static final long STALE_MS = 30 * 60_000L;
    /** One colour per calendar, in file order: soft, readable on dark and light. */
    static final int[] COLOURS = {0xFF5AA9F0, 0xFF3CC3A0, 0xFFF08A5D, 0xFFE76FA0, 0xFFB79CF2, 0xFFE8C15A};

    static final class Event {
        String title, calendar;
        int colour;
        LocalDate day;          // today or tomorrow: the day this card is listed under
        boolean allDay;
        LocalDateTime start, end;  // in the TV's time zone; for all-day events the start of the first day
        String id() { return calendar + "|" + title + "|" + day + "|" + (allDay ? "all" : start.toLocalTime()); }
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final File dir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    Calendars(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("calendar", Context.MODE_PRIVATE);
        dir = new File(ctx.getFilesDir(), "calendar");
    }

    /** True once calendar links have been set up on this TV. */
    boolean configured() { adoptPushedLinks(); return !links().isEmpty(); }

    /** Today's and tomorrow's events from the feeds saved last time (no network). Call off the main thread. */
    List<Event> cached() {
        adoptPushedLinks();
        List<String[]> links = links();
        List<Event> out = new ArrayList<>();
        ZoneId here = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(here);
        for (int i = 0; i < links.size(); i++) {
            File f = new File(dir, i + ".ics");
            if (!f.isFile()) continue;
            try {
                out.addAll(parse(read(f), links.get(i)[0], COLOURS[i % COLOURS.length], today, here));
            } catch (Exception e) {
                Log.w(TAG, "Couldn't read " + links.get(i)[0] + ": " + e);
            }
        }
        Collections.sort(out, (a, b) -> {
            int c = a.day.compareTo(b.day);
            if (c != 0) return c;
            if (a.allDay != b.allDay) return a.allDay ? -1 : 1;
            c = a.start.compareTo(b.start);
            return c != 0 ? c : a.title.compareToIgnoreCase(b.title);
        });
        return out;
    }

    /** Fetches the feeds if they're over 30 minutes old (or the day changed), then hands back the events. */
    void refreshIfStale(Consumer<List<Event>> done) {
        if (busy || !configured()) return;
        String today = LocalDate.now().toString();
        boolean fresh = System.currentTimeMillis() - prefs.getLong("ok_at", 0) < STALE_MS;
        if (fresh && today.equals(prefs.getString("day", ""))) return;
        busy = true;
        worker.execute(() -> {
            List<String[]> links = links();
            boolean any = false;
            for (int i = 0; i < links.size(); i++) {
                try {
                    byte[] body = fetch(links.get(i)[1]);
                    if (!new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8).contains("BEGIN:VCALENDAR"))
                        throw new IOException("not a calendar");
                    write(new File(dir, i + ".ics"), body);
                    any = true;
                } catch (Exception e) {
                    Log.w(TAG, "Couldn't fetch " + links.get(i)[0] + ": " + e);
                }
            }
            if (any) prefs.edit().putLong("ok_at", System.currentTimeMillis()).putString("day", today).apply();
            List<Event> events = cached();
            Log.i(TAG, "calendars=" + links.size() + " events today/tomorrow=" + events.size());
            main.post(() -> { busy = false; done.accept(events); });
        });
    }

    // ---------------------------------------------------------------- links

    /** Moves a pushed calendars.txt into private storage (and deletes the pushed copy). */
    private void adoptPushedLinks() {
        File pushed = new File(ctx.getExternalFilesDir(null), "calendars.txt");
        if (!pushed.isFile()) return;
        try {
            String text = new String(read(pushed), StandardCharsets.UTF_8);
            StringBuilder clean = new StringBuilder();
            for (String line : text.split("\\r?\\n")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s*((?:webcal|https?)://\\S+)\\s*$").matcher(line.trim());
                if (!m.find()) continue;
                String name = m.group(1).trim().replaceAll("[\\t|]+", " ");
                clean.append(name.isEmpty() ? "Calendar" : name).append('\t').append(m.group(2)).append('\n');
            }
            prefs.edit().putString("links", clean.toString()).remove("ok_at").apply();
            if (!pushed.delete()) Log.w(TAG, "Couldn't delete the pushed calendars.txt");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            Log.i(TAG, "Calendar links saved privately");
        } catch (IOException e) {
            Log.w(TAG, "Couldn't read calendars.txt: " + e);
        }
    }

    /** [name, https url] per calendar, in the file's order. */
    private List<String[]> links() {
        List<String[]> out = new ArrayList<>();
        for (String line : prefs.getString("links", "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String url = line.substring(tab + 1).trim();
            if (url.startsWith("webcal://")) url = "https://" + url.substring(9);
            out.add(new String[]{line.substring(0, tab), url});
        }
        return out;
    }

    // ---------------------------------------------------------------- iCalendar

    private static final class Vevent {
        Map<String, String> props = new HashMap<>();      // name → value (first of each)
        Map<String, String> params = new HashMap<>();     // name → its ;parameters
        List<String> exdates = new ArrayList<>();
    }

    /** Events from one feed that fall on today or tomorrow. */
    static List<Event> parse(byte[] ics, String calendar, int colour, LocalDate today, ZoneId here) {
        List<Vevent> events = new ArrayList<>();
        Vevent cur = null;
        int depth = 0;  // inside VEVENT but in a nested block (VALARM)
        for (String line : unfold(new String(ics, StandardCharsets.UTF_8))) {
            if (line.equals("BEGIN:VEVENT")) { cur = new Vevent(); depth = 0; continue; }
            if (cur == null) continue;
            if (line.startsWith("BEGIN:")) { depth++; continue; }
            if (line.startsWith("END:")) {
                if (depth > 0) { depth--; continue; }
                if (line.equals("END:VEVENT")) { events.add(cur); cur = null; }
                continue;
            }
            if (depth > 0) continue;
            int colon = quotedColon(line);
            if (colon < 0) continue;
            String head = line.substring(0, colon), value = line.substring(colon + 1);
            int semi = head.indexOf(';');
            String name = (semi < 0 ? head : head.substring(0, semi)).toUpperCase(Locale.ROOT);
            String params = semi < 0 ? "" : head.substring(semi + 1);
            if (name.equals("EXDATE")) {
                for (String v : value.split(",")) cur.exdates.add(params + "|" + v);
                continue;
            }
            if (!cur.props.containsKey(name)) { cur.props.put(name, value); cur.params.put(name, params); }
        }

        LocalDate end = today.plusDays(2);
        // Moved or edited single occurrences ("RECURRENCE-ID") replace that date of their series.
        Set<String> overridden = new HashSet<>();
        for (Vevent v : events) {
            String rid = v.props.get("RECURRENCE-ID");
            if (rid != null) {
                LocalDateTime t = time(rid, v.params.get("RECURRENCE-ID"), here);
                if (t != null) overridden.add(v.props.get("UID") + "|" + t.toLocalDate());
            }
        }
        List<Event> out = new ArrayList<>();
        for (Vevent v : events) {
            if ("CANCELLED".equalsIgnoreCase(v.props.get("STATUS"))) continue;
            String ds = v.props.get("DTSTART");
            if (ds == null) continue;
            String dsParams = v.params.get("DTSTART");
            boolean allDay = dsParams.contains("VALUE=DATE") && !dsParams.contains("VALUE=DATE-TIME") || ds.length() == 8;
            LocalDateTime start = time(ds, dsParams, here);
            if (start == null) continue;
            LocalDateTime finish = null;
            if (v.props.containsKey("DTEND")) finish = time(v.props.get("DTEND"), v.params.get("DTEND"), here);
            else if (v.props.containsKey("DURATION")) {
                try { finish = start.plus(Duration.parse(v.props.get("DURATION").replace("P", "PT").replace("PTT", "PT"))); }
                catch (RuntimeException ignored) { }
            }
            if (finish == null || !finish.isAfter(start)) finish = allDay ? start.plusDays(1) : start;
            Duration length = Duration.between(start, finish);

            List<LocalDateTime> starts = new ArrayList<>();
            String rrule = v.props.get("RRULE");
            if (rrule == null || v.props.containsKey("RECURRENCE-ID")) {
                starts.add(start);
            } else {
                Set<LocalDate> skip = new HashSet<>();
                for (String ex : v.exdates) {
                    int bar = ex.indexOf('|');
                    LocalDateTime t = time(ex.substring(bar + 1), ex.substring(0, bar), here);
                    if (t != null) skip.add(t.toLocalDate());
                }
                for (LocalDate d : occurrences(rrule, start.toLocalDate(), end)) {
                    if (skip.contains(d) || overridden.contains(v.props.get("UID") + "|" + d)) continue;
                    starts.add(LocalDateTime.of(d, start.toLocalTime()));
                }
            }
            String title = text(v.props.getOrDefault("SUMMARY", "Busy"));
            for (LocalDateTime s : starts) {
                LocalDateTime f = s.plus(length);
                for (LocalDate day = today; day.isBefore(end); day = day.plusDays(1)) {
                    LocalDateTime dayStart = day.atStartOfDay(), dayEnd = day.plusDays(1).atStartOfDay();
                    boolean on = allDay ? !s.isAfter(dayStart) && f.isAfter(dayStart)
                            : s.isBefore(dayEnd) && (f.isAfter(dayStart) || (f.equals(s) && !s.isBefore(dayStart)));
                    if (!on) continue;
                    // A timed event that started yesterday and runs into today is only listed under its first day.
                    if (!allDay && s.isBefore(dayStart) && day.isAfter(today)) continue;
                    Event e = new Event();
                    e.title = title;
                    e.calendar = calendar;
                    e.colour = colour;
                    e.day = day;
                    e.allDay = allDay;
                    e.start = s;
                    e.end = f;
                    out.add(e);
                }
            }
        }
        return out;
    }

    /**
     * The dates (from the series' first date up to, not including, {@code until}) a repeating event happens on.
     * Walks day by day from the start, which COUNT needs; a daily series from years back is still only a few
     * thousand steps.
     */
    static List<LocalDate> occurrences(String rrule, LocalDate first, LocalDate until) {
        Map<String, String> r = new HashMap<>();
        for (String part : rrule.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) r.put(part.substring(0, eq).toUpperCase(Locale.ROOT), part.substring(eq + 1));
        }
        String freq = r.getOrDefault("FREQ", "");
        int interval = 1, count = Integer.MAX_VALUE;
        try { interval = Math.max(1, Integer.parseInt(r.getOrDefault("INTERVAL", "1"))); } catch (NumberFormatException ignored) { }
        try { if (r.containsKey("COUNT")) count = Integer.parseInt(r.get("COUNT")); } catch (NumberFormatException ignored) { }
        LocalDate last = until.minusDays(1);
        if (r.containsKey("UNTIL")) {
            String u = r.get("UNTIL");
            try {
                LocalDate ud = LocalDate.parse(u.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
                if (ud.isBefore(last)) last = ud;
            } catch (RuntimeException ignored) { }
        }
        List<String> byDay = r.containsKey("BYDAY") ? Arrays.asList(r.get("BYDAY").split(",")) : Collections.emptyList();
        List<Integer> byMonthDay = ints(r.get("BYMONTHDAY")), byMonth = ints(r.get("BYMONTH"));

        List<LocalDate> out = new ArrayList<>();
        if (!Arrays.asList("DAILY", "WEEKLY", "MONTHLY", "YEARLY").contains(freq)) { out.add(first); return out; }
        int seen = 0;
        LocalDate weekOfFirst = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (LocalDate d = first; !d.isAfter(last) && seen < count; d = d.plusDays(1)) {
            if (!byMonth.isEmpty() && !byMonth.contains(d.getMonthValue())) continue;
            boolean match;
            switch (freq) {
                case "DAILY":
                    match = ChronoUnit.DAYS.between(first, d) % interval == 0 && (byDay.isEmpty() || dayMatches(byDay, d, false));
                    break;
                case "WEEKLY":
                    long weeks = ChronoUnit.WEEKS.between(weekOfFirst, d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                    match = weeks % interval == 0 && (byDay.isEmpty() ? d.getDayOfWeek() == first.getDayOfWeek() : dayMatches(byDay, d, false));
                    break;
                case "MONTHLY":
                    long months = ChronoUnit.MONTHS.between(first.withDayOfMonth(1), d.withDayOfMonth(1));
                    match = months % interval == 0 && (!byDay.isEmpty() ? dayMatches(byDay, d, true)
                            : !byMonthDay.isEmpty() ? monthDayMatches(byMonthDay, d) : d.getDayOfMonth() == first.getDayOfMonth());
                    break;
                default:  // YEARLY
                    long years = d.getYear() - first.getYear();
                    boolean inMonth = byMonth.isEmpty() ? d.getMonth() == first.getMonth() : true;
                    match = years % interval == 0 && inMonth && (!byDay.isEmpty() ? dayMatches(byDay, d, true)
                            : !byMonthDay.isEmpty() ? monthDayMatches(byMonthDay, d) : d.getDayOfMonth() == first.getDayOfMonth());
            }
            if (!match) continue;
            seen++;
            out.add(d);
        }
        return out;
    }

    /** BYDAY: "MO", or in months "2MO" (second Monday) and "-1FR" (last Friday). */
    private static boolean dayMatches(List<String> byDay, LocalDate d, boolean inMonth) {
        for (String b : byDay) {
            b = b.trim();
            if (b.length() < 2) continue;
            String code = b.substring(b.length() - 2);
            if (!code.equals(d.getDayOfWeek().name().substring(0, 2))) continue;
            String n = b.substring(0, b.length() - 2);
            if (n.isEmpty() || n.equals("+")) return true;
            if (!inMonth) return true;
            try {
                int ord = Integer.parseInt(n.replace("+", ""));
                int nth = (d.getDayOfMonth() - 1) / 7 + 1;
                int fromEnd = (d.lengthOfMonth() - d.getDayOfMonth()) / 7 + 1;
                if (ord > 0 ? nth == ord : fromEnd == -ord) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }

    private static boolean monthDayMatches(List<Integer> days, LocalDate d) {
        for (int n : days) if (n > 0 ? d.getDayOfMonth() == n : d.getDayOfMonth() == d.lengthOfMonth() + n + 1) return true;
        return false;
    }

    private static List<Integer> ints(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv == null) return out;
        for (String s : csv.split(",")) try { out.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { }
        return out;
    }

    /** "20261027T090000Z", "…T090000" with TZID, or "20261027", in the TV's time zone. */
    static LocalDateTime time(String value, String params, ZoneId here) {
        try {
            value = value.trim();
            if (value.length() == 8) return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
            LocalDateTime local = LocalDateTime.parse(value.substring(0, 15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"));
            ZoneId zone = here;
            if (value.endsWith("Z")) zone = ZoneId.of("UTC");
            else if (params != null && params.contains("TZID=")) {
                String tz = params.substring(params.indexOf("TZID=") + 5);
                int semi = tz.indexOf(';');
                if (semi >= 0) tz = tz.substring(0, semi);
                tz = tz.replace("\"", "");
                try { zone = ZoneId.of(tz); } catch (RuntimeException e) { zone = here; }
            }
            return ZonedDateTime.of(local, zone).withZoneSameInstant(here).toLocalDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Joins folded lines (a continuation starts with a space or tab). */
    private static List<String> unfold(String s) {
        List<String> out = new ArrayList<>();
        for (String raw : s.split("\\r?\\n")) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && !out.isEmpty()) {
                out.set(out.size() - 1, out.get(out.size() - 1) + raw.substring(1));
            } else if (!raw.isEmpty()) {
                out.add(raw);
            }
        }
        return out;
    }

    /** The colon between a property's name (and parameters, which may be quoted) and its value. */
    private static int quotedColon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ':' && !quoted) return i;
        }
        return -1;
    }

    private static String text(String v) {
        return v.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\").trim();
    }

    // ---------------------------------------------------------------- files and network

    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private void write(File f, byte[] data) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(data); }
        if (!tmp.renameTo(f)) throw new IOException("Cannot save " + f.getName());
    }
}
