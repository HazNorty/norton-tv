package com.techpp.glass;

// Adapted from tv-vibehack's launcher (SleepService.java, pr/control-center), MIT License,
// Copyright (c) 2026 Gathin. See THIRD_PARTY_NOTICES.md.

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Puts the TV in standby for "Turn off now" and the sleep timer. This TV has no device-admin support,
 * so lockNow() isn't available; the accessibility "lock screen" global action does the same job.
 *
 * Also types a match into JioHotstar's search when a Cricket card has no direct link: JioHotstar's search
 * links open an empty search page. It only acts for 30 s after you pick a card and looks for nothing but the search box.
 *
 * And keeps Talkies as the home screen while the TV's own launcher stays enabled (on Google TV the Assistant needs
 * it: results such as "show me action movies" open on its home screen), though Android may send Home to it. So the
 * Home key opens Talkies, and so does the built-in launcher's home screen appearing on its own (after a restart,
 * or backing out of its pages), except within a few seconds of the Assistant, when it is showing results. The
 * built-in launchers are found at start: system apps that can be the home screen (launchers you installed, like
 * Projectivy, are left alone). The approach follows Matinee's HomeKeyService (MIT). Events come only from JioHotstar,
 * those launchers and the Assistant.
 *
 * Keys: it filters Home (above), OK while the sleep warning is up ("15 more minutes"), and remote buttons you've
 * given a job in Control Center → Remote keys (RemoteKeys); every other key passes through untouched.
 * The sleep warning is a small card drawn as an accessibility overlay, so no "display over other apps" permission.
 * You switch it on yourself in Settings → Device Preferences → Accessibility; if a force stop switches it off,
 * ServiceRepair can put it back (see there).
 */
public class SleepService extends AccessibilityService {
    private static final String TAG = "GlassSleep";
    private static final String SEARCH_BAR = Cricket.HOTSTAR + ":id/search_bar";
    private static final long SEARCH_WAIT_MS = 30_000;
    private static SleepService running;
    private static String pendingSearch;
    private static long pendingUntil;
    private static final String ASSISTANT = "com.google.android.katniss";
    /** The built-in launchers' home screens (package/class), found at start. */
    private final java.util.Set<String> stockHomes = new java.util.HashSet<>();
    private final java.util.Set<String> stockPackages = new java.util.HashSet<>();
    private static final long ASSISTANT_GRACE_MS = 8_000, BOOT_WINDOW_MS = 2 * 60_000, REOPEN_GUARD_MS = 1_000;
    private long assistantAt, openedAt;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Remote keys: the next button pressed is reported here instead of doing anything (null when not learning). */
    interface KeyListener { void onKey(int keyCode); }
    private static KeyListener learning;

    static boolean isRunning() { return running != null; }

    static boolean lock() {
        return running != null && running.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /** Types {@code text} into JioHotstar's search box once it shows up. False if the service is off. */
    static boolean searchHotstar(String text) {
        if (running == null) return false;
        pendingSearch = text;
        pendingUntil = SystemClock.uptimeMillis() + SEARCH_WAIT_MS;
        return true;
    }

    @Override protected void onServiceConnected() {
        running = this;
        findStockHomes();
        ServiceRepair.connected(this, SleepService.class);
        // After a restart the Google TV home screen is up before this service binds: take over once.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openHome();
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled();
        if (code == KeyEvent.KEYCODE_HOME) {
            // You chose a launcher you installed yourself (Projectivy…) as the home app: Home is its, not ours.
            if (otherLauncherChosen()) return false;
            if (up) openHome();
            return true;
        }
        if (warning != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (up) { hideWarning(); Sleep.extend(this); }
            return true;
        }
        KeyListener l = learning;
        if (l != null && RemoteKeys.learnable(code)) {
            if (up) { learning = null; main.post(() -> l.onKey(code)); }
            return true;
        }
        String action = l == null ? RemoteKeys.action(this, code) : null;
        if (action == null) return false;
        if (up) RemoteKeys.run(this, action);
        return true;
    }

    /** Reports the next remote button to {@code l} (or stops listening, with null). False if the service is off. */
    static boolean learn(KeyListener l) {
        learning = l;
        return running != null;
    }

    // ---------------------------------------------------------------- sleep timer warning

    private LinearLayout warning;
    private TextView warningText;
    private long warningEnds;
    private final Runnable tick = this::tick;

    /** A minute before the sleep timer: a card counting down, "Press OK for 15 more minutes". */
    static void warn(long at) {
        SleepService s = running;
        if (s != null) s.main.post(() -> s.showWarning(at));
    }

    static void cancelWarning() {
        SleepService s = running;
        if (s != null) s.main.post(s::hideWarning);
    }

    private void showWarning(long at) {
        warningEnds = at;
        if (warning == null) {
            float dp = getResources().getDisplayMetrics().density;
            warning = new LinearLayout(this);
            warning.setOrientation(LinearLayout.VERTICAL);
            warning.setPadding(Math.round(22 * dp), Math.round(16 * dp), Math.round(22 * dp), Math.round(16 * dp));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEB1C1C1E);
            bg.setCornerRadius(18 * dp);
            bg.setStroke(Math.round(dp), 0x33FFFFFF);
            warning.setBackground(bg);
            warningText = new TextView(this);
            warningText.setTextColor(0xFFF5F5F7);
            warningText.setTextSize(20);
            warningText.setTypeface(getResources().getFont(R.font.outfit));
            warningText.setFontVariationSettings("'wght' 600");
            TextView hint = new TextView(this);
            hint.setText("Press OK for 15 more minutes");
            hint.setTextColor(0xFFA1A1A6);
            hint.setTextSize(14);
            hint.setTypeface(getResources().getFont(R.font.outfit));
            warning.addView(warningText);
            warning.addView(hint);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.x = Math.round(40 * dp);
            lp.y = Math.round(40 * dp);
            try {
                getSystemService(WindowManager.class).addView(warning, lp);
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't show the sleep warning: " + e);
                warning = null;
            }
        }
        tick();
    }

