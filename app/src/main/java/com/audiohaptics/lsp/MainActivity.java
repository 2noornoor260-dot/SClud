package com.audiohaptics.lsp;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    static final String PERM = "android.permission.WRITE_SECURE_SETTINGS";
    static final float[][] PRESETS = {{0.80f, 1.4f}, {0.60f, 1.8f}, {0.40f, 2.4f}};   // softness, click length
    static final String[] PRESET_NAMES = {"هادئ", "متوازن", "قوي"};

    int BG, CARD, TXT, SUB, ACC, GOOD, WARN, BAD;
    final Handler ui = new Handler(Looper.getMainLooper());
    Vibrator vib;
    LinearLayout root;
    Map<String, String> cfg = new LinkedHashMap<>();
    Map<String, String> report = new HashMap<>();
    final Map<String, String> pending = new HashMap<>();
    final ArrayList<Object[]> toggles = new ArrayList<>();
    final Button[] presetBtns = new Button[3];
    TextView heroTitle, heroSub, calText, reportView;
    Button powerBtn, calBtn, grantBtn;
    LinearLayout grantCard, advBody;
    boolean hasReport;

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        initTheme();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(30));
        sv.addView(root, new ViewGroup.LayoutParams(MATCH, WRAP));
        setContentView(sv);
        cfg = Config.parse(Config.read(this));
        buildUi();
        if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) grantAsync();
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); flushNow(); }

    final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000); }
    };

    // ------------------------------------------------------------------ theme & helpers

    void initTheme() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        BG = dark ? 0xFF0E1014 : 0xFFF2F3F7;
        CARD = dark ? 0xFF1A1D25 : 0xFFFFFFFF;
        TXT = dark ? 0xFFEEF0F6 : 0xFF1A1D25;
        SUB = dark ? 0xFF9AA1B3 : 0xFF666C7C;
        ACC = 0xFF6C8CFF;
        GOOD = 0xFF2FBF86;
        WARN = 0xFFF0A23B;
        BAD = 0xFFE5594F;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (!dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    static int tint(int color, float alpha) { return (color & 0x00FFFFFF) | (((int) (alpha * 255)) << 24); }

    LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    TextView tv(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    LinearLayout card(String title, String subtitle) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(16), dp(18), dp(18));
        c.setBackground(round(CARD, 22));
        if (title != null) c.addView(tv(title, 17, TXT, true));
        if (subtitle != null) c.addView(tv(subtitle, 13, SUB, false), lp(MATCH, WRAP, 0, 2, 0, 6));
        root.addView(c, lp(MATCH, WRAP, 0, 12, 0, 0));
        return c;
    }

    Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setBackground(round(bg, 14));
        b.setStateListAnimator(null);
        return b;
    }

    boolean flag(String key, boolean def) {
        String v = cfg.get(key);
        return v == null ? def : !v.equals("0");
    }

    float num(String key, float def) {
        try { String v = cfg.get(key); return v == null ? def : Float.parseFloat(v); } catch (Throwable t) { return def; }
    }

    // ------------------------------------------------------------------ config writing (debounced)

    final Runnable flush = new Runnable() { @Override public void run() { flushNow(); } };

    void put(String k, String v) {
        cfg.put(k, v);
        pending.put(k, v);
        ui.removeCallbacks(flush);
        ui.postDelayed(flush, 150);
    }

    void putNow(String k, String v) {
        put(k, v);
        flushNow();
        refresh();
    }

    void flushNow() {
        if (pending.isEmpty()) return;
        Map<String, String> m = new HashMap<>(pending);
        pending.clear();
        if (!Config.write(this, m) && grantCard != null) grantCard.setVisibility(View.VISIBLE);
    }

    void grantAsync() {
        new Thread(() -> {
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "pm grant " + getPackageName() + " " + PERM});
                pr.waitFor();
            } catch (Throwable ignored) { }
            ui.post(this::refresh);
        }).start();
    }

    // ------------------------------------------------------------------ UI

    void buildUi() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.addView(tv("Audio Haptics", 28, TXT, true));
        head.addView(tv("اهتزاز حقيقي عبر السماعات الأربع", 14, SUB, false), lp(MATCH, WRAP, 0, 2, 0, 0));
        root.addView(head);

        // ---- hero
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));
        hero.setBackground(round(CARD, 26));
        heroTitle = tv("", 22, TXT, true);
        heroSub = tv("", 13, SUB, false);
        hero.addView(heroTitle);
        hero.addView(heroSub, lp(MATCH, WRAP, 0, 4, 0, 14));
        powerBtn = button("", GOOD, Color.WHITE);
        powerBtn.setTextSize(17);
        powerBtn.setTypeface(Typeface.DEFAULT_BOLD);
        powerBtn.setOnClickListener(v -> putNow("e", flag("e", true) ? "0" : "1"));
        hero.addView(powerBtn, lp(MATCH, dp(58), 0, 0, 0, 0));
        root.addView(hero, lp(MATCH, WRAP, 0, 16, 0, 0));

        grantCard = card("الصلاحية مطلوبة", "لحفظ الإعدادات يحتاج التطبيق إلى صلاحية مرة واحدة (يتطلب root).");
        grantBtn = button("منح الصلاحية الآن", ACC, Color.WHITE);
        grantBtn.setOnClickListener(v -> grantAsync());
        grantCard.addView(grantBtn, lp(MATCH, dp(50), 0, 6, 0, 0));
        grantCard.setVisibility(View.GONE);

        // ---- strength
        LinearLayout c1 = card("القوة", "تحكم في مستوى الإحساس");
        sliderRow(c1, "اللمس والكتابة والنقرات", "g", 0, 200, 1.0f);
        sliderRow(c1, "التنبيهات والاهتزاز المتواصل", "a", 0, 200, 1.0f);
        c1.addView(tv("طابع الإحساس", 14, SUB, false), lp(MATCH, WRAP, 0, 12, 0, 6));
        LinearLayout pr = new LinearLayout(this);
        pr.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button pb = button(PRESET_NAMES[i], tint(SUB, 0.12f), TXT);
            pb.setOnClickListener(v -> {
                put("s", String.format(Locale.US, "%.2f", PRESETS[idx][0]));
                put("u", String.format(Locale.US, "%.2f", PRESETS[idx][1]));
                flushNow();
                refresh();
            });
            presetBtns[i] = pb;
            pr.addView(pb, lp(0, dp(46), i == 0 ? 0 : 6, 0, 0, 0));
            ((LinearLayout.LayoutParams) pb.getLayoutParams()).weight = 1;
        }
        c1.addView(pr);

        // ---- automatic behaviour
        LinearLayout c2 = card("التشغيل التلقائي", "متى يتوقف الاهتزاز تلقائياً");
        toggleRow(c2, "إيقاف في الوضع الصامت", "m", true);
        toggleRow(c2, "إيقاف عند توصيل سماعات أو بلوتوث (يمنع تداخل الصوت)", "xo", true);

        // ---- auto tune
        LinearLayout c3 = card("الضبط الذكي", "يقيس اهتزاز الجهاز بمستشعر الحركة ويختار أفضل إعداد للسماعات الأربع تلقائياً. ضع الجهاز على طاولة ثابتة ولا تلمسه (حوالي 25 ثانية).");
        calBtn = button("ابدأ الضبط التلقائي", ACC, Color.WHITE);
        calBtn.setOnClickListener(v -> putNow("c", String.valueOf(System.currentTimeMillis())));
        c3.addView(calBtn, lp(MATCH, dp(50), 0, 4, 0, 0));
        calText = tv("", 13, SUB, false);
        c3.addView(calText, lp(MATCH, WRAP, 0, 8, 0, 0));

        // ---- test
        LinearLayout c4 = card("جرّب الإحساس", null);
        String[] names = {"Tick", "Click", "Heavy", "Double", "Thud", "Buzz"};
        VibrationEffect[] fx = {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK),
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
                thudEffect(),
                VibrationEffect.createOneShot(800, 255)};
        for (int r = 0; r < 3; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int k = 0; k < 2; k++) {
                final VibrationEffect e = fx[r * 2 + k];
                Button tb = button(names[r * 2 + k], tint(ACC, 0.16f), TXT);
                tb.setOnClickListener(v -> { if (vib != null) vib.vibrate(e); });
                LinearLayout.LayoutParams p = lp(0, dp(48), k == 0 ? 0 : 8, 8, 0, 0);
                p.weight = 1;
                row.addView(tb, p);
            }
            c4.addView(row);
        }
        Button comp = button("تركيبة", tint(ACC, 0.16f), TXT);
        comp.setOnClickListener(v -> { if (vib != null) vib.vibrate(VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.8f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 40)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 60).compose()); });
        c4.addView(comp, lp(MATCH, dp(48), 0, 8, 0, 0));
        EditText et = new EditText(this);
        et.setHint("اكتب هنا لتجربة اهتزاز لوحة المفاتيح");
        et.setHintTextColor(SUB);
        et.setTextColor(TXT);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setBackground(round(tint(SUB, 0.10f), 14));
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        c4.addView(et, lp(MATCH, WRAP, 0, 10, 0, 0));

        // ---- advanced (collapsed)
        LinearLayout c5 = card(null, null);
        final Button advHead = button("إعدادات متقدمة  ▾", tint(SUB, 0.10f), TXT);
        c5.addView(advHead, lp(MATCH, dp(48), 0, 0, 0, 0));
        advBody = new LinearLayout(this);
        advBody.setOrientation(LinearLayout.VERTICAL);
        advBody.setVisibility(View.GONE);
        c5.addView(advBody);
        advHead.setOnClickListener(v -> {
            boolean open = advBody.getVisibility() == View.VISIBLE;
            advBody.setVisibility(open ? View.GONE : View.VISIBLE);
            advHead.setText(open ? "إعدادات متقدمة  ▾" : "إعدادات متقدمة  ▴");
        });
        toggleRow(advBody, "الوضع السريع (ستيريو): أقل تأخيراً وأضعف", "f", false);
        sliderRow(advBody, "نعومة الصوت (أعلى = أقل حدّة)", "s", 0, 100, 0.6f);
        sliderRow(advBody, "طول النقرة (أعلى = إحساس أقوى)", "u", 50, 350, 1.8f);
        Button reset = button("إعادة الضبط الافتراضي", tint(BAD, 0.16f), BAD);
        reset.setOnClickListener(v -> {
            String[][] d = {{"e", "1"}, {"m", "1"}, {"xo", "1"}, {"f", "0"}, {"g", "1.00"}, {"a", "1.00"}, {"t", "1.00"},
                    {"s", "0.60"}, {"u", "1.80"}, {"qs", "1.0,-1.0,1.0,-1.0"}};
            for (String[] kv : d) put(kv[0], kv[1]);
            flushNow();
            recreate();
        });
        advBody.addView(reset, lp(MATCH, dp(48), 0, 12, 0, 0));
        reportView = tv("", 11, SUB, false);
        reportView.setTypeface(Typeface.MONOSPACE);
        advBody.addView(reportView, lp(MATCH, WRAP, 0, 12, 0, 0));

        root.addView(tv("الإصدار 1.0 · يعمل داخل النظام عبر LSPosed", 12, SUB, false), lp(WRAP, WRAP, 0, 18, 0, 0));
    }

    void sliderRow(LinearLayout parent, String title, final String key, final int min, final int max, float def) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = tv(title, 14, TXT, false);
        final TextView val = tv("", 14, ACC, true);
        head.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(val);
        parent.addView(head, lp(MATCH, WRAP, 0, 12, 0, 0));
        final float cur = num(key, def);
        val.setText(Math.round(cur * 100) + "%");
        SeekBar s = new SeekBar(this);
        s.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        s.setMax(max - min);
        s.setProgress(Math.max(0, Math.min(max - min, Math.round(cur * 100) - min)));
        ColorStateList cs = ColorStateList.valueOf(ACC);
        s.setProgressTintList(cs);
        s.setThumbTintList(cs);
        s.setProgressBackgroundTintList(ColorStateList.valueOf(tint(SUB, 0.35f)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (!user) return;
                int pct = p + min;
                val.setText(pct + "%");
                put(key, String.format(Locale.US, "%.2f", pct / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { flushNow(); }
        });
        parent.addView(s, lp(MATCH, WRAP, 0, 2, 0, 0));
    }

    void toggleRow(LinearLayout parent, final String label, final String key, final boolean def) {
        Button b = button("", tint(SUB, 0.10f), TXT);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setOnClickListener(v -> putNow(key, flag(key, def) ? "0" : "1"));
        toggles.add(new Object[]{b, label, key, def});
        parent.addView(b, lp(MATCH, WRAP, 0, 8, 0, 0));
        paintToggle(b, label, flag(key, def));
    }

    void paintToggle(Button b, String label, boolean on) {
        b.setText(label + "\n" + (on ? "● مفعّل" : "○ متوقف"));
        b.setTextColor(on ? TXT : SUB);
        b.setBackground(round(on ? tint(ACC, 0.20f) : tint(SUB, 0.10f), 14));
    }

    // ------------------------------------------------------------------ live state

    void refresh() {
        cfg = Config.parse(Config.read(this));
        for (Map.Entry<String, String> e : pending.entrySet()) cfg.put(e.getKey(), e.getValue());
        String r = null;
        try { r = Settings.Global.getString(getContentResolver(), Status.KEY); } catch (Throwable ignored) { }
        report = new HashMap<>();
        if (r != null) for (String line : r.split("\n")) {
            int i = line.indexOf(": ");
            if (i > 0) report.put(line.substring(0, i), line.substring(i + 2));
        }
        hasReport = r != null;

        boolean on = flag("e", true);
        String mode = report.get("0.mode");
        boolean has = vib != null && vib.hasVibrator();
        String audio = report.get("6.audio");
        String sub = audio == null ? "" : (audio.contains("out=QUAD") ? "إخراج رباعي · أربع سماعات" : "إخراج ستيريو");
        if (!hasReport && !has) {
            heroTitle.setText("الوحدة غير نشطة");
            heroTitle.setTextColor(BAD);
            heroSub.setText("فعّل الوحدة في LSPosed وحدّد «إطار النظام» (System Framework) ثم أعد تشغيل الجهاز.");
        } else if (!on) {
            heroTitle.setText("متوقف");
            heroTitle.setTextColor(SUB);
            heroSub.setText("لا يصدر أي اهتزاز في أي وضع.");
        } else if ("silent".equals(mode)) {
            heroTitle.setText("متوقف مؤقتاً: الوضع الصامت");
            heroTitle.setTextColor(WARN);
            heroSub.setText("يعود تلقائياً عند الخروج من الوضع الصامت.");
        } else if ("external-audio".equals(mode)) {
            heroTitle.setText("متوقف مؤقتاً: سماعات متصلة");
            heroTitle.setTextColor(WARN);
            heroSub.setText("لتجنب تداخل توجيه الصوت مع الأيربودز. يعود تلقائياً عند فصلها.");
        } else if ("call".equals(mode)) {
            heroTitle.setText("متوقف مؤقتاً: مكالمة");
            heroTitle.setTextColor(WARN);
            heroSub.setText("يعود تلقائياً بعد انتهاء المكالمة.");
        } else {
            heroTitle.setText("يعمل");
            heroTitle.setTextColor(GOOD);
            heroSub.setText(sub);
        }
        powerBtn.setText(on ? "إيقاف الاهتزاز" : "تشغيل الاهتزاز");
        powerBtn.setBackground(round(on ? tint(BAD, 0.9f) : GOOD, 16));

        for (Object[] t : toggles) paintToggle((Button) t[0], (String) t[1], flag((String) t[2], (Boolean) t[3]));
        float s = num("s", 0.6f), u = num("u", 1.8f);
        for (int i = 0; i < 3; i++) {
            boolean sel = Math.abs(s - PRESETS[i][0]) < 0.06f && Math.abs(u - PRESETS[i][1]) < 0.15f;
            presetBtns[i].setBackground(round(sel ? ACC : tint(SUB, 0.12f), 14));
            presetBtns[i].setTextColor(sel ? Color.WHITE : TXT);
        }

        String cal = report.get("12.cal");
        boolean running = cal != null && (cal.startsWith("1/3") || cal.startsWith("2/3") || cal.startsWith("3/3"));
        calText.setText(cal == null ? "" : cal.replace("DONE:", "تم:").replace("ERROR:", "خطأ:").replace("ABORT:", "لم يبدأ:"));
        calText.setTextColor(cal != null && cal.startsWith("DONE") ? GOOD : (cal != null && (cal.startsWith("ERROR") || cal.startsWith("ABORT")) ? BAD : SUB));
        calBtn.setEnabled(!running);
        calBtn.setAlpha(running ? 0.5f : 1f);

        grantCard.setVisibility(checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED ? View.GONE : View.VISIBLE);
        if (advBody.getVisibility() == View.VISIBLE) reportView.setText(r == null ? "" : r);
    }
    // EFFECT_THUD is a @hide API — access via reflection, fallback to HEAVY_CLICK
    private VibrationEffect thudEffect() {
        try {
            int id = (int) VibrationEffect.class.getField("EFFECT_THUD").get(null);
            return VibrationEffect.createPredefined(id);
        } catch (Throwable e) {
            return VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK);
        }
    }

}
