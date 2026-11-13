package com.wa2000.photohdr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "PhotoHDRFix";
    private static final String TARGET_PKG_PHOTOS = "com.google.android.apps.photos";
    private static final String TARGET_PKG_GALLERY = "com.miui.gallery";
    private static final String MODULE_PKG = "com.wa2000.photohdr";
    private static final Uri STATUS_URI = Uri.parse("content://com.wa2000.photohdr.status");

    public static volatile boolean sEnabled = true;
    public static volatile boolean sFixDolbyVision = true;
    public static volatile boolean sLowLatency = true;
    public static volatile boolean sLockRefreshRate = true;
    public static volatile int sTargetFps = 120;
    public static volatile boolean sVerbose = false;

    private static final AtomicBoolean sCodecHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sWindowHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sReceiverRegistered = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        if (!TARGET_PKG_PHOTOS.equals(pkg) && !TARGET_PKG_GALLERY.equals(pkg)) {
            return;
        }

        XposedBridge.log(TAG + ": v2.3.0 Initializing in " + pkg + " (pid=" + android.os.Process.myPid() + ")");

        initSettings();
        registerSettingsReceiver();
        hookMediaCodec(lpparam.classLoader);
        hookWindowRefreshRate(lpparam.classLoader);
    }

    private static void hookMediaCodec(ClassLoader classLoader) {
        if (sCodecHooked.compareAndSet(false, true)) {
            try {
                Class<?> mediaCodecClass = Class.forName("android.media.MediaCodec", false, classLoader);

                XposedBridge.hookAllMethods(mediaCodecClass, "configure", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sEnabled || param.args == null || param.args.length < 2) {
                            return;
                        }

                        Object arg0 = param.args[0];
                        if (!(arg0 instanceof MediaFormat)) {
                            return;
                        }

                        MediaFormat format = (MediaFormat) arg0;

                        String mime = format.containsKey(MediaFormat.KEY_MIME) ? format.getString(MediaFormat.KEY_MIME) : "";
                        boolean isDolbyVision = "video/dolby-vision".equalsIgnoreCase(mime);
                        boolean isHevc = "video/hevc".equalsIgnoreCase(mime);

                        Object surfArg = param.args.length > 1 ? param.args[1] : null;
                        String surfName = getSurfaceName(surfArg);

                        if (sFixDolbyVision && (isHevc || isDolbyVision)) {
                            boolean isFullscreen = (surfName != null && surfName.contains("SurfaceTexture"))
                                    || (surfArg instanceof android.view.Surface
                                    && surfArg.getClass() != android.view.Surface.class);

                            format.setString(MediaFormat.KEY_MIME, "video/hevc");

                            if (isFullscreen) {
                                // 全屏：保留真 HDR，缺失才注入 BT.2020 + HLG，绝不碰 csd-0
                                if (!format.containsKey("color-standard")) {
                                    format.setInteger("color-standard", 6); // COLOR_STANDARD_BT2020
                                }
                                if (!format.containsKey("color-transfer")) {
                                    format.setInteger("color-transfer", 7); // COLOR_TRANSFER_HLG
                                }
                            } else {
                                // 缩略图/预览（含 surf=null 的 dolby 与 hevc、HomeActivity SurfaceView）：
                                // 压 SDR + 覆写 SPS VUI，双保险兜住玄戒解码器无视 configure 的坑
                                format.setInteger("color-standard", 1); // COLOR_STANDARD_BT709
                                format.setInteger("color-transfer", 3);  // COLOR_TRANSFER_SDR_VIDEO
                                patchCsd0ToSdr(format);
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(mediaCodecClass, "createByCodecName", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sEnabled || !sFixDolbyVision) {
                            return;
                        }
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                            String name = (String) param.args[0];
                            if ("c2.xring.dolby.decoder".equalsIgnoreCase(name)) {
                                param.args[0] = "c2.xring.hevc.decoder";
                                XposedBridge.log(TAG + ": [CODEC REDIRECT] c2.xring.dolby.decoder -> c2.xring.hevc.decoder");
                            }
                        }
                    }
                });

                XposedBridge.hookAllMethods(mediaCodecClass, "createDecoderByType", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sEnabled || !sFixDolbyVision) {
                            return;
                        }
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                            String type = (String) param.args[0];
                            if ("video/dolby-vision".equalsIgnoreCase(type)) {
                                param.args[0] = "video/hevc";
                                XposedBridge.log(TAG + ": [TYPE REDIRECT] video/dolby-vision -> video/hevc");
                            }
                        }
                    }
                });

                XposedBridge.log(TAG + ": Successfully hooked MediaCodec.configure + createByCodecName + createDecoderByType");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to hook MediaCodec: " + t.getMessage());
            }
        }
    }

    private static String getSurfaceName(Object surf) {
        if (surf == null) {
            return null;
        }
        try {
            java.lang.reflect.Method m = android.view.Surface.class.getDeclaredMethod("getName");
            m.setAccessible(true);
            Object name = m.invoke(surf);
            return name == null ? null : String.valueOf(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void patchCsd0ToSdr(MediaFormat format) {
        try {
            if (!format.containsKey("csd-0")) {
                return;
            }
            java.nio.ByteBuffer orig = format.getByteBuffer("csd-0");
            if (orig == null) {
                return;
            }

            // 护栏1：先 duplicate 读出，绝不原地修改原 ByteBuffer
            java.nio.ByteBuffer dup = orig.duplicate();
            int len = dup.remaining();
            byte[] bytes = new byte[len];
            dup.get(bytes);

            // 护栏2：特征校验，只有精确命中才覆写，防误伤其他分辨率视频
            if (len != 93 || bytes[64] != (byte) 0x42 || bytes[65] != (byte) 0x44 || bytes[66] != (byte) 0x82) {
                return;
            }

            // 副本上改，新建 direct ByteBuffer 写回，原对象零污染
            byte[] patched = new byte[len];
            System.arraycopy(bytes, 0, patched, 0, len);
            patched[64] = (byte) 0x40; // colour_primaries: 9(BT.2020) -> 1(BT.709)
            patched[65] = (byte) 0x40; // transfer_characteristics: 18(HLG) -> 1(BT.709)
            patched[66] = (byte) 0x40; // matrix_coeffs: 9(BT.2020 NC) -> 1(BT.709)

            java.nio.ByteBuffer patchedBuf = java.nio.ByteBuffer.allocateDirect(len);
            patchedBuf.put(patched);
            patchedBuf.flip();
            format.setByteBuffer("csd-0", patchedBuf);
        } catch (Throwable ignored) {
        }
    }

    private static void hookWindowRefreshRate(ClassLoader classLoader) {
        if (sWindowHooked.compareAndSet(false, true)) {
            try {
                Class<?> activityClass = Class.forName("android.app.Activity", false, classLoader);

                XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sEnabled || !sLockRefreshRate) {
                            return;
                        }

                        Object activity = param.thisObject;
                        if (activity instanceof android.app.Activity) {
                            android.app.Activity act = (android.app.Activity) activity;
                            Window window = act.getWindow();
                            if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                WindowManager.LayoutParams lp = window.getAttributes();
                                Display display = act.getWindowManager().getDefaultDisplay();
                                Display.Mode[] modes = display.getSupportedModes();
                                int bestModeId = -1;
                                float target = sTargetFps;

                                for (Display.Mode mode : modes) {
                                    if (Math.abs(mode.getRefreshRate() - target) < 1.0f) {
                                        bestModeId = mode.getModeId();
                                        break;
                                    }
                                }

                                if (bestModeId != -1 && lp.preferredDisplayModeId != bestModeId) {
                                    lp.preferredDisplayModeId = bestModeId;
                                    window.setAttributes(lp);
                                    if (sVerbose) {
                                        XposedBridge.log(TAG + ": Locked display refresh rate to " + target + "Hz (mode=" + bestModeId + ")");
                                    }
                                }
                            }
                        }
                    }
                });

                XposedBridge.log(TAG + ": Successfully hooked Activity.onResume for refresh rate lock");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to hook Activity.onResume: " + t.getMessage());
            }
        }
    }

    private static void initSettings() {
        Context ctx = getSystemContext();
        if (ctx != null) {
            try {
                Bundle bundle = ctx.getContentResolver().call(STATUS_URI, "get_settings", null, null);
                if (bundle != null) {
                    sEnabled = bundle.getBoolean("en", true);
                    String fps = bundle.getString("fps", "120");
                    sTargetFps = "60".equals(fps) ? 60 : 120;
                    sFixDolbyVision = bundle.getBoolean("fix_dv", true);
                    sLowLatency = bundle.getBoolean("low_lat", true);
                    sLockRefreshRate = bundle.getBoolean("lock_fps", true);
                    sVerbose = bundle.getBoolean("vb", false);

                    XposedBridge.log(TAG + ": Settings loaded via ContentProvider: en=" + sEnabled
                            + ", fix_dv=" + sFixDolbyVision + ", fps=" + sTargetFps);
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": ContentProvider call failed, falling back to XSharedPreferences: " + t.getMessage());
            }
        }

        try {
            XSharedPreferences xsp = new XSharedPreferences(MODULE_PKG, "photohdr_settings");
            xsp.reload();
            sEnabled = xsp.getBoolean("enable_fix", true);
            String fps = xsp.getString("fps_mode", "120");
            sTargetFps = "60".equals(fps) ? 60 : 120;
            sFixDolbyVision = xsp.getBoolean("fix_dv", true);
            sLowLatency = xsp.getBoolean("low_lat", true);
            sLockRefreshRate = xsp.getBoolean("lock_fps", true);
            sVerbose = xsp.getBoolean("verbose_log", false);
            XposedBridge.log(TAG + ": Settings loaded via XSP: en=" + sEnabled + ", fix_dv=" + sFixDolbyVision);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Using default settings: en=" + sEnabled + ", fix_dv=" + sFixDolbyVision);
        }
    }

    private static void registerSettingsReceiver() {
        if (sReceiverRegistered.compareAndSet(false, true)) {
            try {
                Context ctx = getSystemContext();
                if (ctx == null) return;

                IntentFilter filter = new IntentFilter("com.wa2000.photohdr.SETTINGS_CHANGED");
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        sEnabled = intent.getBooleanExtra("en", sEnabled);
                        sFixDolbyVision = intent.getBooleanExtra("fix_dv", sFixDolbyVision);
                        sLowLatency = intent.getBooleanExtra("low_lat", sLowLatency);
                        sLockRefreshRate = intent.getBooleanExtra("lock_fps", sLockRefreshRate);
                        String fps = intent.getStringExtra("fps");
                        if (fps != null) {
                            sTargetFps = "60".equals(fps) ? 60 : 120;
                        }
                        sVerbose = intent.getBooleanExtra("vb", sVerbose);
                        XposedBridge.log(TAG + ": Settings updated via broadcast: en=" + sEnabled
                                + ", fix_dv=" + sFixDolbyVision + ", fps=" + sTargetFps);
                    }
                };

                ctx.registerReceiver(receiver, filter);
                XposedBridge.log(TAG + ": Settings broadcast receiver successfully registered");
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to register settings receiver: " + t.getMessage());
            }
        }
    }

    private static Context getSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object currentActivityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            return (Context) activityThreadClass.getMethod("getApplication").invoke(currentActivityThread);
        } catch (Throwable ignored) {
            return null;
        }
    }
}