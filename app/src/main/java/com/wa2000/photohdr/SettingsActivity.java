package com.wa2000.photohdr;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * PhotoHDRFix v2.3.0 - True Kyant0 / DouYinLiquidTab Optical Refraction Edition
 *
 * Key Upgrades matching Video 2 (DouYinLiquidTab / Kyant0 AndroidLiquidGlass):
 * 1. Crystal-Clear Liquid Glass Dock (Zero muddy grey fill!):
 *    - Ultra-clear transparent body (alpha = 20) with a crisp 1.4dp specular border rim.
 *    - Floating cyan/accent-tinted active lens pill with a bright inner water-drop specular ring.
 * 2. Real-Time Optical Refraction & Magnification Clone Layer:
 *    - Inside the sliding active pill (`lensViewport`), a synchronized, 1.07x magnified & bright-lit
 *      refraction layer counter-scrolls in real time as the pill moves or drags, creating genuine
 *      water-drop lens refraction over the background content and tab icons!
 * 3. Interactive Drag + Tap + Gel-Bulge Spring Physics:
 *    - Supports both smooth finger dragging across the dock and direct tapping.
 *    - On press/drag, the active water-drop pill expands (scaleX 1.12x, scaleY 1.08x) and brightens
 *      its specular rim, then settles with Kyant0's underdamped spring curve.
 * 4. Rich Deep-Space / Ocean Atmospheric Gradient Background + Crisp 1dp Glass Cards:
 *    - Eliminates flat dull black; uses a deep atmospheric gradient backdrop and translucent
 *      1dp-bordered glass cards so background colors refract vividly through the bottom dock.
 */
public class SettingsActivity extends Activity {

    private static final Interpolator I_EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator I_LIQUID_SPRING = new PathInterpolator(0.16f, 0.92f, 0.28f, 1.16f);

    // Dynamic Palette Tokens
    private int C_BG_TOP;
    private int C_BG_BOTTOM;
    private int C_CARD_FILL;
    private int C_CARD_BORDER;
    private int C_SURFACE_HIGH;
    private int C_PRIMARY;
    private int C_ON_PRIMARY;
    private int C_PRIMARY_CONTAINER;
    private int C_ON_PRIMARY_CONTAINER;
    private int C_SECONDARY_CONTAINER;
    private int C_ON_SECONDARY_CONTAINER;
    private int C_ON_SURFACE;
    private int C_ON_SURFACE_VARIANT;

    // Liquid Glass Optical Tokens
    private int C_DOCK_GLASS_FILL;
    private int C_DOCK_GLASS_RIM;
    private int C_PILL_LENS_FILL;
    private int C_PILL_LENS_RIM;
    private int C_PILL_GLOW;

    private boolean night;
    private int curPage = 0;

    private FrameLayout content;
    private ScrollView homePage;
    private ScrollView settingsPage;

    // Hero Banner Elements
    private LinearLayout heroCard;
    private TextView heroTitle;
    private TextView heroSub;
    private TextView heroModeTag;
    private TextView heroBadge;

    // True Kyant0 Liquid Glass Dock + Lens Refraction Viewport
    private FrameLayout dockBar;
    private FrameLayout dockPill;
    private LinearLayout refractedTabsRow; // Magnified inner lens layer for real optical refraction!
    private TextView icHome;
    private TextView icSettings;
    private TextView icAbout;
    private TextView lblHome;
    private TextView lblSettings;
    private TextView lblAbout;
    private ScrollView aboutPage;

    // Exact Geometry for 3-Tab Dock (Home / Settings / About - matching Video 2!)
    private int dockOuterWidth;
    private int dockInnerPad;
    private int tabSlotWidth;
    private float currentPillX = 0f;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String st = intent.getStringExtra("st");
            if (st != null) {
                ConfigStore.status = st;
                ConfigStore.statusTime = System.currentTimeMillis();
                ConfigStore.save(SettingsActivity.this);
                refreshStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            requestWindowFeature(1); // Hide duplicate system ActionBar
        } catch (Throwable ignored) {}

        super.onCreate(savedInstanceState);
        ConfigStore.load(this);
        loadTheme();
        buildUi();
        setContentView(content);
        applySystemBars();
        showPage(0, false);

