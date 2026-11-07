package com.techpp.glass;

import android.content.Context;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Placeholder lights and calendar events for design work on the emulator, which has no Hue Bridge or calendars.
 * Only on when a file named design_preview is in the app's files folder (like players_preview); never on the TV.
 */
final class Demo {
    private Demo() { }

    static boolean on(Context c) {
        File dir = c.getExternalFilesDir(null);
        return dir != null && new File(dir, "design_preview").isFile();
    }

    static List<Hue.Light> lights() {
        String[] names = {"Living room", "Hallway", "Kitchen", "Bedroom", "Landing"};
        List<Hue.Light> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            Hue.Light l = new Hue.Light();
            l.id = String.valueOf(i + 1);
            l.name = names[i];
            l.on = i == 0 || i == 2;
            l.bri = 160;
            l.reachable = true;
            out.add(l);
        }
        return out;
    }

    static List<Calendars.Event> events() {
        LocalDate today = LocalDate.now(), tomorrow = today.plusDays(1);
        List<Calendars.Event> out = new ArrayList<>();
        out.add(event("Event one", today, 16, 0, 0xFF3F8EF0));
        out.add(event("Event two", today, 19, 30, 0xFF33B679));
        out.add(event("Event three", tomorrow, 9, 0, 0xFF3F8EF0));
        return out;
    }

    private static Calendars.Event event(String title, LocalDate day, int h, int m, int colour) {
        Calendars.Event e = new Calendars.Event();
        e.title = title;
        e.calendar = "Placeholder";
        e.colour = colour;
        e.day = day;
        e.start = LocalDateTime.of(day, LocalTime.of(h, m));
        e.end = e.start.plusHours(1);
        return e;
    }
}
