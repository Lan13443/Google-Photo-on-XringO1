package com.wa2000.photohdr;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PhotoHDRFix 设置界面。严格遵循 Material 3 设计规范：
 * 色彩角色严格配对、M3 easing 动画、12dp 卡片、ripple 按压反馈、
 * surfaceContainer 层级、M3 导航栏。
 */
public class SettingsActivity extends Activity {

    // ============ M3 motion ============
    private static final Interpolator I_EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator I_DECEL = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final Interpolator I_ACCEL = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    // ============ palette (M3 color roles) ============
    private int C_PRIMARY, C_ON_PRIMARY, C_PRIMARY_CONTAINER, C_ON_PRIMARY_CONTAINER;
    private int C_SECONDARY_CONTAINER, C_ON_SECONDARY_CONTAINER;
    private int C_TERTIARY_CONTAINER, C_ON_TERTIARY_CONTAINER;
    private int C_SURFACE, C_SURFACE_LOW, C_SURFACE_CONTAINER, C_SURFACE_HIGH, C_SURFACE_HIGHEST;
    private int C_ON_SURFACE, C_ON_SURFACE_VARIANT, C_OUTLINE, C_ERROR;
    private int C_RIPPLE;
    private boolean night;

    // ============ views ============
    private FrameLayout content;
    private ScrollView homePage, settingsPage;
    private TextView bigTitle, bigSub, bigMeta;
    private View dot;
    private View dockPill;
    private TextView icHome, icSettings, lblHome, lblSettings;
    private TextView themeNameTxt;
    private TextView verPhotos;