        IntentFilter filter = new IntentFilter("com.wa2000.photohdr.STATUS");
        registerSafe(statusReceiver, filter);
        broadcastSettings();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(statusReceiver);
        } catch (Throwable ignored) {}
    }

    private void registerSafe(BroadcastReceiver r, IntentFilter f) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                Class<?> ctxCls = Context.class;
                java.lang.reflect.Method m = ctxCls.getMethod("registerReceiver", BroadcastReceiver.class, IntentFilter.class, Integer.TYPE);
                m.invoke(this, r, f, Integer.valueOf(2));
                return;
            }
        } catch (Throwable ignored) {}
        try {
            registerReceiver(r, f);
        } catch (Throwable ignored) {}
    }

    private int c(int resId, int fallback) {
        try {
            int val = getColor(resId);
            if (val != 0) return val;
        } catch (Throwable ignored) {}
        return fallback;
    }

    /**
     * Resolves Deep-Space / Ocean Atmospheric Backdrop + Monet & Crystal Glass Tokens
     */
    private void loadTheme() {
        int uiMode = getResources().getConfiguration().uiMode & 48;
        night = (uiMode == 32);

        String theme = ConfigStore.theme != null ? ConfigStore.theme : "ocean";

        if ("monet".equals(theme) && Build.VERSION.SDK_INT >= 31) {
            C_PRIMARY = c(night ? 17170491 : 17170495, night ? 0xFF78C5FF : 0xFF006493);
            C_ON_PRIMARY = night ? 0xFF003351 : 0xFFFFFFFF;
            C_PRIMARY_CONTAINER = c(night ? 17170496 : 17170490, night ? 0xFF1A4975 : 0xFFCBE6FF);
            C_ON_PRIMARY_CONTAINER = c(night ? 17170490 : 17170498, night ? 0xFFD8ECFF : 0xFF001E30);
            C_SECONDARY_CONTAINER = c(night ? 17170509 : 17170504, night ? 0xFF284B6A : 0xFFD3E5F5);
            C_ON_SECONDARY_CONTAINER = c(night ? 17170503 : 17170511, night ? 0xFFD3E5F5 : 0xFF0C1D29);
        } else if ("purple".equals(theme)) {
            // KernelSU Berry Purple
            C_PRIMARY = night ? 0xFFEAB5ED : 0xFF7B4E7F;
            C_ON_PRIMARY = night ? 0xFF48204C : 0xFFFFFFFF;
            C_PRIMARY_CONTAINER = night ? 0xFF4C3654 : 0xFFFAD7FF;
            C_ON_PRIMARY_CONTAINER = night ? 0xFFFCE2FF : 0xFF310937;
            C_SECONDARY_CONTAINER = night ? 0xFFE5B8C8 : 0xFFFFD8E6;
            C_ON_SECONDARY_CONTAINER = night ? 0xFF3E1C29 : 0xFF3B071D;
        } else if ("moss".equals(theme)) {
            C_PRIMARY = night ? 0xFF7CE89A : 0xFF236C3A;
            C_ON_PRIMARY = night ? 0xFF003919 : 0xFFFFFFFF;
            C_PRIMARY_CONTAINER = night ? 0xFF174E2C : 0xFFB4F6C4;
            C_ON_PRIMARY_CONTAINER = night ? 0xFFD2FFE0 : 0xFF00210C;
            C_SECONDARY_CONTAINER = night ? 0xFF264E36 : 0xFFD4E8D9;
            C_ON_SECONDARY_CONTAINER = night ? 0xFFD4E8D9 : 0xFF0F1F15;
        } else if ("rose".equals(theme)) {
            C_PRIMARY = night ? 0xFFFFB1C8 : 0xFF984061;
            C_ON_PRIMARY = night ? 0xFF5E1133 : 0xFFFFFFFF;
            C_PRIMARY_CONTAINER = night ? 0xFF5A253A : 0xFFFFD9E2;
            C_ON_PRIMARY_CONTAINER = night ? 0xFFFFD9E2 : 0xFF3E001D;
            C_SECONDARY_CONTAINER = night ? 0xFF6B3248 : 0xFFFFD9E2;
            C_ON_SECONDARY_CONTAINER = night ? 0xFFFFD9E2 : 0xFF2B151C;
        } else {
            // Video 2 Signature "LiquidTab Deep Sapphire & Cyan" Palette
            C_PRIMARY = night ? 0xFF47B8FF : 0xFF006493;
            C_ON_PRIMARY = night ? 0xFF00304D : 0xFFFFFFFF;
            C_PRIMARY_CONTAINER = night ? 0xFF164370 : 0xFFCBE6FF;
            C_ON_PRIMARY_CONTAINER = night ? 0xFFE3F3FF : 0xFF001E30;
            C_SECONDARY_CONTAINER = night ? 0xFF1F6E58 : 0xFFC8F5E4;
            C_ON_SECONDARY_CONTAINER = night ? 0xFF8FF7D4 : 0xFF002117;
        }

        if (night) {
            // Atmospheric Deep Gradient Backdrop (Video 2 style)
            C_BG_TOP = blend(0xFF0B131E, C_PRIMARY_CONTAINER, 0.25f);
            C_BG_BOTTOM = blend(0xFF121D2E, C_PRIMARY_CONTAINER, 0.42f);
            // Translucent Glass Cards with crisp 1dp border
            C_CARD_FILL = alpha(blend(0xFF182436, C_PRIMARY_CONTAINER, 0.28f), 175);
            C_CARD_BORDER = alpha(0xFFFFFFFF, 34);
            C_SURFACE_HIGH = alpha(blend(0xFF22324A, C_PRIMARY_CONTAINER, 0.35f), 205);
            C_ON_SURFACE = 0xFFF2F6FC;
            C_ON_SURFACE_VARIANT = 0xFFAAB8CC;

            // Crystal-Clear Liquid Glass Dock (Zero muddy grey!)
            C_DOCK_GLASS_FILL = alpha(blend(0xFF0E1A2B, C_PRIMARY_CONTAINER, 0.22f), 115);
            C_DOCK_GLASS_RIM = alpha(0xFFFFFFFF, 76);

            // Water-Drop Active Lens Pill (Crisp cyan/primary refraction ring)
            C_PILL_LENS_FILL = alpha(blend(C_PRIMARY, 0xFFFFFFFF, 0.15f), 58);
            C_PILL_LENS_RIM = alpha(blend(C_PRIMARY, 0xFFFFFFFF, 0.65f), 175);
            C_PILL_GLOW = alpha(C_PRIMARY, 95);
        } else {
            C_BG_TOP = blend(0xFFF2F7FC, C_PRIMARY_CONTAINER, 0.25f);
            C_BG_BOTTOM = blend(0xFFE4EFFA, C_PRIMARY_CONTAINER, 0.45f);
            C_CARD_FILL = alpha(0xFFFFFFFF, 200);
            C_CARD_BORDER = alpha(C_PRIMARY, 38);
            C_SURFACE_HIGH = alpha(blend(0xFFEAF2FA, C_PRIMARY_CONTAINER, 0.40f), 220);
            C_ON_SURFACE = 0xFF101A26;
            C_ON_SURFACE_VARIANT = 0xFF4E5E72;

            C_DOCK_GLASS_FILL = alpha(0xFFFFFFFF, 145);
            C_DOCK_GLASS_RIM = alpha(0xFFFFFFFF, 210);

            C_PILL_LENS_FILL = alpha(C_PRIMARY, 45);
            C_PILL_LENS_RIM = alpha(C_PRIMARY, 145);
            C_PILL_GLOW = alpha(C_PRIMARY, 70);
        }
    }

    private void applySystemBars() {
        try {
            Window w = getWindow();
            GradientDrawable bgGrad = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{C_BG_TOP, C_BG_BOTTOM});
            w.setBackgroundDrawable(bgGrad);
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController ic = w.getInsetsController();
                if (ic != null) {
                    int app = night ? 0 : 24;
                    ic.setSystemBarsAppearance(app, 24);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Builds Crystal-Clear Outer Dock Shell (Matches Video 2: transparent glass + crisp white specular rim)
     */
    private GradientDrawable createCrystalDockShellDrawable(float radiusPx) {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        alpha(blend(C_DOCK_GLASS_FILL, 0xFFFFFFFF, 0.12f), night ? 110 : 155),
                        C_DOCK_GLASS_FILL
                });
        gd.setCornerRadius(radiusPx);
        gd.setStroke(dp(1.2f), C_DOCK_GLASS_RIM);
        return gd;
    }

    /**
     * Builds Water-Drop Lens Pill Drawable (Matches Video 2: bright refractive rim + crystal inner lens)
     */
    private GradientDrawable createWaterDropLensDrawable(float radiusPx, boolean pressed) {
        int fillTop = alpha(blend(C_PRIMARY, 0xFFFFFFFF, 0.38f), pressed ? 92 : 64);
        int fillBot = alpha(C_PRIMARY, pressed ? 68 : 42);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{fillTop, fillBot});
        gd.setCornerRadius(radiusPx);
        gd.setStroke(dp(pressed ? 1.8f : 1.4f), pressed ? alpha(0xFFFFFFFF, 220) : C_PILL_LENS_RIM);
        return gd;
    }

    private void buildUi() {
        content = new FrameLayout(this);
        GradientDrawable bgGrad = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_BG_TOP, C_BG_BOTTOM});
        content.setBackground(bgGrad);

        homePage = buildHomePage();
        settingsPage = buildSettingsPage();
        aboutPage = buildAboutPage();
        settingsPage.setVisibility(View.GONE);
        aboutPage.setVisibility(View.GONE);

        content.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(aboutPage, new FrameLayout.LayoutParams(-1, -1));

        // Floating Crystal Liquid Glass Bottom Bar (Video 2 3-Tab Layout)
        dockBar = buildNavBar();
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                dockOuterWidth, dp(68f), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dockLp.bottomMargin = dp(20f);
        content.addView(dockBar, dockLp);
    }

    /**
     * Page 1: Dashboard (Matches Video 2 Clean Centered Header + Glass Status Cards + Numbered Guide)
     */
    private ScrollView buildHomePage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18f), dp(22f), dp(18f), dp(115f));

        // 1. Centered Clean Top Title (Matches Video 2 "主页")
        TextView topTitle = label("主页", 20f, C_ON_SURFACE, true);
        topTitle.setGravity(Gravity.CENTER);
        topTitle.setPadding(0, dp(6f), 0, dp(18f));
        col.addView(topTitle, new LinearLayout.LayoutParams(-1, -2));

        // 2. Hero Activation Status Card (Matches Video 2 Blue Check Circle + Device Info)
        heroCard = glassCard();
        heroCard.setOrientation(LinearLayout.HORIZONTAL);
        heroCard.setGravity(Gravity.CENTER_VERTICAL);
        heroCard.setPadding(dp(18f), dp(18f), dp(18f), dp(18f));

        TextView checkCircle = label("✓", 20f, 0xFFFFFFFF, true);
        checkCircle.setGravity(Gravity.CENTER);
        checkCircle.setBackground(rounded(C_PRIMARY, dp(22f)));
        heroCard.addView(checkCircle, new LinearLayout.LayoutParams(dp(44f), dp(44f)));

        LinearLayout heroTextCol = new LinearLayout(this);
        heroTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(0, -2, 1f);
        htLp.leftMargin = dp(16f);

        heroTitle = label("模块已激活", 18f, C_ON_SURFACE, true);
        heroTextCol.addView(heroTitle);

        heroSub = label("Xiaomi · " + Build.MODEL + " · 安卓 " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                13f, C_ON_SURFACE_VARIANT, false);
        heroSub.setPadding(0, dp(3f), 0, 0);
        heroTextCol.addView(heroSub);

        heroCard.addView(heroTextCol, htLp);
        heroCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ConfigStore.en = !ConfigStore.en;
                ConfigStore.save(SettingsActivity.this);
                broadcastSettings();
                refreshStatus();
            }
        });
        col.addView(heroCard);
        col.addView(gap(12));

        // 3. Target Host App Match Card (Matches Video 2 "抖音 v41.0.0 已适配" Card)
        LinearLayout hostCard = glassCard();
        hostCard.setOrientation(LinearLayout.HORIZONTAL);
        hostCard.setGravity(Gravity.CENTER_VERTICAL);
        hostCard.setPadding(dp(18f), dp(16f), dp(18f), dp(16f));

        LinearLayout hostLeft = new LinearLayout(this);
        hostLeft.setOrientation(LinearLayout.VERTICAL);
        hostLeft.addView(label("Google 相册", 16.5f, C_ON_SURFACE, true));
        TextView hostVerTv = label("v" + getPhotosVer() + " · 杜比视界 Profile 8.4 硬解直通", 12.5f, C_ON_SURFACE_VARIANT, false);
        hostVerTv.setPadding(0, dp(3f), 0, 0);
        hostLeft.addView(hostVerTv);
        hostCard.addView(hostLeft, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout hostRight = new LinearLayout(this);
        hostRight.setOrientation(LinearLayout.VERTICAL);
        hostRight.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        hostModeTag = label("c2.xring.hevc", 15f, C_ON_SURFACE, true);
        hostRight.addView(hostModeTag);

        heroBadge = label("已适配", 11f, C_ON_SECONDARY_CONTAINER, true);
        heroBadge.setPadding(dp(8f), dp(2f), dp(8f), dp(2f));
        heroBadge.setBackground(roundedStroke(C_SECONDARY_CONTAINER, C_PRIMARY, dp(6f), dp(1f)));
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(-2, -2);
        hbLp.topMargin = dp(4f);
        hostRight.addView(heroBadge, hbLp);
        hostCard.addView(hostRight);

        col.addView(hostCard);
        col.addView(gap(18));

        // 4. Section Header: 总开关控制
        TextView secCtrl = label("总开关控制", 13.5f, C_ON_SURFACE_VARIANT, true);
        secCtrl.setPadding(dp(4f), 0, dp(4f), dp(8f));
        col.addView(secCtrl);

        // Quick Toggles (Video 2 Individual Glass Row Cards)
        col.addView(standaloneSwitchCard("杜比视界硬解直通", "将崩溃的 dolby.decoder 重定向至 10-bit HEVC", ConfigStore.fixDv, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                ConfigStore.fixDv = v;
                ConfigStore.save(SettingsActivity.this);
                broadcastSettings();
            }
        }));
        col.addView(gap(10));

        col.addView(standaloneSwitchCard("前台 120Hz 高刷锁定", "消除 LTPO 动态切档引起的 VSync 节拍错位", ConfigStore.lockFps, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                ConfigStore.lockFps = v;
                ConfigStore.save(SettingsActivity.this);
                broadcastSettings();
            }
        }));
        col.addView(gap(10));

        col.addView(standaloneSwitchCard("BT.2020 HLG 广色域保留", "保留 10-bit HDR 高动态范围色彩映射与高光细节", ConfigStore.lowLat, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                ConfigStore.lowLat = v;
                ConfigStore.save(SettingsActivity.this);
                broadcastSettings();
            }
        }));
        col.addView(gap(18));

        // 5. Section Header: 使用指南 (Video 2 Numbered Guide Cards so scrolling under dock shows real refraction!)
        TextView secGuide = label("使用指南", 13.5f, C_ON_SURFACE_VARIANT, true);
        secGuide.setPadding(dp(4f), 0, dp(4f), dp(8f));
        col.addView(secGuide);

        col.addView(numberedGuideCard("1", "在 LSPosed 中启用本模块，并勾选作用域 Google 相册"));
        col.addView(gap(10));
        col.addView(numberedGuideCard("2", "模块自动将杜比视界流重定向至玄戒 O1 原生 HEVC 硬解管线"));
        col.addView(gap(10));
        col.addView(numberedGuideCard("3", "切换开关或帧率档位后实时广播生效，直接播放视频即可丝滑呈现"));

        sv.addView(col);
        return sv;
    }

    private TextView hostModeTag;

    /**
     * Page 2: Advanced Settings & Monet Theme Palette
     */
    private ScrollView buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18f), dp(22f), dp(18f), dp(115f));

        TextView topTitle = label("设置", 20f, C_ON_SURFACE, true);
        topTitle.setGravity(Gravity.CENTER);
        topTitle.setPadding(0, dp(6f), 0, dp(18f));
        col.addView(topTitle, new LinearLayout.LayoutParams(-1, -2));

        // Section 1: Frame Rate
        TextView secFps = label("渲染节拍与刷新率", 13.5f, C_ON_SURFACE_VARIANT, true);
        secFps.setPadding(dp(4f), 0, dp(4f), dp(8f));
        col.addView(secFps);

        LinearLayout fpsCard = glassCard();
        fpsCard.setPadding(dp(16f), dp(16f), dp(16f), dp(16f));

        LinearLayout segRow = new LinearLayout(this);
        segRow.setOrientation(LinearLayout.HORIZONTAL);
        final String[] opts = {"120", "60"};
        final String[] names = {"120 Hz 满血高刷", "60 Hz 标准节拍"};

        for (int i = 0; i < opts.length; i++) {
            final String val = opts[i];
            boolean sel = val.equals(ConfigStore.fps);
            TextView btn = label(names[i], 14f, sel ? 0xFFFFFFFF : C_ON_SURFACE_VARIANT, sel);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(12f), 0, dp(12f));
            btn.setBackground(sel
                    ? createWaterDropLensDrawable(dp(16f), false)
                    : roundedStroke(C_SURFACE_HIGH, C_CARD_BORDER, dp(16f), dp(1f)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(10f);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigStore.fps = val;
                    ConfigStore.save(SettingsActivity.this);
                    broadcastSettings();
                    applyThemeAndRebuild();
                }
            });
            segRow.addView(btn, lp);
        }
        fpsCard.addView(segRow);
        col.addView(fpsCard);
        col.addView(gap(18));

        // Section 2: Theme & Monet
        TextView secTheme = label("莫奈取色与液态玻璃主题", 13.5f, C_ON_SURFACE_VARIANT, true);
        secTheme.setPadding(dp(4f), 0, dp(4f), dp(8f));
        col.addView(secTheme);

        LinearLayout thCard = glassCard();
        thCard.setPadding(dp(16f), dp(16f), dp(16f), dp(16f));

        TextView thSub = label("切换深海液态蓝、莫奈壁纸取色或 KernelSU 经典配色", 13f, C_ON_SURFACE_VARIANT, false);
        thSub.setPadding(dp(2f), 0, dp(2f), dp(12f));
        thCard.addView(thSub);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final String[] tKeys = {"ocean", "monet", "purple", "moss", "rose"};
        final String[] tNames = {"深海液态蓝", "莫奈壁纸", "KernelSU 紫", "翡翠森林", "绯红落日"};
        final int[] tDots = {0xFF47B8FF, 0xFFD0BCFF, 0xFFEAB5ED, 0xFF7CE89A, 0xFFFFB1C8};

        for (int i = 0; i < tKeys.length; i++) {
            final String k = tKeys[i];
            boolean act = k.equals(ConfigStore.theme);

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(14f), dp(10f), dp(16f), dp(10f));
            chip.setBackground(act
                    ? createWaterDropLensDrawable(dp(16f), false)
                    : roundedStroke(C_SURFACE_HIGH, C_CARD_BORDER, dp(16f), dp(1f)));

            View dot = new View(this);
            dot.setBackground(rounded(tDots[i], dp(7f)));
            chip.addView(dot, new LinearLayout.LayoutParams(dp(14f), dp(14f)));

            TextView tv = label(tNames[i], 13f, act ? 0xFFFFFFFF : C_ON_SURFACE, act);
            LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(-2, -2);
            tvLp.leftMargin = dp(8f);
            chip.addView(tv, tvLp);

            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) cLp.leftMargin = dp(10f);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ConfigStore.theme = k;
                    ConfigStore.save(SettingsActivity.this);
                    applyThemeAndRebuild();
                }
            });
            row.addView(chip, cLp);
        }
        hsv.addView(row);
        thCard.addView(hsv);
        col.addView(thCard);
        col.addView(gap(18));

        // Section 3: Diagnostics
        TextView secDiag = label("调试与诊断", 13.5f, C_ON_SURFACE_VARIANT, true);
        secDiag.setPadding(dp(4f), 0, dp(4f), dp(8f));
        col.addView(secDiag);

        col.addView(standaloneSwitchCard("详细调试日志 (Verbose)", "向 LSPosed 日志输出每次 MediaCodec 拦截参数", ConfigStore.vb, new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                ConfigStore.vb = v;
                ConfigStore.save(SettingsActivity.this);
                broadcastSettings();
            }
        }));

        sv.addView(col);
        return sv;
    }

    /**
     * Page 3: About & Credits (Matches Video 2 "关于" Page Layout)
     */
    private ScrollView buildAboutPage() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(18f), dp(22f), dp(18f), dp(115f));

        TextView topTitle = label("关于", 20f, C_ON_SURFACE, true);
        topTitle.setGravity(Gravity.CENTER);
        topTitle.setPadding(0, dp(6f), 0, dp(22f));
        col.addView(topTitle, new LinearLayout.LayoutParams(-1, -2));

        // App Glass Squircle Icon
        TextView appIcon = label("HDR", 24f, 0xFFFFFFFF, true);
        appIcon.setGravity(Gravity.CENTER);
        appIcon.setBackground(createWaterDropLensDrawable(dp(24f), true));
        col.addView(appIcon, new LinearLayout.LayoutParams(dp(82f), dp(82f)));
        col.addView(gap(12));

        col.addView(label("PhotoHDRFix", 22f, C_ON_SURFACE, true));
        TextView verTv = label("v" + getSelfVer(), 13f, C_ON_SURFACE_VARIANT, false);
        verTv.setPadding(0, dp(2f), 0, dp(4f));
        col.addView(verTv);

        TextView descTv = label("小米 15S Pro (玄戒 O1) 杜比视界硬解直通与液态玻璃材质模块", 13f, C_ON_SURFACE_VARIANT, false);
        descTv.setGravity(Gravity.CENTER);
        descTv.setPadding(dp(12f), 0, dp(12f), dp(20f));
        col.addView(descTv);

        // Two-Column Environment Cards (Matches Video 2 "支持环境 / 离线与隐私")
        LinearLayout gridRow = new LinearLayout(this);
        gridRow.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout envLeft = glassCard();
        envLeft.setPadding(dp(16f), dp(16f), dp(16f), dp(16f));
        TextView ic1 = label("⚙", 20f, C_PRIMARY, true);
        envLeft.addView(ic1);
        TextView t1 = label("支持环境", 15f, C_ON_SURFACE, true);
        t1.setPadding(0, dp(8f), 0, dp(4f));
        envLeft.addView(t1);
        envLeft.addView(label("玄戒 O1 / Android 13+\nLSPosed API 100+", 12f, C_ON_SURFACE_VARIANT, false));

        LinearLayout envRight = glassCard();
        envRight.setPadding(dp(16f), dp(16f), dp(16f), dp(16f));
        TextView ic2 = label("🛡", 20f, C_PRIMARY, true);
        envRight.addView(ic2);
        TextView t2 = label("离线与安全", 15f, C_ON_SURFACE, true);
        t2.setPadding(0, dp(8f), 0, dp(4f));
        envRight.addView(t2);
        envRight.addView(label("纯本地硬解重定向\n零网络权限 · 零异常开销", 12f, C_ON_SURFACE_VARIANT, false));

        LinearLayout.LayoutParams gLp1 = new LinearLayout.LayoutParams(0, -2, 1f);
        gLp1.rightMargin = dp(6f);
        LinearLayout.LayoutParams gLp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        gLp2.leftMargin = dp(6f);
        gridRow.addView(envLeft, gLp1);
        gridRow.addView(envRight, gLp2);
        col.addView(gridRow, new LinearLayout.LayoutParams(-1, -2));
        col.addView(gap(12));

        // Credits Card (Kyant0 / AndroidLiquidGlass & Team)
        LinearLayout creditCard = glassCard();
        creditCard.setPadding(dp(18f), dp(16f), dp(18f), dp(16f));
        creditCard.addView(label("开源致谢与光学引擎", 15.5f, C_ON_SURFACE, true));
        TextView crSub = label("• kyant0 / AndroidLiquidGlass — 液态玻璃光学折射与弹簧物理模型\n• WA2000 & Operit AI & DeepSeek & Gemini — 联合逆向与架构重构",
                12.5f, C_ON_SURFACE_VARIANT, false);
        crSub.setPadding(0, dp(6f), 0, 0);
        creditCard.addView(crSub);
        col.addView(creditCard, new LinearLayout.LayoutParams(-1, -2));

        sv.addView(col);
        return sv;
    }

    /**
     * Video 2 Authentic Liquid Glass Floating Bottom Dock:
     * - Crystal-clear outer capsule (no muddy grey!)
     * - Water-drop active lens pill (`dockPill`) that clips and counter-translates a 1.08x magnified
     *   refraction layer (`refractedTabsRow`), producing genuine real-time lens refraction as you drag or switch tabs!
     */
    private FrameLayout buildNavBar() {
        int screenW = getScreenW();
        int horizontalMargin = dp(20f);
        dockInnerPad = dp(4f);
        dockOuterWidth = screenW - (horizontalMargin * 2);
        tabSlotWidth = (dockOuterWidth - (dockInnerPad * 2)) / 3;
        final int innerWidth = tabSlotWidth * 3;
        final int pillHeight = dp(60f);

        final FrameLayout bar = new FrameLayout(this);
        bar.setBackground(createCrystalDockShellDrawable(dp(34f)));
        bar.setPadding(dockInnerPad, dockInnerPad, dockInnerPad, dockInnerPad);

        // 1. Base Dimmed Tabs Layer (Unselected background tabs)
        LinearLayout baseTabs = new LinearLayout(this);
        baseTabs.setOrientation(LinearLayout.HORIZONTAL);
        baseTabs.setGravity(Gravity.CENTER_VERTICAL);
        baseTabs.addView(createNavTabView(0, "⌂", "主页", false), new LinearLayout.LayoutParams(tabSlotWidth, pillHeight));
        baseTabs.addView(createNavTabView(1, "⚙", "设置", false), new LinearLayout.LayoutParams(tabSlotWidth, pillHeight));
        baseTabs.addView(createNavTabView(2, "ⓘ", "关于", false), new LinearLayout.LayoutParams(tabSlotWidth, pillHeight));
        bar.addView(baseTabs, new FrameLayout.LayoutParams(innerWidth, pillHeight));

        // 2. Water-Drop Active Lens Pill with Internal Refracted/Magnified Layer!
        dockPill = new FrameLayout(this);
        dockPill.setBackground(createWaterDropLensDrawable(dp(30f), false));

        // Inside the water-drop pill: a full-width row of bright, 1.08x magnified tabs
        // that counter-translates by -pillX so the lens physically refracts whatever tab is underneath it!
        refractedTabsRow = new LinearLayout(this);
        refractedTabsRow.setOrientation(LinearLayout.HORIZONTAL);
        refractedTabsRow.setGravity(Gravity.CENTER_VERTICAL);
        refractedTabsRow.addView(createNavTabView(0, "⌂", "主页", true), new LinearLayout.LayoutParams(tabSlotWidth, pillHeight));
        refractedTabsRow.addView(createNavTabView(1, "⚙", "设置", true), new LinearLayout.LayoutParams(tabSlotWidth, pillHeight));
        refractedTabsRow.addView(createNavTabView(2, "ⓘ", "关于", true), new LinearLayout.LayoutParams(tabSlotWidth, pillHeight));
        refractedTabsRow.setScaleX(1.07f);
        refractedTabsRow.setScaleY(1.07f);

        dockPill.addView(refractedTabsRow, new FrameLayout.LayoutParams(innerWidth, pillHeight));

        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                tabSlotWidth, pillHeight, Gravity.CENTER_VERTICAL | Gravity.LEFT);
        bar.addView(dockPill, pillLp);

        // 3. Unified Gesture Layer on Dock: Supports smooth horizontal dragging & tap with water-drop bulge!
        bar.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float startPillX;
            private boolean isDragging;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int act = ev.getActionMasked();
                float x = ev.getX() - dockInnerPad;

                if (act == MotionEvent.ACTION_DOWN) {
                    downX = x;
                    startPillX = currentPillX;
                    isDragging = false;
                    // Water-drop lens swell on touch
                    dockPill.setBackground(createWaterDropLensDrawable(dp(30f), true));
                    dockPill.animate().scaleX(1.10f).scaleY(1.06f).setDuration(140L).setInterpolator(I_EMPHASIZED).start();
                    return true;
                } else if (act == MotionEvent.ACTION_MOVE) {
                    float dx = x - downX;
                    if (Math.abs(dx) > dp(6f)) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        float maxTx = tabSlotWidth * 2f;
                        float newX = Math.max(0f, Math.min(maxTx, startPillX + dx));
                        setLensPosition(newX);
                    }
                    return true;
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    dockPill.setBackground(createWaterDropLensDrawable(dp(30f), false));
                    dockPill.animate().scaleX(1f).scaleY(1f).setDuration(340L).setInterpolator(I_LIQUID_SPRING).start();

                    int targetIdx;
                    if (isDragging) {
                        targetIdx = Math.round(currentPillX / tabSlotWidth);
                    } else {
                        targetIdx = (int) (x / tabSlotWidth);
                    }
                    targetIdx = Math.max(0, Math.min(2, targetIdx));
                    showPage(targetIdx, true);
                    return true;
                }
                return false;
            }
        });

        return bar;
    }

    /**
     * Synchronizes the water-drop pill position AND counter-scrolls the inner magnified refraction layer
     */
    private void setLensPosition(float pillX) {
        currentPillX = pillX;
        dockPill.setTranslationX(pillX);
        if (refractedTabsRow != null) {
            // Counter-translate inner magnified layer so icons stay anchored in world-space while the lens glides over them!
            refractedTabsRow.setTranslationX(-pillX);
        }
        // Subtle base layer fade under active lens to avoid double-vision
        float progress0 = 1f - Math.min(1f, Math.abs(pillX - 0f) / tabSlotWidth);
        float progress1 = 1f - Math.min(1f, Math.abs(pillX - tabSlotWidth) / tabSlotWidth);
        float progress2 = 1f - Math.min(1f, Math.abs(pillX - tabSlotWidth * 2f) / tabSlotWidth);
        if (icHome != null) {
            icHome.setAlpha(1f - progress0 * 0.85f);
            lblHome.setAlpha(1f - progress0 * 0.85f);
        }
        if (icSettings != null) {
            icSettings.setAlpha(1f - progress1 * 0.85f);
            lblSettings.setAlpha(1f - progress1 * 0.85f);
        }
        if (icAbout != null) {
            icAbout.setAlpha(1f - progress2 * 0.85f);
            lblAbout.setAlpha(1f - progress2 * 0.85f);
        }
    }

    private LinearLayout createNavTabView(int idx, String iconStr, String textStr, boolean isRefractedLensLayer) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);

        int color = isRefractedLensLayer ? C_PRIMARY : C_ON_SURFACE_VARIANT;
        TextView ic = label(iconStr, isRefractedLensLayer ? 21f : 19.5f, color, true);
        ic.setGravity(Gravity.CENTER);

        TextView lb = label(textStr, isRefractedLensLayer ? 12.5f : 11.5f, color, isRefractedLensLayer);
        lb.setGravity(Gravity.CENTER);
        lb.setPadding(0, dp(2f), 0, 0);

        if (!isRefractedLensLayer) {
            if (idx == 0) {
                icHome = ic;
                lblHome = lb;
            } else if (idx == 1) {
                icSettings = ic;
                lblSettings = lb;
            } else {
                icAbout = ic;
                lblAbout = lb;
            }
        }

        tab.addView(ic, new LinearLayout.LayoutParams(-1, -2));
        tab.addView(lb, new LinearLayout.LayoutParams(-1, -2));
        return tab;
    }

    private ValueAnimator pillAnimator;

    private void showPage(int idx, boolean anim) {
        curPage = idx;
        ScrollView[] pages = new ScrollView[]{homePage, settingsPage, aboutPage};
        for (int i = 0; i < pages.length; i++) {
            ScrollView p = pages[i];
            if (i == idx) {
                if (p.getVisibility() != View.VISIBLE) {
                    p.setVisibility(View.VISIBLE);
                    if (anim) {
                        p.setAlpha(0f);
                        p.setTranslationY(dp(8f));
                        p.animate().alpha(1f).translationY(0f).setDuration(240L).setInterpolator(I_EMPHASIZED).start();
                    } else {
                        p.setAlpha(1f);
                        p.setTranslationY(0f);
                    }
                }
            } else {
                p.setVisibility(View.GONE);
            }
        }
        animateLensToTab(idx, anim);
    }

    private void animateLensToTab(int idx, boolean anim) {
        final float targetX = idx * tabSlotWidth;
        if (pillAnimator != null) {
            pillAnimator.cancel();
        }
        if (!anim) {
            setLensPosition(targetX);
            return;
        }

        final float startX = currentPillX;
        pillAnimator = ValueAnimator.ofFloat(0f, 1f);
        pillAnimator.setDuration(360L);
        pillAnimator.setInterpolator(I_LIQUID_SPRING);
        pillAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float f = ((Float) animation.getAnimatedValue()).floatValue();
                float x = startX + (targetX - startX) * f;
                setLensPosition(x);
            }
        });
        pillAnimator.start();
    }

    private void refreshStatus() {
        if (heroTitle == null) return;
        if (ConfigStore.en) {
            heroTitle.setText("模块已激活");
            if (hostModeTag != null) hostModeTag.setText("c2.xring.hevc (" + ConfigStore.fps + "Hz)");
        } else {
            heroTitle.setText("模块已暂停 (点击恢复)");
            if (hostModeTag != null) hostModeTag.setText("原生模式");
        }
    }

    private void broadcastSettings() {
        try {
            Intent i = new Intent("com.wa2000.photohdr.SETTINGS_CHANGED");
            i.setPackage("com.google.android.apps.photos");
            i.putExtra("en", ConfigStore.en);
            i.putExtra("fix_dv", ConfigStore.fixDv);
            i.putExtra("low_lat", ConfigStore.lowLat);
            i.putExtra("lock_fps", ConfigStore.lockFps);
            i.putExtra("fps", ConfigStore.fps);
            i.putExtra("vb", ConfigStore.vb);
            sendBroadcast(i);
        } catch (Throwable ignored) {}
    }

    private void applyThemeAndRebuild() {
        int keepPage = curPage;
        loadTheme();
        buildUi();
        setContentView(content);
        applySystemBars();
        showPage(keepPage, false);
        refreshStatus();
    }

    private LinearLayout glassCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedStroke(C_CARD_FILL, C_CARD_BORDER, dp(22f), dp(1f)));
        return card;
    }

    private LinearLayout standaloneSwitchCard(String title, String sub, boolean checked, final CompoundButton.OnCheckedChangeListener l) {
        LinearLayout card = glassCard();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18f), dp(15f), dp(18f), dp(15f));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label(title, 15.5f, C_ON_SURFACE, true));
        TextView tvSub = label(sub, 12.5f, C_ON_SURFACE_VARIANT, false);
        tvSub.setPadding(0, dp(3f), 0, 0);
        col.addView(tvSub);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, -2, 1f);
        colLp.rightMargin = dp(12f);
        card.addView(col, colLp);

        final Switch sw = new Switch(this);
        sw.setChecked(checked);
        tintSwitch(sw, checked);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean v) {
                tintSwitch(sw, v);
                if (l != null) l.onCheckedChanged(b, v);
            }
        });
        card.addView(sw);
        return card;
    }

    private LinearLayout numberedGuideCard(String numStr, String guideText) {
        LinearLayout card = glassCard();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16f), dp(14f), dp(16f), dp(14f));

        TextView badge = label(numStr, 13f, C_PRIMARY, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(roundedStroke(alpha(C_PRIMARY, 38), alpha(C_PRIMARY, 90), dp(14f), dp(1f)));
        card.addView(badge, new LinearLayout.LayoutParams(dp(28f), dp(28f)));

        TextView txt = label(guideText, 13.5f, C_ON_SURFACE, false);
        LinearLayout.LayoutParams txtLp = new LinearLayout.LayoutParams(0, -2, 1f);
        txtLp.leftMargin = dp(14f);
        card.addView(txt, txtLp);

        return card;
    }

    private void tintSwitch(Switch sw, boolean checked) {
        try {
            sw.setThumbTintList(ColorStateList.valueOf(checked ? 0xFFFFFFFF : C_ON_SURFACE_VARIANT));
            sw.setTrackTintList(ColorStateList.valueOf(checked ? 0xFF34C759 : C_SURFACE_HIGH)); // iOS / Video 2 vibrant green switch track
        } catch (Throwable ignored) {}
    }

    private TextView label(String text, float sizeSp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setTypeface(null, bold ? 1 : 0);
        return tv;
    }

    private GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    private GradientDrawable roundedStroke(int fill, int stroke, int radiusPx, int strokeW) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radiusPx);
        gd.setStroke(strokeW, stroke);
        return gd;
    }

    private View gap(int dpVal) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(dpVal)));
        return v;
    }

    private int dp(float val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    private int getScreenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private String getSelfVer() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (build " + pi.versionCode + ")";
        } catch (Throwable t) {
            return "2.3.0 (build 23)";
        }
    }

    private String getPhotosVer() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo("com.google.android.apps.photos", 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "已安装";
        }
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    private static int blend(int c1, int c2, float ratio) {
        float inv = 1f - ratio;
        int r = Math.round(((c1 >> 16) & 0xFF) * inv + ((c2 >> 16) & 0xFF) * ratio);
        int g = Math.round(((c1 >> 8) & 0xFF) * inv + ((c2 >> 8) & 0xFF) * ratio);
        int b = Math.round((c1 & 0xFF) * inv + (c2 & 0xFF) * ratio);
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}