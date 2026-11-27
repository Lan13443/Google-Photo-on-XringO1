package com.wa2000.photohdr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;
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
    private static final String TARGET_PKG_WECHAT = "com.tencent.mm";
    private static final String MODULE_PKG = "com.wa2000.photohdr";
    private static final Uri STATUS_URI = Uri.parse("content://com.wa2000.photohdr.status");

    private static final int COLOR_MODE_DEFAULT = 0;
    private static final int COLOR_MODE_WIDE_COLOR_GAMUT = 1;

    public static volatile boolean sEnabled = true;
    public static volatile boolean sFixDolbyVision = true;
    public static volatile boolean sLowLatency = true;
    public static volatile boolean sLockRefreshRate = true;
    public static volatile int sTargetFps = 120;
    public static volatile boolean sVerbose = false;

    private static final AtomicBoolean sSurfaceHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sCodecHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sWindowHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sWeChatHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sReceiverRegistered = new AtomicBoolean(false);

    private static final Object sSurfaceLock = new Object();
    private static final WeakHashMap<Surface, WeakReference<SurfaceView>> sSurfaceToView = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String pkg = lpparam.packageName;
        boolean isPhotosOrGallery = TARGET_PKG_PHOTOS.equals(pkg) || TARGET_PKG_GALLERY.equals(pkg);
        boolean isWeChat = TARGET_PKG_WECHAT.equals(pkg);
        if (!isPhotosOrGallery && !isWeChat) {
            return;
        }

        XposedBridge.log(TAG + ": v2.4.2 Initializing in " + pkg + " (pid=" + android.os.Process.myPid() + ")");

        initSettings();
        registerSettingsReceiver();

        if (isWeChat) {
            hookWeChatWideColor(lpparam.classLoader);
            return;
        }

        hookSurfaceViewTracking(lpparam.classLoader);
        hookMediaCodec(lpparam.classLoader);
        hookWindowRefreshRate(lpparam.classLoader);
    }

    private static void recordSurfaceView(Object obj) {
        if (!(obj instanceof SurfaceView)) {
            return;
        }
        SurfaceView sv = (SurfaceView) obj;
        SurfaceHolder holder = sv.getHolder();
        if (holder == null) {
            return;
        }
        Surface s = holder.getSurface();
        if (s != null) {
            synchronized (sSurfaceLock) {
                sSurfaceToView.put(s, new WeakReference<>(sv));
            }
        }
    }

    private static void hookSurfaceViewTracking(ClassLoader classLoader) {
        if (!sSurfaceHooked.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> svClass = Class.forName("android.view.SurfaceView", false, classLoader);
            XC_MethodHook svHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        recordSurfaceView(param.thisObject);
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllConstructors(svClass, svHook);
            XposedBridge.hookAllMethods(svClass, "updateSurface", svHook);

            Class<?> viewClass = Class.forName("android.view.View", false, classLoader);
            XposedBridge.hookAllMethods(viewClass, "onSizeChanged", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof SurfaceView) {
                            recordSurfaceView(param.thisObject);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.log(TAG + ": Successfully hooked SurfaceView tracking");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook SurfaceView tracking: " + t.getMessage());
        }
    }

    private static boolean isFullscreenSurface(Object surfArg, String surfName, int[] outDims) {
        outDims[0] = 0;
        outDims[1] = 0;
        if (surfArg == null) {
            return true;
        }

        SurfaceView sv = null;
        if (surfArg instanceof Surface) {
            synchronized (sSurfaceLock) {
                WeakReference<SurfaceView> ref = sSurfaceToView.get((Surface) surfArg);
                if (ref != null) {
                    sv = ref.get();
                }
            }
        }

        if (sv != null) {
            int w = sv.getWidth();
            int h = sv.getHeight();
            outDims[0] = w;
            outDims[1] = h;
            if (w > 0 && h > 0) {
                DisplayMetrics dm = sv.getResources().getDisplayMetrics();
                int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
                long screenArea = (long) dm.widthPixels * (long) dm.heightPixels;
                long viewArea = (long) w * (long) h;
                return (w >= shortSide * 0.85f)
                        || (h >= shortSide * 0.85f && viewArea >= screenArea * 0.40f);
            }
            return true;
        }

        if (surfName != null && surfName.contains("ImageReader")) {
            return false;
        }
        return true;
    }

    private static void hookMediaCodec(ClassLoader classLoader) {
        if (sCodecHooked.compareAndSet(false, true)) {
            try {
                Class<?> mediaCodecClass = Class.forName("android.media.MediaCodec", false, classLoader);

                XposedBridge.hookAllMethods(mediaCodecClass, "configure", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!sEnabled || param.args == null || param.args.length != 4) {
                            return;
                        }

                        Object arg0 = param.args[0];
                        if (!(arg0 instanceof MediaFormat)) {
                            return;
                        }

                        MediaFormat format = (MediaFormat) arg0;
                        String mime = format.containsKey(MediaFormat.KEY_MIME)
                                ? format.getString(MediaFormat.KEY_MIME) : "";
                        boolean isDolbyVision = "video/dolby-vision".equalsIgnoreCase(mime);
                        boolean isHevc = "video/hevc".equalsIgnoreCase(mime);

                        Object surfArg = param.args[1];
                        String surfName = getSurfaceName(surfArg);
                        String surfClass = surfArg == null ? "null" : surfArg.getClass().getSimpleName();

                        if (sFixDolbyVision && (isHevc || isDolbyVision)) {
                            format.setString(MediaFormat.KEY_MIME, "video/hevc");
                            int[] dims = new int[2];
                            boolean isFullscreen = isFullscreenSurface(surfArg, surfName, dims);
                            XposedBridge.log(TAG + ": [CONFIGURE] mime=" + mime
                                    + " view=" + dims[0] + "x" + dims[1]
                                    + " cls=" + surfClass
                                    + " surf=" + surfName
                                    + " -> " + (isFullscreen ? "FULLSCREEN(HDR)" : "THUMBNAIL(SDR)"));

                            if (!isFullscreen) {
                                format.setInteger("color-standard", 1); // COLOR_STANDARD_BT709
                                format.setInteger("color-transfer", 3); // COLOR_TRANSFER_SDR_VIDEO
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

    private static void hookWeChatWideColor(ClassLoader classLoader) {
        if (!sWeChatHooked.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> windowClass = Class.forName("android.view.Window", false, classLoader);
            XposedBridge.hookAllMethods(windowClass, "setColorMode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled || param.args == null || param.args.length < 1) {
                            return;
                        }
                        if (param.args[0] instanceof Integer
                                && ((Integer) param.args[0]) == COLOR_MODE_WIDE_COLOR_GAMUT) {
                            param.args[0] = COLOR_MODE_DEFAULT;
                            XposedBridge.log(TAG + ": [WECHAT-COLOR] Window.setColorMode(1->0)");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedBridge.hookAllMethods(windowClass, "setAttributes", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled || param.args == null || param.args.length < 1) {
                            return;
                        }
                        if (param.args[0] instanceof WindowManager.LayoutParams) {
                            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) param.args[0];
                            if (lp.getColorMode() == COLOR_MODE_WIDE_COLOR_GAMUT) {
                                lp.setColorMode(COLOR_MODE_DEFAULT);
                                XposedBridge.log(TAG + ": [WECHAT-COLOR] Window.setAttributes colorMode(1->0)");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            Class<?> lpClass = Class.forName("android.view.WindowManager$LayoutParams", false, classLoader);
            XposedBridge.hookAllMethods(lpClass, "setColorMode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled || param.args == null || param.args.length < 1) {
                            return;
                        }
                        if (param.args[0] instanceof Integer
                                && ((Integer) param.args[0]) == COLOR_MODE_WIDE_COLOR_GAMUT) {
                            param.args[0] = COLOR_MODE_DEFAULT;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            Class<?> activityClass = Class.forName("android.app.Activity", false, classLoader);
            XC_MethodHook activityClampHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!sEnabled || !(param.thisObject instanceof android.app.Activity)) {
                            return;
                        }
                        android.app.Activity act = (android.app.Activity) param.thisObject;
                        Window w = act.getWindow();
                        if (w != null && w.getColorMode() == COLOR_MODE_WIDE_COLOR_GAMUT) {
                            w.setColorMode(COLOR_MODE_DEFAULT);
                            XposedBridge.log(TAG + ": [WECHAT-COLOR] Activity clamped wide color mode -> DEFAULT ("
                                    + act.getClass().getName() + ")");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedBridge.hookAllMethods(activityClass, "onCreate", activityClampHook);
            XposedBridge.hookAllMethods(activityClass, "onResume", activityClampHook);

            XposedBridge.log(TAG + ": Successfully hooked WeChat wide color clamp");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook WeChat wide color: " + t.getMessage());
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
            ByteBuffer orig = format.getByteBuffer("csd-0");
            if (orig == null) {
                return;
            }

            // Guard 1: duplicate before reading so we never mutate caller's ByteBuffer in place
            ByteBuffer dup = orig.duplicate();
            int len = dup.remaining();
            if (len < 16) {
                return;
            }
            byte[] bytes = new byte[len];
            dup.get(bytes);

            if (tryPatchHevcSpsVui(format, bytes)) {
                return;
            }

            // Fallback to verified legacy offset signature
            if (len == 93 && bytes[64] == (byte) 0x42 && bytes[65] == (byte) 0x44 && bytes[66] == (byte) 0x82) {
                byte[] patched = new byte[len];
                System.arraycopy(bytes, 0, patched, 0, len);
                patched[64] = (byte) 0x40;
                patched[65] = (byte) 0x40;
                patched[66] = (byte) 0x40;

                ByteBuffer patchedBuf = ByteBuffer.allocateDirect(len);
                patchedBuf.put(patched);
                patchedBuf.flip();
                format.setByteBuffer("csd-0", patchedBuf);
                XposedBridge.log(TAG + ": [CSD0-PATCH] Legacy 93B VUI -> BT.709");
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean tryPatchHevcSpsVui(MediaFormat format, byte[] csd) {
        int len = csd.length;
        int pos = 0;
        while (pos < len) {
            int startCodeLen = 0;
            int nalStart = -1;
            for (int i = pos; i + 3 < len; i++) {
                if (csd[i] == 0 && csd[i + 1] == 0 && csd[i + 2] == 1) {
                    startCodeLen = 3;
                    nalStart = i + 3;
                    break;
                }
                if (i + 4 < len && csd[i] == 0 && csd[i + 1] == 0 && csd[i + 2] == 0 && csd[i + 3] == 1) {
                    startCodeLen = 4;
                    nalStart = i + 4;
                    break;
                }
            }
            if (nalStart < 0 || nalStart + 2 >= len) {
                break;
            }

            int nalEnd = len;
            for (int i = nalStart; i + 2 < len; i++) {
                if (csd[i] == 0 && csd[i + 1] == 0 && (csd[i + 2] == 1 || (i + 3 < len && csd[i + 2] == 0 && csd[i + 3] == 1))) {
                    nalEnd = i;
                    break;
                }
            }

            int nalType = (csd[nalStart] >> 1) & 0x3F;
            if (nalType == 33) { // HEVC SPS_NUT
                byte[] rbsp = stripEmulationPrevention(csd, nalStart, nalEnd);
                int colourBitPos = findHevcSpsColourDescBitPos(rbsp);
                if (colourBitPos > 0 && (colourBitPos + 24) <= rbsp.length * 8) {
                    int cp = readBitsAt(rbsp, colourBitPos, 8);
                    int tc = readBitsAt(rbsp, colourBitPos + 8, 8);
                    int mc = readBitsAt(rbsp, colourBitPos + 16, 8);
                    if (cp == 9 || tc == 18 || tc == 16 || mc == 9) {
                        writeBitsAt(rbsp, colourBitPos, 8, 1);      // colour_primaries = BT.709
                        writeBitsAt(rbsp, colourBitPos + 8, 8, 1);  // transfer_characteristics = BT.709
                        writeBitsAt(rbsp, colourBitPos + 16, 8, 1); // matrix_coeffs = BT.709
                        byte[] escapedNal = insertEmulationPrevention(rbsp);
                        int newLen = nalStart + escapedNal.length + (len - nalEnd);
                        ByteBuffer out = ByteBuffer.allocateDirect(newLen);
                        out.put(csd, 0, nalStart);
                        out.put(escapedNal);
                        if (nalEnd < len) {
                            out.put(csd, nalEnd, len - nalEnd);
                        }
                        out.flip();
                        format.setByteBuffer("csd-0", out);
                        XposedBridge.log(TAG + ": [CSD0-PATCH] SPS VUI (" + cp + "," + tc + "," + mc
                                + ") -> (1,1,1) len=" + len + "->" + newLen);
                        return true;
                    }
                    return false;
                }
            }
            pos = nalEnd;
        }
        return false;
    }

    private static byte[] stripEmulationPrevention(byte[] data, int start, int end) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(end - start);
        int zeros = 0;
        for (int i = start; i < end; i++) {
            int b = data[i] & 0xFF;
            if (zeros == 2 && b == 3 && i + 1 < end && (data[i + 1] & 0xFF) <= 3) {
                zeros = 0;
                continue;
            }
            out.write(b);
            zeros = (b == 0) ? (zeros + 1) : 0;
        }
        return out.toByteArray();
    }

    private static byte[] insertEmulationPrevention(byte[] rbsp) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rbsp.length + 4);
        int zeros = 0;
        for (byte value : rbsp) {
            int b = value & 0xFF;
            if (zeros == 2 && b <= 3) {
                out.write(3);
                zeros = 0;
            }
            out.write(b);
            zeros = (b == 0) ? (zeros + 1) : 0;
        }
        return out.toByteArray();
    }

    private static int findHevcSpsColourDescBitPos(byte[] rbsp) {
        if (rbsp.length < 16) {
            return -1;
        }
        BitReader br = new BitReader(rbsp);
        try {
            br.skipBits(16); // nal_unit_header
            br.skipBits(4);  // sps_video_parameter_set_id
            int maxSubLayersMinus1 = br.readBits(3);
            br.skipBits(1);  // sps_temporal_id_nesting_flag

            // profile_tier_level(1, maxSubLayersMinus1)
            br.skipBits(96); // general profile/tier/compatibility/constraints (88) + general_level_idc (8)
            boolean[] subProfile = new boolean[maxSubLayersMinus1];
            boolean[] subLevel = new boolean[maxSubLayersMinus1];
            for (int i = 0; i < maxSubLayersMinus1; i++) {
                subProfile[i] = br.readBit() == 1;
                subLevel[i] = br.readBit() == 1;
            }
            if (maxSubLayersMinus1 > 0) {
                br.skipBits(2 * (8 - maxSubLayersMinus1));
            }
            for (int i = 0; i < maxSubLayersMinus1; i++) {
                if (subProfile[i]) {
                    br.skipBits(88);
                }
                if (subLevel[i]) {
                    br.skipBits(8);
                }
            }

            br.readUe(); // sps_seq_parameter_set_id
            int chromaFormatIdc = br.readUe();
            if (chromaFormatIdc == 3) {
                br.skipBits(1); // separate_colour_plane_flag
            }
            br.readUe(); // pic_width_in_luma_samples
            br.readUe(); // pic_height_in_luma_samples
            if (br.readBit() == 1) { // conformance_window_flag
                br.readUe();
                br.readUe();
                br.readUe();
                br.readUe();
            }
            br.readUe(); // bit_depth_luma_minus8
            br.readUe(); // bit_depth_chroma_minus8
            int maxPocLsbBits = br.readUe() + 4; // log2_max_pic_order_cnt_lsb_minus4 + 4
            int subLayerOrderingFlag = br.readBit();
            int startIdx = (subLayerOrderingFlag == 1) ? 0 : maxSubLayersMinus1;
            for (int i = startIdx; i <= maxSubLayersMinus1; i++) {
                br.readUe();
                br.readUe();
                br.readUe();
            }
            br.readUe(); // log2_min_luma_coding_block_size_minus3
            br.readUe(); // log2_diff_max_min_luma_coding_block_size
            br.readUe(); // log2_min_luma_transform_block_size_minus2
            br.readUe(); // log2_diff_max_min_luma_transform_block_size
            br.readUe(); // max_transform_hierarchy_depth_inter
            br.readUe(); // max_transform_hierarchy_depth_intra
            if (br.readBit() == 1) { // scaling_list_enabled_flag
                if (br.readBit() == 1) { // sps_scaling_list_data_present_flag
                    return -1; // Bail out cleanly rather than mis-parsing custom scaling lists
                }
            }
            br.skipBits(2); // amp_enabled_flag + sample_adaptive_offset_enabled_flag
            if (br.readBit() == 1) { // pcm_enabled_flag
                br.skipBits(8);
                br.readUe();
                br.readUe();
                br.skipBits(1);
            }
            int numShortTermRefPicSets = br.readUe();
            if (numShortTermRefPicSets < 0 || numShortTermRefPicSets > 64) {
                return -1;
            }
            int[] numDeltaPocs = new int[numShortTermRefPicSets + 1];
            for (int stRpsIdx = 0; stRpsIdx < numShortTermRefPicSets; stRpsIdx++) {
                int predFlag = (stRpsIdx != 0) ? br.readBit() : 0;
                if (predFlag == 1) {
                    br.skipBits(1); // delta_rps_sign
                    br.readUe();    // abs_delta_rps_minus1
                    int refNum = numDeltaPocs[stRpsIdx - 1];
                    int count = 0;
                    for (int j = 0; j <= refNum; j++) {
                        int used = br.readBit();
                        int useDelta = (used == 0) ? br.readBit() : 1;
                        if (used == 1 || useDelta == 1) {
                            count++;
                        }
                    }
                    numDeltaPocs[stRpsIdx] = count;
                } else {
                    int numNeg = br.readUe();
                    int numPos = br.readUe();
                    if (numNeg < 0 || numPos < 0 || (numNeg + numPos) > 64) {
                        return -1;
                    }
                    numDeltaPocs[stRpsIdx] = numNeg + numPos;
                    for (int i = 0; i < numNeg; i++) {
                        br.readUe();
                        br.skipBits(1);
                    }
                    for (int i = 0; i < numPos; i++) {
                        br.readUe();
                        br.skipBits(1);
                    }
                }
            }
            if (br.readBit() == 1) { // long_term_ref_pics_present_flag
                int numLt = br.readUe();
                if (numLt < 0 || numLt > 64) {
                    return -1;
                }
                for (int i = 0; i < numLt; i++) {
                    br.skipBits(maxPocLsbBits + 1);
                }
            }
            br.skipBits(2); // sps_temporal_mvp_enabled_flag + strong_intra_smoothing_enabled_flag
            if (br.readBit() != 1) { // vui_parameters_present_flag
                return -1;
            }
            if (br.readBit() == 1) { // aspect_ratio_info_present_flag
                int arIdc = br.readBits(8);
                if (arIdc == 255) {
                    br.skipBits(32);
                }
            }
            if (br.readBit() == 1) { // overscan_info_present_flag
                br.skipBits(1);
            }
            if (br.readBit() != 1) { // video_signal_type_present_flag
                return -1;
            }
            br.skipBits(4); // video_format(3) + video_full_range_flag(1)
            if (br.readBit() != 1) { // colour_description_present_flag
                return -1;
            }
            return br.pos;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int readBitsAt(byte[] buf, int bitPos, int count) {
        int val = 0;
        for (int i = 0; i < count; i++) {
            int p = bitPos + i;
            int bit = (buf[p >> 3] >> (7 - (p & 7))) & 1;
            val = (val << 1) | bit;
        }
        return val;
    }

    private static void writeBitsAt(byte[] buf, int bitPos, int count, int value) {
        for (int i = 0; i < count; i++) {
            int p = bitPos + i;
            int shift = 7 - (p & 7);
            int bit = (value >> (count - 1 - i)) & 1;
            if (bit == 1) {
                buf[p >> 3] = (byte) (buf[p >> 3] | (1 << shift));
            } else {
                buf[p >> 3] = (byte) (buf[p >> 3] & ~(1 << shift));
            }
        }
    }

    private static final class BitReader {
        private final byte[] data;
        private final int totalBits;
        int pos;

        BitReader(byte[] data) {
            this.data = data;
            this.totalBits = data.length * 8;
        }

        void skipBits(int n) {
            pos += n;
            if (pos > totalBits) {
                throw new IndexOutOfBoundsException("EOF");
            }
        }

        int readBit() {
            if (pos >= totalBits) {
                throw new IndexOutOfBoundsException("EOF");
            }
            int bit = (data[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return bit;
        }

        int readBits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | readBit();
            }
            return v;
        }

        int readUe() {
            int leadingZeros = 0;
            while (readBit() == 0) {
                leadingZeros++;
                if (leadingZeros > 30) {
                    throw new IllegalStateException("ue overflow");
                }
            }
            if (leadingZeros == 0) {
                return 0;
            }
            return ((1 << leadingZeros) - 1) + readBits(leadingZeros);
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
