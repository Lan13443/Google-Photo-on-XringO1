package com.wa2000.photohdr;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Field;
import android.util.Log;

public class Main implements IXposedHookLoadPackage {
    private static final String PREF = "photohdr_settings";

    // runtime settings (hot-reloaded at each codec configure)
    private static boolean sEnabled = true;
    private static long sFrameNs = 16666667L;
    private static boolean sVerbose = false;

    private static int relCount = 0;
    private static int confCount = 0;
    private static long relPrev = -1;
    private static int renderTrue = 0;
    private static int renderFalse = 0;
    private static int renderTs = 0;
    private static boolean jfoHooked = false;
    private static XC_LoadPackage.LoadPackageParam gLp;
    private static long lastRel = -1;
    private static int fixCount = 0;
    private static int fixErr = 0;
    private static int bhCount = 0;
    private static int bfCount = 0;
    private static int keepDrop = 0;

    private static Context gCtx = null;

    private static Context ctx() {
        if (gCtx == null) {
            try {
                Object at = Class.forName("android.app.ActivityThread")
                        .getMethod("currentActivityThread").invoke(null);
                Object app = at.getClass().getMethod("getApplication").invoke(at);
                gCtx = (Context) app;
            } catch (Throwable ignored) {
            }
        }
        return gCtx;
    }

    private static void loadSettings() {
        boolean got = tryMirrorSettings();
        if (!got) got = tryXspSettings();
        if (!got) {
            XposedBridge.log("PhotoHDRFix: settings DEFAULT en=" + sEnabled
                    + " fps=" + (sFrameNs == 33333333L ? "30" : "60") + " vb=" + sVerbose);
        }
    }