    private void tick() {
        main.removeCallbacks(tick);
        long left = warningEnds - System.currentTimeMillis();
        if (left <= 500) {
            hideWarning();
            Sleep.fire(this);
            return;
        }
        if (warningText != null) warningText.setText("Turning off in " + ((left + 999) / 1000) + " s");
        main.postDelayed(tick, Math.max(50, left % 1000 == 0 ? 1000 : left % 1000));
    }

    private void hideWarning() {
        main.removeCallbacks(tick);
        if (warning == null) return;
        try {
            getSystemService(WindowManager.class).removeView(warning);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        warning = null;
        warningText = null;
    }

    /**
     * System apps that can be the home screen, other than Talkies and the boot-time fallbacks (negative priority):
     * Google TV's launcher, Android TV's, or the TV maker's. Window events are then limited to them, JioHotstar and
     * the Assistant.
     */
    private void findStockHomes() {
        stockHomes.clear();
        stockPackages.clear();
        android.content.pm.PackageManager pm = getPackageManager();
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_ALL)) {
            android.content.pm.ActivityInfo ai = ri.activityInfo;
            if (ri.priority < 0 || ai.packageName.equals(getPackageName())) continue;
            if ((ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            stockHomes.add(ai.packageName + "/" + ai.name);
            stockPackages.add(ai.packageName);
        }
        java.util.Set<String> watched = new java.util.HashSet<>(stockPackages);
        watched.add(Cricket.HOTSTAR);
        watched.add(ASSISTANT);
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = watched.toArray(new String[0]);
            setServiceInfo(info);
        }
        Log.i(TAG, "Built-in home screens: " + stockHomes);
    }

    /**
     * Whether the default home app is a launcher you installed (not Talkies, not one the TV came with, not Android's
     * "which app?" chooser). Then Talkies stays out of the way of the Home button.
     */
    private boolean otherLauncherChosen() {
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        if (r == null || r.activityInfo == null) return false;
        String pkg = r.activityInfo.packageName;
        return !pkg.equals(getPackageName()) && !pkg.equals("android") && !stockPackages.contains(pkg)
                && (r.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0;
    }

    void openHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - openedAt < REOPEN_GUARD_MS) return;  // the key and the window it brings up arrive together
        openedAt = now;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName(this, HomeActivity.class.getName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception e) {
            Log.w(TAG, "Couldn't open Talkies: " + e);
        }
    }

    @Override public boolean onUnbind(Intent intent) {
        // Unbound while still running: you switched it off, so don't put it back (a force stop never gets here).
        ServiceRepair.disconnected(this, SleepService.class);
        hideWarning();
        running = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
            if (ASSISTANT.contentEquals(pkg)) {
                assistantAt = SystemClock.elapsedRealtime();
            } else if (stockPackages.contains(pkg.toString()) && event.getClassName() != null
                    && (stockHomes.contains(pkg + "/" + event.getClassName())
                        || event.getClassName().toString().endsWith("ProfileChooserActivity"))) {
                // A built-in launcher's home screen, or the "Choose an account" screen Google TV puts up when it starts.
                if (SystemClock.elapsedRealtime() - assistantAt > ASSISTANT_GRACE_MS) openHome();
                else assistantAt = SystemClock.elapsedRealtime();  // browsing the results: keep them
            }
        }
        String text = pendingSearch;
        if (text == null) return;
        if (SystemClock.uptimeMillis() > pendingUntil) { pendingSearch = null; return; }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !Cricket.HOTSTAR.contentEquals(root.getPackageName())) return;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(SEARCH_BAR);
        if (found.isEmpty()) return;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (found.get(0).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            pendingSearch = null;
            Log.i(TAG, "Searched JioHotstar for " + text);
        }
    }

    @Override public void onInterrupt() { }
}
