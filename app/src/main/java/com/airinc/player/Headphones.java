package com.airinc.player;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import org.json.JSONObject;
import java.lang.reflect.Method;

/** Detects wired / Bluetooth headphones and reads their battery when the device reports it. */
public class Headphones {
    static int call(BluetoothDevice d, String name) {
        try { Method m = BluetoothDevice.class.getMethod(name); Object r = m.invoke(d); return r instanceof Integer ? (Integer) r : -1; }
        catch (Throwable t) { return -1; }
    }

    /** Untethered earbud metadata: 10 = left, 11 = right, 12 = case battery (percent as text). */
    static int meta(BluetoothDevice d, int key) {
        try {
            Method m = BluetoothDevice.class.getMethod("getMetadata", int.class);
            byte[] b = (byte[]) m.invoke(d, key);
            if (b == null) return -1;
            int v = Integer.parseInt(new String(b).trim());
            return v >= 0 && v <= 100 ? v : -1;
        } catch (Throwable t) { return -1; }
    }

    public static boolean btPerm(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    public static String info(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences sp = c.getSharedPreferences("air", 0);
            boolean perm = btPerm(c);
            o.put("connected", false).put("perm", perm).put("battery", -1).put("l", -1).put("r", -1).put("case", -1);
            o.put("apoff", sp.getBoolean("ap_off", true)).put("apon", sp.getBoolean("ap_on", false)).put("limit", sp.getFloat("vlimit", 1f));
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo best = null; boolean bestBt = false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                boolean bt = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET);
                boolean wired = t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET || t == AudioDeviceInfo.TYPE_USB_HEADSET;
                if ((bt || wired) && (best == null || bt)) { best = d; bestBt = bt; }
            }
            if (best != null) {
                CharSequence nm = best.getProductName();
                o.put("connected", true).put("type", bestBt ? "Bluetooth" : best.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ? "USB" : "Wired")
                    .put("name", nm == null || nm.length() == 0 ? "Headphones" : nm.toString());
                int bat = -1, l = -1, r = -1, cs = -1;
                if (bestBt && perm && Build.VERSION.SDK_INT >= 28) {
                    String addr = best.getAddress();
                    BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
                    if (addr != null && addr.length() > 0 && ad != null) {
                        BluetoothDevice dev = ad.getRemoteDevice(addr);
                        bat = call(dev, "getBatteryLevel"); l = meta(dev, 10); r = meta(dev, 11); cs = meta(dev, 12);
                        if (bat < 0) bat = sp.getInt("hp_bat_" + addr, -1);
                    }
                }
                if (bat < 0 && l >= 0 && r >= 0) bat = (l + r) / 2; else if (bat < 0 && l >= 0) bat = l; else if (bat < 0 && r >= 0) bat = r;
                o.put("battery", bat).put("l", l).put("r", r).put("case", cs);
            }
        } catch (Throwable e) { }
        return o.toString();
    }
}