    // ============ lifecycle ============
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ConfigStore.load(this);
        loadTheme();
        buildUi();
        setContentView(content);
        applySystemBars();
        showPage(0, false);
        try {
            registerSafe(statusReceiver, new IntentFilter("com.wa2000.photohdr.STATUS"));
        } catch (Throwable ignored) {
        }
        broadcastSettings();
        refreshStatus(false);
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(statusReceiver);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void registerSafe(BroadcastReceiver r, IntentFilter f) {
        try {
            java.lang.reflect.Method m = Context.class.getMethod("registerReceiver",
                    BroadcastReceiver.class, IntentFilter.class, int.class);
            m.invoke(this, r, f, 0x2);
        } catch (Throwable t) {
            registerReceiver(r, f);
        }
    }

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String st = i.getStringExtra("st");
            if (st != null) {
                ConfigStore.status = st;
                ConfigStore.statusTime = System.currentTimeMillis();
                ConfigStore.save(c);
                refreshStatus(true);
            }
        }
    };

    private void applySystemBars() {
        // 窗口背景与页面背景统一为 surface 色，消除状态栏区域的白边
        getWindow().setBackgroundDrawable(new ColorDrawable(C_SURFACE));
        getWindow().getDecorView().setBackgroundColor(C_SURFACE);
        getWindow().setStatusBarColor(C_SURFACE);
        getWindow().setNavigationBarColor(C_SURFACE_CONTAINER);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int flag = night ? 0 : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                c.setSystemBarsAppearance(flag, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }
    }

    // ============ theme ============
    private void loadTheme() {
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        String t = ConfigStore.theme == null ? "monet" : ConfigStore.theme;
        boolean monet = "monet".equals(t);

        // 中性色（所有主题共享 M3 baseline neutrals）
        if (night) {
            C_SURFACE = 0xFF141218;
            C_SURFACE_LOW = 0xFF1D1B20;
            C_SURFACE_CONTAINER = 0xFF211F26;
            C_SURFACE_HIGH = 0xFF2B2930;
            C_SURFACE_HIGHEST = 0xFF36343B;
            C_ON_SURFACE = 0xFFE6E0E9;
            C_ON_SURFACE_VARIANT = 0xFFCAC4D0;
            C_OUTLINE = 0xFF938F99;
            C_ERROR = 0xFFF2B8B5;
            C_RIPPLE = 0x1FFFFFFF;
        } else {
            C_SURFACE = 0xFFFEF7FF;
            C_SURFACE_LOW = 0xFFF7F2FA;
            C_SURFACE_CONTAINER = 0xFFF3EDF7;
            C_SURFACE_HIGH = 0xFFECE6F0;
            C_SURFACE_HIGHEST = 0xFFE6E0E9;
            C_ON_SURFACE = 0xFF1D1B20;
            C_ON_SURFACE_VARIANT = 0xFF49454F;
            C_OUTLINE = 0xFF79747E;
            C_ERROR = 0xFFB3261E;
            C_RIPPLE = 0x14000000;
        }

        if (monet && Build.VERSION.SDK_INT >= 31) {
            // 动态取色：accent1/2/3 = primary/secondary/tertiary
            C_PRIMARY = c(night ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600,
                    night ? 0xFFD0BCFF : 0xFF6750A4);
            C_ON_PRIMARY = night ? 0xFF381E72 : 0xFFFFFFFF;
            C_PRIMARY_CONTAINER = c(night ? android.R.color.system_accent1_700 : android.R.color.system_accent1_100,
                    night ? 0xFF4F378B : 0xFFEADDFF);
            C_ON_PRIMARY_CONTAINER = c(night ? android.R.color.system_accent1_100 : android.R.color.system_accent1_900,
                    night ? 0xFFEADDFF : 0xFF21005D);
            C_SECONDARY_CONTAINER = c(night ? android.R.color.system_accent2_700 : android.R.color.system_accent2_200,
                    night ? 0xFF4A4458 : 0xFFE8DEF8);
            C_ON_SECONDARY_CONTAINER = c(night ? android.R.color.system_accent2_100 : android.R.color.system_accent2_900,
                    night ? 0xFFE8DEF8 : 0xFF1D192B);
            C_TERTIARY_CONTAINER = c(night ? android.R.color.system_accent3_700 : android.R.color.system_accent3_200,
                    night ? 0xFF633B48 : 0xFFFFD8E4);
            C_ON_TERTIARY_CONTAINER = c(night ? android.R.color.system_accent3_100 : android.R.color.system_accent3_900,
                    night ? 0xFFFFD8E4 : 0xFF31111D);
        } else {
            int p, op, pc, opc, sc, osc, tc, otc;
            if ("blue".equals(t)) {
                if (night) {
                    p = 0xFFACC7FF; op = 0xFF0A2D6C; pc = 0xFF274273; opc = 0xFFDCE3FF;
                    sc = 0xFF424657; osc = 0xFFDDE1F9; tc = 0xFF573E52; otc = 0xFFFFD7F0;
                } else {
                    p = 0xFF3B5BA9; op = 0xFFFFFFFF; pc = 0xFFDCE3FF; opc = 0xFF00174B;
                    sc = 0xFFDDE1F9; osc = 0xFF161B2C; tc = 0xFFFFD7F0; otc = 0xFF2B1230;
                }
            } else if ("green".equals(t)) {
                if (night) {
                    p = 0xFF9CD67D; op = 0xFF113800; pc = 0xFF21500C; opc = 0xFFB9F193;
                    sc = 0xFF3F4A36; osc = 0xFFD8E7CB; tc = 0xFF1E4D50; otc = 0xFFBCEBEE;
                } else {
                    p = 0xFF386A20; op = 0xFFFFFFFF; pc = 0xFFB9F193; opc = 0xFF0B2000;
                    sc = 0xFFD8E7CB; osc = 0xFF131F0D; tc = 0xFFBCEBEE; otc = 0xFF002021;
                }
            } else if ("rose".equals(t)) {
                if (night) {
                    p = 0xFFFFB1C8; op = 0xFF5F1134; pc = 0xFF633B48; opc = 0xFFFFD9E2;
                    sc = 0xFF5B3F46; osc = 0xFFFFD9E2; tc = 0xFF613F1F; otc = 0xFFFFDCC1;
                } else {
                    p = 0xFF9C254D; op = 0xFFFFFFFF; pc = 0xFFFFD9E2; opc = 0xFF3E001D;
                    sc = 0xFFF6DDE2; osc = 0xFF2B151C; tc = 0xFFFFDCC1; otc = 0xFF2E1500;
                }
            } else { // baseline（官方全套）
                if (night) {
                    p = 0xFFD0BCFF; op = 0xFF381E72; pc = 0xFF4F378B; opc = 0xFFEADDFF;
                    sc = 0xFF4A4458; osc = 0xFFE8DEF8; tc = 0xFF633B48; otc = 0xFFFFD8E4;
                } else {
                    p = 0xFF6750A4; op = 0xFFFFFFFF; pc = 0xFFEADDFF; opc = 0xFF21005D;
                    sc = 0xFFE8DEF8; osc = 0xFF1D192B; tc = 0xFFFFD8E4; otc = 0xFF31111D;
                }
            }
            C_PRIMARY = p;
            C_ON_PRIMARY = op;
            C_PRIMARY_CONTAINER = pc;
            C_ON_PRIMARY_CONTAINER = opc;
            C_SECONDARY_CONTAINER = sc;
            C_ON_SECONDARY_CONTAINER = osc;
            C_TERTIARY_CONTAINER = tc;
            C_ON_TERTIARY_CONTAINER = otc;
        }
    }

    private int c(int resId, int fallback) {
        try {
            int v = getColor(resId);
            return v == 0 ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ============ ui ============
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(C_SURFACE);

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        homePage = buildHomePage();
        settingsPage = buildSettingsPage();
        content.addView(homePage, new FrameLayout.LayoutParams(-1, -1));
        content.addView(settingsPage, new FrameLayout.LayoutParams(-1, -1));
        settingsPage.setVisibility(View.GONE);

        root.addView(buildNavBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        this.content = root;
    }

    // ---- home page ----
    private ScrollView buildHomePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, dp(96));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -1));

        // 大状态卡：filled card（surfaceContainerHighest）
        LinearLayout big = m3Card(C_SURFACE_HIGHEST, false, true);
        big.setGravity(Gravity.CENTER_HORIZONTAL);
        big.setPadding(dp(24), dp(32), dp(24), dp(32));
        dot = new View(this);
        dot.setBackground(rounded(C_ON_SURFACE_VARIANT, dp(6)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(12), dp(12));
        dlp.bottomMargin = dp(16);
        big.addView(dot, dlp);

        bigTitle = new TextView(this);
        bigTitle.setTextSize(22);
        bigTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bigTitle.setTextColor(C_ON_SURFACE);
        bigTitle.setGravity(Gravity.CENTER);
        big.addView(bigTitle);

        bigSub = new TextView(this);
        bigSub.setTextSize(14);
        bigSub.setTextColor(C_ON_SURFACE_VARIANT);
        bigSub.setGravity(Gravity.CENTER);
        bigSub.setLineSpacing(0, 1.3f);
        bigSub.setPadding(dp(8), dp(8), dp(8), dp(8));
        big.addView(bigSub);

        bigMeta = new TextView(this);
        bigMeta.setTextSize(12);
        bigMeta.setTextColor(C_ON_SURFACE_VARIANT);
        bigMeta.setGravity(Gravity.CENTER);
        bigMeta.setPadding(0, dp(12), 0, 0);
        big.addView(bigMeta);

        big.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendRefresh(); }
        });
        root.addView(big);

        root.addView(gap(dp(8)));

        // 版本卡：outlined card（surface + outline）
        LinearLayout verCard = m3Card(0, true, false);
        verCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        verCard.addView(label("Google 相册"));
        verPhotos = new TextView(this);
        verPhotos.setTextSize(14);
        verPhotos.setTextColor(C_ON_SURFACE);
        verPhotos.setTypeface(Typeface.DEFAULT_BOLD);
        verCard.addView(verPhotos, new LinearLayout.LayoutParams(-1, -2));
        verCard.addView(gap(dp(12)));
        verCard.addView(label("模块版本"));
        TextView verModule = new TextView(this);
        verModule.setTextSize(14);
        verModule.setTextColor(C_ON_SURFACE);
        verModule.setTypeface(Typeface.DEFAULT_BOLD);
        verModule.setText("2.0.0 · build 20");
        verCard.addView(verModule, new LinearLayout.LayoutParams(-1, -2));
        root.addView(verCard);

        root.addView(gap(dp(8)));

        root.addView(gap(dp(4)));

        // 刷新状态：小型文字选项条（text button 风格）
        LinearLayout ref = new LinearLayout(this);
        ref.setOrientation(LinearLayout.HORIZONTAL);
        ref.setGravity(Gravity.CENTER);
        ref.setPadding(dp(12), dp(10), dp(12), dp(10));
        ref.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), null, null));
        TextView t1 = new TextView(this);
        t1.setText("刷新状态");
        t1.setTextSize(14);
        t1.setTextColor(C_PRIMARY);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setGravity(Gravity.CENTER);
        ref.addView(t1);
        ref.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { sendRefresh(); }
        });
        root.addView(ref);

        return scroll;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(C_ON_SURFACE_VARIANT);
        return t;
    }

    // ---- settings page ----
    private ScrollView buildSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(24), pad, dp(96));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(C_ON_SURFACE);
        root.addView(title);
        root.addView(gap(dp(16)));

        // 配色主题
        LinearLayout themeCard = m3Card(C_SURFACE_LOW, false, false);
        themeCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView th = new TextView(this);
        th.setText("配色主题");
        th.setTextSize(14);
        th.setTypeface(Typeface.DEFAULT_BOLD);
        th.setTextColor(C_ON_SURFACE);
        themeCard.addView(th);
        themeNameTxt = new TextView(this);
        themeNameTxt.setTextSize(12);
        themeNameTxt.setTextColor(C_ON_SURFACE_VARIANT);
        themeNameTxt.setPadding(0, dp(2), 0, dp(14));
        themeCard.addView(themeNameTxt);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        final String[] ids = new String[]{"monet", "baseline", "blue", "green", "rose"};
        for (String id : ids) {
            dots.addView(themeDot(id));
        }
        hs.addView(dots, new HorizontalScrollView.LayoutParams(-2, -1));
        themeCard.addView(hs);
        root.addView(themeCard);

        root.addView(gap(dp(8)));

        // 修复干预
        LinearLayout cardFix = m3Card(C_SURFACE_LOW, false, false);
        final Switch swEnable = new Switch(this);
        swEnable.setOnCheckedChangeListener(null);
        swEnable.setChecked(ConfigStore.en);
        swEnable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean ck) {
                ConfigStore.en = ck;
                ConfigStore.save(SettingsActivity.this);
                broadcastSettings();
                refreshStatus(false);
            }
        });
        tintSwitch(swEnable);
        cardFix.addView(switchRow("修复干预", "改写丢帧决策，保持画面节奏", swEnable));
        root.addView(cardFix);

        root.addView(gap(dp(8)));

        // 渲染节奏
        LinearLayout fpsCard = m3Card(C_SURFACE_LOW, false, false);
        fpsCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView ft = new TextView(this);
        ft.setText("渲染节奏");
        ft.setTextSize(14);
        ft.setTypeface(Typeface.DEFAULT_BOLD);
        ft.setTextColor(C_ON_SURFACE);
        fpsCard.addView(ft);
        TextView fd = new TextView(this);
        fd.setText("帧间隔基准，默认 60 fps");
        fd.setTextSize(12);
        fd.setTextColor(C_ON_SURFACE_VARIANT);
        fd.setPadding(0, dp(2), 0, dp(14));
        fpsCard.addView(fd);
        fpsCard.addView(segmented(new String[]{"60 fps", "30 fps"},
                "60".equals(ConfigStore.fps) ? 0 : 1,
                new SegListener() {
                    public void onPick(int idx) {
                        ConfigStore.fps = idx == 1 ? "30" : "60";
                        ConfigStore.save(SettingsActivity.this);
                        broadcastSettings();
                    }
                }));
        root.addView(fpsCard);

        root.addView(gap(dp(8)));

        // 详细日志
        LinearLayout logCard = m3Card(C_SURFACE_LOW, false, false);
        final Switch swVerbose = new Switch(this);
        swVerbose.setOnCheckedChangeListener(null);
        swVerbose.setChecked(ConfigStore.vb);
        swVerbose.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean ck) {
                ConfigStore.vb = ck;
                ConfigStore.save(SettingsActivity.this);
                broadcastSettings();
            }
        });
        tintSwitch(swVerbose);
        logCard.addView(switchRow("详细日志", "写入 logcat，排查时开启", swVerbose));
        root.addView(logCard);

        root.addView(gap(dp(16)));

        // 说明（body small，非卡片）
        TextView nt = new TextView(this);
        nt.setTextSize(12);
        nt.setTextColor(C_ON_SURFACE_VARIANT);
        nt.setLineSpacing(0, 1.4f);
        nt.setPadding(dp(4), 0, dp(4), 0);
        nt.setText("生效条件：root + LSPosed（Zygisk），作用范围选择 Google 相册。\n"
                + "修改设置后，相册重新开始播放即生效，无需重启相册。");
        root.addView(nt);

        return scroll;
    }

    private interface SegListener {
        void onPick(int idx);
    }

    private View segmented(final String[] labels, int sel, final SegListener cb) {
        final FrameLayout wrap = new FrameLayout(this);
        wrap.setBackground(roundedStroke(C_OUTLINE, dp(20), 1));
        wrap.setPadding(dp(4), dp(4), dp(4), dp(4));

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextSize(14);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setGravity(Gravity.CENTER);
            tv.setHeight(dp(32));
            tv.setPadding(dp(12), 0, dp(12), 0);
            tv.setTextColor(idx == sel ? C_ON_SECONDARY_CONTAINER : C_ON_SURFACE);
            tv.setBackground(idx == sel ? rounded(C_SECONDARY_CONTAINER, dp(16)) : null);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    cb.onPick(idx);
                    for (int j = 0; j < row.getChildCount(); j++) {
                        TextView o = (TextView) row.getChildAt(j);
                        boolean sel2 = j == idx;
                        o.setTextColor(sel2 ? C_ON_SECONDARY_CONTAINER : C_ON_SURFACE);
                        o.setBackground(sel2 ? rounded(C_SECONDARY_CONTAINER, dp(16)) : null);
                    }
                }
            });
            row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        wrap.addView(row, new FrameLayout.LayoutParams(-1, -2));
        return wrap;
    }

    private View themeDot(final String id) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(dp(10), 0, dp(10), 0);

        final FrameLayout ring = new FrameLayout(this);
        int color = themePrimaryOf(id);
        final View inner = new View(this);
        inner.setBackground(rounded(color, dp(12)));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        ring.addView(inner, ilp);

        boolean sel = id.equals(ConfigStore.theme == null ? "monet" : ConfigStore.theme);
        ring.setBackground(sel ? roundedStroke(C_PRIMARY, dp(18), dp(2)) : null);
        cell.addView(ring, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView name = new TextView(this);
        name.setText(themeNameOf(id));
        name.setTextSize(11);
        name.setTextColor(sel ? C_PRIMARY : C_ON_SURFACE_VARIANT);
        name.setPadding(0, dp(6), 0, 0);
        cell.addView(name);

        cell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (id.equals(ConfigStore.theme)) return;
                ConfigStore.theme = id;
                ConfigStore.save(SettingsActivity.this);
                recreate();
            }
        });
        return cell;
    }

    private int themePrimaryOf(String id) {
        boolean n = night;
        if ("blue".equals(id)) return n ? 0xFFACC7FF : 0xFF3B5BA9;
        if ("green".equals(id)) return n ? 0xFF9CD67D : 0xFF386A20;
        if ("rose".equals(id)) return n ? 0xFFFFB1C8 : 0xFF9C254D;
        if ("baseline".equals(id)) return n ? 0xFFD0BCFF : 0xFF6750A4;
        return C_PRIMARY; // monet
    }

    private String themeNameOf(String id) {
        if ("baseline".equals(id)) return "经典紫";
        if ("blue".equals(id)) return "海岸蓝";
        if ("green".equals(id)) return "苔原绿";
        if ("rose".equals(id)) return "玫瑰粉";
        return "莫奈动态";
    }

    // ---- navigation bar (M3) ----
    private View buildNavBar() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(C_SURFACE_CONTAINER);
        wrap.setPadding(dp(8), dp(12), dp(8), dp(12));

        dockPill = new View(this);
        dockPill.setBackground(rounded(C_SECONDARY_CONTAINER, dp(16)));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(64), dp(32), Gravity.TOP | Gravity.LEFT);
        plp.topMargin = dp(2);
        wrap.addView(dockPill, plp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(row, new FrameLayout.LayoutParams(-1, -1));

        addTab(row, "\u2302", "主页", 0);
        addTab(row, "\u2699", "设置", 1);

        wrap.post(new Runnable() {
            public void run() { movePill(0, false); }
        });
        return wrap;
    }

    private void addTab(LinearLayout row, String icon, String label, final int idx) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER_HORIZONTAL);
        tab.setPadding(0, dp(2), 0, dp(2));
        tab.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), null, null));

        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(24);
        ic.setTypeface(Typeface.DEFAULT_BOLD);
        ic.setTextColor(C_ON_SURFACE_VARIANT);
        ic.setGravity(Gravity.CENTER);
        tab.addView(ic);

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(12);
        lb.setTextColor(C_ON_SURFACE_VARIANT);
        lb.setGravity(Gravity.CENTER);
        lb.setPadding(0, dp(2), 0, 0);
        tab.addView(lb);

        if (idx == 0) {
            icHome = ic;
            lblHome = lb;
        } else {
            icSettings = ic;
            lblSettings = lb;
        }

        tab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPage(idx, true); }
        });
        row.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
    }

    private void movePill(int idx, boolean animate) {
        float cell = (getScreenW() - dp(16)) / 2f;
        float cx = cell * idx + (cell - dp(64)) / 2f;
        if (animate) {
            dockPill.animate().translationX(cx).setDuration(300).setInterpolator(I_EMPHASIZED).start();
        } else {
            dockPill.setTranslationX(cx);
        }
        boolean hi = idx == 0;
        lblHome.setTextColor(hi ? C_ON_SURFACE : C_ON_SURFACE_VARIANT);
        icHome.setTextColor(hi ? C_ON_SECONDARY_CONTAINER : C_ON_SURFACE_VARIANT);
        lblSettings.setTextColor(hi ? C_ON_SURFACE_VARIANT : C_ON_SURFACE);
        icSettings.setTextColor(hi ? C_ON_SURFACE_VARIANT : C_ON_SECONDARY_CONTAINER);
    }

    private void showPage(int idx, boolean animate) {
        boolean home = idx == 0;
        ScrollView in = home ? homePage : settingsPage;
        ScrollView out = home ? settingsPage : homePage;
        out.setVisibility(View.GONE);
        in.setVisibility(View.VISIBLE);
        movePill(idx, animate);
        if (animate) {
            // M3：进场 = emphasized decelerate 400ms
            in.setAlpha(0f);
            in.setTranslationY(dp(24));
            in.animate().alpha(1f).translationY(0f)
                    .setDuration(400).setInterpolator(I_DECEL).start();
        }
        if (home) refreshStatus(false);
    }

    // ---- status ----
    private void sendRefresh() {
        dot.animate().scaleX(1.35f).scaleY(1.35f).setDuration(120)
                .setInterpolator(I_EMPHASIZED)
                .withEndAction(new Runnable() {
                    public void run() {
                        dot.animate().scaleX(1f).scaleY(1f).setDuration(240)
                                .setInterpolator(I_DECEL).start();
                    }
                }).start();
        broadcastSettings();
        refreshStatus(false);
    }

    private void refreshStatus(boolean got) {
        String st = ConfigStore.status;
        long t = ConfigStore.statusTime;
        long ageMin = (System.currentTimeMillis() - t) / 60000;
        boolean fresh = st != null && ageMin < 60;
        boolean enabled = ConfigStore.en;
        String when = "";
        if (t > 0) {
            try {
                when = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(t));
            } catch (Throwable ignored) {
            }
        }
        if (fresh) {
            dot.setBackground(rounded(enabled ? C_PRIMARY : C_OUTLINE, dp(6)));
            bigTitle.setText(enabled ? "已注入 · 运行中" : "已注入 · 已停用");
            bigSub.setText(enabled ? "修复干预已启用，正在保护相册播放节奏"
                    : "模块已注入，干预当前处于关闭状态");
            bigMeta.setText("最近同步 " + when);
        } else {
            dot.setBackground(rounded(C_ON_SURFACE_VARIANT, dp(6)));
            bigTitle.setText("等待注入");
            bigSub.setText("打开一次 Google 相册并播放视频后，\n返回本页即可看到实时状态");
            bigMeta.setText(st == null ? "" : "上次同步 " + when);
        }
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo("com.google.android.apps.photos", 0);
            verPhotos.setText(pi.versionName + " (" + pi.versionCode + ")");
        } catch (Throwable e) {
            verPhotos.setText("未安装");
        }
        if (themeNameTxt != null) {
            themeNameTxt.setText("当前：" + themeNameOf(ConfigStore.theme == null ? "monet" : ConfigStore.theme)
                    + (night ? " · 深色" : " · 浅色"));
        }
    }

    private void broadcastSettings() {
        try {
            boolean en = ConfigStore.en;
            String fps = ConfigStore.fps;
            boolean vb = ConfigStore.vb;
            android.util.Log.i("PhotoHDRFix", "bcast en=" + en + " fps=" + fps + " vb=" + vb);
            Intent i = new Intent("com.wa2000.photohdr.SETTINGS_CHANGED");
            i.setPackage("com.google.android.apps.photos");
            i.putExtra("en", en);
            i.putExtra("fps", fps);
            i.putExtra("vb", vb);
            sendBroadcast(i);
        } catch (Throwable t) {
            android.util.Log.w("PhotoHDRFix", "bcast FAIL: " + t);
        }
    }

    // ============ helpers ============
    /**
     * M3 卡片。
     * @param bgColor 填充色；0 表示 outlined（surface + outline 描边）
     * @param outline 是否描边
     * @param ripple  是否带按压涟漪（可点击）
     */
    private LinearLayout m3Card(int bgColor, boolean outline, boolean ripple) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = rounded(bgColor == 0 ? C_SURFACE : bgColor, dp(12));
        if (outline || bgColor == 0) g.setStroke(dp(1), C_OUTLINE);
        if (ripple) {
            c.setBackground(new RippleDrawable(ColorStateList.valueOf(C_RIPPLE), g, g));
        } else {
            c.setBackground(g);
        }
        return c;
    }

    private LinearLayout switchRow(String t, String d, Switch sw) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(16);
        tv.setTextColor(C_ON_SURFACE);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(tv);
        TextView dv = new TextView(this);
        dv.setText(d);
        dv.setTextSize(14);
        dv.setTextColor(C_ON_SURFACE_VARIANT);
        texts.addView(dv);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(sw);
        return row;
    }

    private void tintSwitch(Switch sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] thumb = new int[]{C_ON_PRIMARY, C_OUTLINE};
        int[] track = new int[]{C_PRIMARY, C_SURFACE_HIGH};
        sw.setThumbTintList(new ColorStateList(states, thumb));
        sw.setTrackTintList(new ColorStateList(states, track));
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable roundedStroke(int color, int radius, int w) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setStroke(dp(w), color);
        g.setCornerRadius(radius);
        return g;
    }

    private int alpha(int color, float a) {
        int aa = Math.round(255 * a);
        if (aa > 255) aa = 255;
        if (aa < 0) aa = 0;
        return (aa << 24) | (color & 0xFFFFFF);
    }

    private int getScreenW() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}