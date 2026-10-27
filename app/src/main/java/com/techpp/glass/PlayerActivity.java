package com.techpp.glass;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Opens one UK catch-up player (BBC iPlayer, ITVX, Channel 4, My5) and gets out of the way. On this Bravia they
 * aren't Android apps: YouView runs them as HbbTV web apps through Sony's launcher, which takes the whole
 * "type=…, service=…, url=…" line in one string extra (found in the TV's logs; see tv-debloat's docs/uk-players.md).
 * Each player is an activity-alias in the manifest with its home page in a "url" meta-data entry.
 */
public final class PlayerActivity extends Activity {
    private static final String TAG = "GlassPlayer";
    static final String HBBTV_PKG = "com.sony.dtv.hbbtvlauncher";
    /** What a player tile's AppEntry uses as its package, e.g. "player:iplayer": unique per tile, never a real app. */
    static final String KEY_PREFIX = "player:";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String url = meta(this, "url");
        try {
            if (url == null) throw new IllegalStateException("no url for " + getComponentName());
            startActivity(intentFor(url));
        } catch (Exception e) {
            Log.w(TAG, "Couldn't open " + url + ": " + e);
            Toast.makeText(this, "This player isn't available on this TV", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** Sony's HbbTV launcher, pointed at a player's page. YouView itself always sends service=BBC_Sports. */
    static Intent intentFor(String url) {
        return new Intent(HBBTV_PKG + ".LaunchHbbTV")
                .setClassName(HBBTV_PKG, HBBTV_PKG + ".LaunchHbbTV")
                .putExtra("HBBTV_LAUNCHER_INFO", "type=bi, service=BBC_Sports, url=" + url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** A meta-data value of this activity-alias (Android hands an alias its own meta-data, not the target's). */
    private static String meta(Activity a, String name) {
        try {
            ActivityInfo info = a.getPackageManager().getActivityInfo(a.getComponentName(), PackageManager.GET_META_DATA);
            return info.metaData == null ? null : info.metaData.getString(name);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** True when the TV has Sony's HbbTV launcher, so the player tiles can work. */
    static boolean available(PackageManager pm) {
        try {
            return pm.getApplicationInfo(HBBTV_PKG, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
