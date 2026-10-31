package com.wa2000.photohdr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

/**
 * File-based config store. Deliberately avoids SharedPreferences because on
 * HyperOS + LSPosed the SharedPreferences path resolution is hooked/redirected
 * and silently returns defaults. Plain file in filesDir is not affected.
 */
public class ConfigStore {
    private static final String TAG = "PhotoHDRFix";

    public static boolean en = true;
    public static String fps = "60";
    public static boolean vb = false;
    public static String status = null;
    public static long statusTime = 0;
    public static String theme = "monet"; // monet | baseline | blue | green | rose

    private static File file;

    public static synchronized void load(Context ctx) {
        try {
            file = new File(ctx.getFilesDir(), "photohdr_settings.txt");
            if (file.exists() && file.length() > 0) {
                Properties p = new Properties();
                FileInputStream in = new FileInputStream(file);
                p.load(in);
                in.close();
                en = "true".equals(p.getProperty("en", "true"));
                fps = p.getProperty("fps", "60");
                vb = "true".equals(p.getProperty("vb", "false"));
                theme = p.getProperty("theme", "monet");
                status = p.getProperty("st", null);
                String stt = p.getProperty("stt", "0");
                try { statusTime = Long.parseLong(stt); } catch (Throwable ignored) { }
            }
            Log.i(TAG, "cfg loaded from " + file + " en=" + en + " fps=" + fps + " vb=" + vb);
        } catch (Throwable t) {
            Log.w(TAG, "cfg load FAIL " + t);
        }
    }

    public static synchronized void save(Context ctx) {
        try {
            if (file == null) file = new File(ctx.getFilesDir(), "photohdr_settings.txt");
            Properties p = new Properties();
            p.setProperty("en", String.valueOf(en));
            p.setProperty("fps", fps);
            p.setProperty("vb", String.valueOf(vb));
            p.setProperty("theme", theme == null ? "monet" : theme);
            if (status != null) {
                p.setProperty("st", status);
                p.setProperty("stt", String.valueOf(statusTime));
            }
            FileOutputStream out = new FileOutputStream(file);
            p.store(out, "PhotoHDRFix");
            out.close();
            Log.i(TAG, "cfg saved to " + file);
        } catch (Throwable t) {
            Log.w(TAG, "cfg save FAIL " + t);
        }
    }
}