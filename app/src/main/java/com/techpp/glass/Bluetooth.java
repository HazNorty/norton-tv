package com.techpp.glass;

import android.Manifest;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Control Center → Bluetooth: your paired speakers and headphones, connected or disconnected with one press. Android's
 * public API can list them but not connect them, so this calls the audio profile's own connect and disconnect, which a
 * plain app may use. The approach follows CleanYourTV's Bluetooth Connect add-on (MIT).
 *
 * There's deliberately no "turn Bluetooth off": on many TVs the remote is a Bluetooth device too.
 */
final class Bluetooth {
    private static final String TAG = "GlassBluetooth";
    /** The audio profile, kept once Android hands it over (it's a connection to the Bluetooth service). */
    private static BluetoothA2dp a2dp;

    private Bluetooth() { }

    static BluetoothAdapter adapter(Context c) {
        BluetoothManager m = c.getSystemService(BluetoothManager.class);
        return m == null ? null : m.getAdapter();
    }

    /** Android 12 and later ask once for "Nearby devices"; before that the permission comes with the install. */
    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean on(Context c) {
        BluetoothAdapter a = adapter(c);
        try { return a != null && a.isEnabled(); } catch (SecurityException e) { return false; }
    }

    /** Hands {@code done} the audio profile on the main thread, or null when Bluetooth is off or Android refuses. */
    static void audio(Context c, Consumer<BluetoothA2dp> done) {
        if (a2dp != null) { done.accept(a2dp); return; }
        BluetoothAdapter a = adapter(c);
        boolean asked;
        try {
            asked = a != null && a.isEnabled() && a.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    a2dp = (BluetoothA2dp) proxy;
                    done.accept(a2dp);
                }
                @Override public void onServiceDisconnected(int profile) { a2dp = null; }
            }, BluetoothProfile.A2DP);
        } catch (SecurityException e) {
            asked = false;
        }
        if (!asked) done.accept(null);
    }

    /** Paired devices that can play sound, by name (the remote and game controllers are left out). */
    static List<BluetoothDevice> speakers(Context c) {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter a = adapter(c);
        try {
            if (a != null) for (BluetoothDevice d : a.getBondedDevices()) if (isAudio(d.getBluetoothClass())) out.add(d);
            out.sort((x, y) -> name(x).compareToIgnoreCase(name(y)));
        } catch (SecurityException e) {
            Log.w(TAG, "Not allowed to list paired devices: " + e);
        }
        return out;
    }

    private static boolean isAudio(BluetoothClass c) {
        if (c == null) return true;  // unknown: show it rather than hide a speaker
        return c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO || c.hasService(BluetoothClass.Service.AUDIO);
    }

    static String name(BluetoothDevice d) {
        try {
            String n = Build.VERSION.SDK_INT >= 30 ? d.getAlias() : null;  // getAlias is Android 11+
            if (n == null || n.isEmpty()) n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    /** BluetoothProfile.STATE_CONNECTED, _CONNECTING, _DISCONNECTED…; DISCONNECTED when it can't be read. */
    static int state(BluetoothA2dp p, BluetoothDevice d) {
        try { return p == null ? BluetoothProfile.STATE_DISCONNECTED : p.getConnectionState(d); }
        catch (SecurityException e) { return BluetoothProfile.STATE_DISCONNECTED; }
    }

    /** True when Android took the request; the connection itself changes a moment later. */
    static boolean connect(BluetoothA2dp p, BluetoothDevice d, boolean connect) {
        if (p == null) return false;
        try {
            Object ok = BluetoothA2dp.class.getMethod(connect ? "connect" : "disconnect", BluetoothDevice.class).invoke(p, d);
            return Boolean.TRUE.equals(ok);
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Couldn't " + (connect ? "connect " : "disconnect ") + name(d) + ": " + e);
            return false;
        }
    }
}
