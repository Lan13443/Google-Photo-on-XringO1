package com.wa2000.photohdr;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

public class ConfigStore {
    private static final String TAG = "PhotoHDRFix";
    private static final String FILE_NAME = "photohdr_settings.txt";

    public static boolean en = true;
    public static boolean fixDv = true;
    public static boolean lowLat = true;
    public static boolean lockFps = true;
    public static String fps = "120";
    public static boolean vb = false;
    public static String theme = "ocean";
    public static String status = "";
    public static long statusTime = 0;

    private static File sFile;

    public static synchronized void load(Context context) {
        if (context == null) return;
        try {
            if (sFile == null) {
                sFile = new File(context.getFilesDir(), FILE_NAME);
            }
            if (!sFile.exists() || sFile.length() <= 0) {
                return;
            }

            Properties prop = new Properties();
            try (FileInputStream fis = new FileInputStream(sFile)) {
                prop.load(fis);
            }

            en = Boolean.parseBoolean(prop.getProperty("en", "true"));
            fixDv = Boolean.parseBoolean(prop.getProperty("fix_dv", "true"));
            lowLat = Boolean.parseBoolean(prop.getProperty("low_lat", "true"));
            lockFps = Boolean.parseBoolean(prop.getProperty("lock_fps", "true"));
            fps = prop.getProperty("fps", "120");
            vb = Boolean.parseBoolean(prop.getProperty("vb", "false"));
            theme = prop.getProperty("theme", "ocean");
            status = prop.getProperty("st", "");
            statusTime = Long.parseLong(prop.getProperty("stt", "0"));

            Log.i(TAG, "Config loaded: en=" + en + ", fix_dv=" + fixDv + ", fps=" + fps);
        } catch (Throwable t) {
            Log.w(TAG, "Config load failed: " + t.getMessage());
        }
    }

    public static synchronized void save(Context context) {
        if (context == null) return;
        try {
            if (sFile == null) {
                sFile = new File(context.getFilesDir(), FILE_NAME);
            }

            Properties prop = new Properties();
            prop.setProperty("en", String.valueOf(en));
            prop.setProperty("fix_dv", String.valueOf(fixDv));
            prop.setProperty("low_lat", String.valueOf(lowLat));
            prop.setProperty("lock_fps", String.valueOf(lockFps));
            prop.setProperty("fps", fps);
            prop.setProperty("vb", String.valueOf(vb));
            prop.setProperty("theme", theme != null ? theme : "ocean");
            if (status != null) {
                prop.setProperty("st", status);
            }
            prop.setProperty("stt", String.valueOf(statusTime));

            try (FileOutputStream fos = new FileOutputStream(sFile)) {
                prop.store(fos, "PhotoHDRFix Settings");
            }
            Log.i(TAG, "Config saved to " + sFile.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "Config save failed: " + t.getMessage());
        }
    }
}