    private static void writeMirrorFile() {
        try {
            Context c = ctx();
            if (c == null) {
                XposedBridge.log("PhotoHDRFix: mirror write skip: no ctx");
                return;
            }
            File f = new File(c.getFilesDir(), "photohdr_settings.txt");
            FileWriter w = new FileWriter(f);
            w.write("enable_fix=" + (sEnabled ? "1" : "0")
                    + "\nfps_mode=" + (sFrameNs == 33333333L ? "30" : "60")
                    + "\nverbose_log=" + (sVerbose ? "1" : "0") + "\n");
            w.close();
            XposedBridge.log("PhotoHDRFix: mirror written to " + f.getAbsolutePath());
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: mirror write FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean sReceiverRegistered = false;

    private static void registerSafe(Context c, BroadcastReceiver r, IntentFilter f) {
        try {
            java.lang.reflect.Method m = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, int.class);
            m.invoke(c, r, f, 0x2); // RECEIVER_EXPORTED
        } catch (Throwable t) {
            try {
                c.registerReceiver(r, f);
            } catch (Throwable t2) {
                XposedBridge.log("PhotoHDRFix: register FAIL " + t2);
            }
        }
    }

    private static void ensureReceiver() {
        if (sReceiverRegistered) return;
        try {
            final Context c = ctx();
            if (c == null) return;
            IntentFilter f = new IntentFilter("com.wa2000.photohdr.SETTINGS_CHANGED");
            registerSafe(c, new BroadcastReceiver() {
                @Override
                public void onReceive(Context rc, Intent i) {
                    try {
                        sEnabled = i.getBooleanExtra("en", sEnabled);
                        String fps = i.getStringExtra("fps");
                        if ("30".equals(fps)) sFrameNs = 33333333L;
                        else if ("60".equals(fps)) sFrameNs = 16666667L;
                        sVerbose = i.getBooleanExtra("vb", sVerbose);
                        writeMirrorFile();
                        XposedBridge.log("PhotoHDRFix: broadcast settings en=" + sEnabled
                                + " fps=" + fps + " vb=" + sVerbose);
                        Intent r = new Intent("com.wa2000.photohdr.STATUS");
                        r.setPackage("com.wa2000.photohdr");
                        r.putExtra("st", "pid=" + android.os.Process.myPid()
                                + " enabled=" + sEnabled
                                + " fps=" + (sFrameNs == 33333333L ? "30" : "60"));
                        rc.sendBroadcast(r);
                    } catch (Throwable t) {
                        XposedBridge.log("PhotoHDRFix: bcst FAIL " + t);
                    }
                }
            }, f);
            sReceiverRegistered = true;
            XposedBridge.log("PhotoHDRFix: receiver registered");
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: receiver FAIL " + t);
        }
    }

    private static boolean tryMirrorSettings() {
        try {
            Context c = ctx();
            if (c == null) return false;
            File f = new File(c.getFilesDir(), "photohdr_settings.txt");
            if (!f.exists()) {
                XposedBridge.log("PhotoHDRFix: mirror file not found: " + f.getAbsolutePath());
                return false;
            }
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("enable_fix=")) {
                    sEnabled = "1".equals(line.substring("enable_fix=".length()));
                } else if (line.startsWith("fps_mode=")) {
                    String v = line.substring("fps_mode=".length());
                    if ("30".equals(v)) sFrameNs = 33333333L;
                } else if (line.startsWith("verbose_log=")) {
                    sVerbose = "1".equals(line.substring("verbose_log=".length()));
                }
            }
            br.close();
            XposedBridge.log("PhotoHDRFix: settings via mirror en=" + sEnabled
                    + " fps=" + (sFrameNs == 33333333L ? "30" : "60") + " vb=" + sVerbose);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: mirror FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    private static boolean tryXspSettings() {
        try {
            XSharedPreferences xsp = new XSharedPreferences("com.wa2000.photohdr", PREF);
            xsp.reload();
            sEnabled = xsp.getBoolean("enable_fix", true);
            String fps = xsp.getString("fps_mode", "60");
            sFrameNs = "30".equals(fps) ? 33333333L : 16666667L;
            sVerbose = xsp.getBoolean("verbose_log", false);
            XposedBridge.log("PhotoHDRFix: settings via xsp en=" + sEnabled + " fps=" + fps + " vb=" + sVerbose);
            return true;
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: xsp FAIL " + t.getClass().getName() + ": " + t.getMessage());
            return false;
        }
    }

    private static void notifyStatus() {
        String st = "pid=" + android.os.Process.myPid()
                + "  t=" + (System.currentTimeMillis() / 1000)
                + "  enabled=" + sEnabled
                + "  fps=" + (sFrameNs == 33333333L ? "30" : "60");
        // status file in photos' own files dir (readable by the settings app via root)
        try {
            Context c = ctx();
            if (c != null) {
                File f = new File(c.getFilesDir(), "photohdr_status.txt");
                FileWriter w = new FileWriter(f);
                w.write(st + "\n");
                w.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String codecName(Object mc) {
        try {
            Object info = Class.forName("android.media.MediaCodec").getMethod("getCodecInfo").invoke(mc);
            return (String) info.getClass().getMethod("getName").invoke(info);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean irqHooked = false;
    private static int tCount = 0;
    private static boolean anHooked = false;
    private static int anCount = 0;
    private static int anP8P9 = 0; // p8 && !p9 -> would trigger #53 skip
    private static int anRetT = 0;
    private static int anRetF = 0;

    private static long fieldLong(Object o, String name) {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.getLong(o);
            } catch (Throwable ignored) {
            }
            c = c.getSuperclass();
        }
        return Long.MIN_VALUE;
    }

    private static int jeuBCount = 0;
    private static int jeuACount = 0;

    private static void installJeuHook() {
        try {
            Class<?> jeuCls = Class.forName("jeu", false, gLp.classLoader);
            XposedBridge.hookAllMethods(jeuCls, "b", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    jeuBCount++;
                    if (jeuBCount <= 3 || jeuBCount % 90 == 0) {
                        XposedBridge.log("PhotoHDRFix: jeu.b #" + jeuBCount + " th="
                                + Thread.currentThread().getName());
                        if (jeuBCount <= 2) {
                            XposedBridge.log("PhotoHDRFix: jeu.b STACK\n"
                                    + Log.getStackTraceString(new Throwable()));
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(jeuCls, "a", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    jeuACount++;
                    if (jeuACount <= 3 || jeuACount % 90 == 0) {
                        XposedBridge.log("PhotoHDRFix: jeu.a #" + jeuACount + " th="
                                + Thread.currentThread().getName());
                    }
                }
            });
            XposedBridge.log("PhotoHDRFix: hooked jeu.a/b");
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: jeu hook fail " + t);
        }
    }

    private static void installAnHook() {
        XposedBridge.log("PhotoHDRFix: installAnHook enter anHooked=" + anHooked + " gLp=" + (gLp != null));
        if (anHooked) return;
        for (final String cn : new String[] { "jex", "inw" }) {
            try {
                Class<?> cls = Class.forName(cn, false, gLp.classLoader);
                XposedBridge.hookAllMethods(cls, "an", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                        // hook irq.t with the CORRECT classloader (same as jex)
                        hookIrqT(p.thisObject);
                        anCount++;
                        if (anCount <= 30) {
                            long clock = fieldLong(p.thisObject, "f");
                            long pts = p.args.length > 7 && p.args[7] instanceof Long ? (Long) p.args[7] : -1;
                            Object p8 = p.args.length > 8 ? p.args[8] : null;
                            Object p9 = p.args.length > 9 ? p.args[9] : null;
                            XposedBridge.log("PhotoHDRFix: AN #" + anCount + " cls=" + cn
                                    + " p8=" + p8 + " p9=" + p9
                                    + " pts=" + pts + " clock=" + clock
                                    + " d=" + (pts >= 0 && clock != Long.MIN_VALUE ? (pts - clock) : -999));
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                        Object r = p.getResult();
                        if (r instanceof Boolean && (Boolean) r) anRetT++;
                        else if (r instanceof Boolean) anRetF++;
                    }
                });
                XposedBridge.log("PhotoHDRFix: hooked " + cn + ".an");
            } catch (Throwable t) {
                XposedBridge.log("PhotoHDRFix: " + cn + ".an hook fail " + t);
            }
        }
        anHooked = true;
    }

    private static void hookIrqT(Object codecObj) {
        if (irqHooked) return;
        try {
            ClassLoader realCL = codecObj.getClass().getClassLoader();
            Class<?> irq = Class.forName("irq", false, realCL);
            XposedBridge.hookAllMethods(irq, "t", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    tCount++;
                    if (tCount <= 3 || tCount % 90 == 0) {
                        XposedBridge.log("PhotoHDRFix: irq.t #" + tCount + " th="
                                + Thread.currentThread().getName());
                        if (tCount <= 2) {
                            XposedBridge.log("PhotoHDRFix: irq.t STACK\n"
                                    + Log.getStackTraceString(new Throwable()));
                        }
                    }
                }
            });
            irqHooked = true;
            XposedBridge.log("PhotoHDRFix: hooked irq.t (real CL)");
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: irq hook fail " + t);
        }
    }

    private static void installJfoHook() {
        try {
            Class<?> jfo = Class.forName("jfo", false, gLp.classLoader);
            XposedBridge.hookAllMethods(jfo, "n", new XC_MethodHook() {
                private int nCount = 0;
                private int r0 = 0;
                private int r1 = 0;
                private int r2 = 0;
                private int r3 = 0;
                private int r4 = 0;
                private int r5 = 0;

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!sEnabled) return;

                    Object res = param.getResult();
                    int r = res instanceof Number ? ((Number) res).intValue() : -1;
                    nCount++;
                    if (r == 0) r0++;
                    else if (r == 1) r1++;
                    else if (r == 2) r2++;
                    else if (r == 3) r3++;
                    else if (r == 4) r4++;
                    else if (r == 5) r5++;

                    long a = -1;
                    Object kdh = null;
                    try {
                        kdh = param.args[9];
                        Field fa = kdh.getClass().getDeclaredField("a");
                        fa.setAccessible(true);
                        a = fa.getLong(kdh);
                    } catch (Throwable ignore) {
                    }

                    long now = System.nanoTime();

                    if (r == 1 && a > 0) lastRel = a;

                    // jfo.n semantics (verified via bytecode):
                    // r=0: render NOW with System.nanoTime() -> bq+aW (releaseOutputBuffer render)
                    // r=1: render with jfo-computed timestamp -> bq+aW
                    // r=2: DROP (jex.an -> bf "dropVideoBuffer")   <- the bug on O1: excessive drops
                    // r=3: SKIP (jex.an -> bh "skipVideoBuffer")
                    // r=4/5: WAIT (jex.an returns false, frame stays queued) - must NOT touch
                    //
                    // v21: rescue only "about-to-expire" drops.
                    // r=2 (drop) frames whose render time kdh.a is within 2 frame periods
                    // ahead get rewritten to r=1 (render at jfo-computed time, NO forging).
                    // Far-ahead frames stay dropped (that's the player's legit catch-up).
                    if (r == 2) {
                        if (a > now && (a - now) < 2 * sFrameNs) {
                            param.setResult(1);
                            fixCount++;
                            if (fixCount <= 3) {
                                XposedBridge.log("PhotoHDRFix: SAFE-FIX r=2->1 delta="
                                        + ((a - now) / 1000000) + "ms");
                            }
                        } else {
                            keepDrop++;
                            if (keepDrop <= 3) {
                                XposedBridge.log("PhotoHDRFix: KEEP-DROP a=" + a
                                        + " delta=" + ((a - now) / 1000000) + "ms");
                            }
                        }
                    }

                    if (sVerbose && (nCount % 600 == 0 || (fixCount > 0 && fixCount % 90 == 0))) {
                        XposedBridge.log("PhotoHDRFix: JFO n#" + nCount + " r=" + r
                                + " [i0=" + r0 + " s1=" + r1 + " d2=" + r2 + " k3=" + r3 + " h4=" + r4 + " h5=" + r5 + "]"
                                + " fix=" + fixCount + " err=" + fixErr + " kdh.a=" + a);
                    }
                }
            });
            jfoHooked = true;
            XposedBridge.log("PhotoHDRFix: hooked jfo.n (v21, enabled=" + sEnabled
                    + ", fps=" + (sFrameNs == 33333333L ? "30" : "60") + ")");
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: jfo hook pending: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (!"com.google.android.apps.photos".equals(lp.packageName)) return;
        gLp = lp;
        loadSettings();
        notifyStatus();
        ensureReceiver();
        XposedBridge.log("PhotoHDRFix: v21 loaded (enabled=" + sEnabled
                + ", fps=" + (sFrameNs == 33333333L ? "30" : "60") + ", verbose=" + sVerbose + ")");

        try {
            Class<?> mc = Class.forName("android.media.MediaCodec", false, lp.classLoader);
            XposedBridge.hookAllMethods(mc, "configure", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    loadSettings(); // hot-reload settings at each playback start
                    notifyStatus();
                    ensureReceiver();
                    if (sVerbose && confCount < 6) {
                        confCount++;
                        Object fmt = param.args[0];
                        XposedBridge.log("PhotoHDRFix: configure #" + confCount + " codec=" + codecName(param.thisObject)
                                 + " fmt=" + (fmt == null ? "null" : fmt.toString()));
                    }
                    if (!jfoHooked) installJfoHook();

                    // v20: hook jex.bh (skip) and jex.bf (drop) to find ALL drop sources
                    try {
                        Class<?> jexCls = Class.forName("jex", false, lp.classLoader);
                        XposedBridge.hookAllMethods(jexCls, "bh", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                bhCount++;
                                if (bhCount <= 3 || bhCount % 90 == 0) {
                                    XposedBridge.log("PhotoHDRFix: BH #" + bhCount + " th="
                                            + Thread.currentThread().getName());
                                    if (bhCount <= 2) {
                                        XposedBridge.log("PhotoHDRFix: BH-STACK\n"
                                                + Log.getStackTraceString(new Throwable()));
                                    }
                                }
                            }
                        });
                        XposedBridge.hookAllMethods(jexCls, "bf", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                                bfCount++;
                                if (bfCount <= 3 || bfCount % 90 == 0) {
                                    XposedBridge.log("PhotoHDRFix: BF #" + bfCount + " th="
                                            + Thread.currentThread().getName());
                                }
                            }
                        });
                        XposedBridge.log("PhotoHDRFix: hooked jex.bh/bf");
                    } catch (Throwable t) {
                        XposedBridge.log("PhotoHDRFix: jex hook fail " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(mc, "releaseOutputBuffer", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    hookIrqT(param.thisObject);
                    installAnHook();
                    installJeuHook();
                    if (!sVerbose) return;
                    long now = System.nanoTime();
                    long d = 0;
                    if (relPrev >= 0) d = (now - relPrev) / 1000000;
                    relPrev = now;
                    relCount++;
                    Object a1 = param.args.length > 1 ? param.args[1] : null;
                    String kind;
                    if (a1 instanceof Boolean) {
                        kind = ((Boolean) a1) ? "R" : "D";
                        if ((Boolean) a1) renderTrue++;
                        else renderFalse++;
                        if (kind.equals("D") && renderFalse <= 3) {
                            XposedBridge.log("PhotoHDRFix: DROP-STACK #" + renderFalse + "\n"
                                    + Log.getStackTraceString(new Throwable()));
                        }
                    } else if (a1 instanceof Long) {
                        kind = "T";
                        renderTs++;
                    } else {
                        kind = "?";
                    }
                    if (relCount <= 30 || relCount % 90 == 0) {
                        XposedBridge.log("PhotoHDRFix: relOB #" + relCount + " dt=" + d + "ms kind=" + kind
                                + " th=" + Thread.currentThread().getName()
                                + " [R=" + renderTrue + " D=" + renderFalse + " T=" + renderTs + "]");
                    }
                }
            });
            XposedBridge.log("PhotoHDRFix: hooked MediaCodec");
        } catch (Throwable t) {
            XposedBridge.log("PhotoHDRFix: MediaCodec hook fail " + t);
        }

        try {
            if (!jfoHooked) installJfoHook();
        } catch (Throwable ignored) {
        }
    }
}