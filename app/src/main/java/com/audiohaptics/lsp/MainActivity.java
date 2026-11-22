package com.audiohaptics.lsp;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    boolean enabled = true, muteOnSilent = true;
    int phase = 1, src = 0, warm = 1;
    float gain = 1f, tone = 1f, soft = 0.6f;
    TextView status, report, hint, gainLabel, toneLabel, softLabel;
    Vibrator vib;

    interface OnVal { void on(int v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        load();
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        status = text(root, "", 16);
        report = text(root, "", 11);
        report.setTypeface(Typeface.MONOSPACE);

        addToggle(root, "الاهتزاز عبر السماعات (الزر الرئيسي)", enabled, on -> { enabled = on; save(); });
        addToggle(root, "إيقاف الاهتزاز عند الوضع الصامت", muteOnSilent, on -> { muteOnSilent = on; save(); });

        gainLabel = text(root, "", 14);
        root.addView(seek(0, 300, (int) (gain * 100), v -> { gain = v / 100f; save(); refreshLabels(); }));
        toneLabel = text(root, "", 14);
        root.addView(seek(50, 200, (int) (tone * 100), v -> { tone = v / 100f; save(); refreshLabels(); }));
        softLabel = text(root, "", 14);
        root.addView(seek(0, 100, (int) (soft * 100), v -> { soft = v / 100f; save(); refreshLabels(); }));

        text(root, "طور السماعات:", 14);
        radios(root, new String[]{"هادئ: طور معاكس (صوت أقل، موصى به)", "قوي: طور متطابق (أقوى وأعلى صوتاً)"},
                phase == 1 ? 0 : 1, v -> { phase = (v == 0) ? 1 : 0; save(); });

        text(root, "مصدر الصوت:", 14);
        radios(root, new String[]{"تلقائي", "ثابت (BEACON): قوة مستقلة عن أي مستوى صوت", "المنبّه (يتبع مستوى صوت المنبّه)"},
                src, v -> { src = v; save(); });

        text(root, "إبقاء مسار الصوت جاهزاً (أقل تأخير):", 14);
        radios(root, new String[]{"عند الطلب فقط (تأخير أول نقرة ~100ms)", "أثناء تشغيل الشاشة (موصى به)", "دائماً"},
                warm, v -> { warm = v; save(); });

        text(root, "اختبار:", 14);
        addTest(root, "Tick", VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        addTest(root, "Click", VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        addTest(root, "Heavy click", VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        addTest(root, "Double click", VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK));
        addTestThud(root);
        addTest(root, "Buzz 800ms", VibrationEffect.createOneShot(800, 255));
        addTest(root, "Composition", VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.8f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 40)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 60).compose());

        EditText et = new EditText(this);
        et.setHint("اكتب هنا لاختبار اهتزاز لوحة المفاتيح");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(et);

        hint = text(root, "", 13);
        hint.setVisibility(View.GONE);
        Button grant = new Button(this);
        grant.setText("منح صلاحية الحفظ عبر root");
        grant.setOnClickListener(v -> new Thread(() -> {
            try {
                Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"});
                pr.waitFor();
            } catch (Throwable ignored) { }
            runOnUiThread(this::save);
        }).start());
        root.addView(grant);
        refreshLabels();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean has = vib != null && vib.hasVibrator();
        status.setText(has ? "✔ النظام يرى الآن هزازاً (الوحدة تعمل)" : "✘ لا يوجد هزاز بعد");
        String r = null;
        try { r = Settings.Global.getString(getContentResolver(), Status.KEY); } catch (Throwable ignored) { }
        report.setText(r != null ? "تقرير system_server:\n" + r : "تقرير system_server: (لا يوجد)");
    }

    void refreshLabels() {
        gainLabel.setText(String.format(Locale.US, "القوة (مستقلة): %d%%", (int) (gain * 100)));
        toneLabel.setText(String.format(Locale.US, "حدّة النغمة (التردد): %d%%", (int) (tone * 100)));
        softLabel.setText(String.format(Locale.US, "النعومة (تقليل الصوت الحاد): %d%%", (int) (soft * 100)));
    }

    void load() {
        try {
            String s = Settings.Global.getString(getContentResolver(), Config.KEY);
            if (s == null) return;
            for (String kv : s.split(";")) {
                String[] p = kv.split("=");
                if (p.length != 2) continue;
                switch (p[0]) {
                    case "e": enabled = !p[1].equals("0"); break;
                    case "m": muteOnSilent = !p[1].equals("0"); break;
                    case "p": phase = Integer.parseInt(p[1]); break;
                    case "k": src = Integer.parseInt(p[1]); break;
                    case "g": gain = Float.parseFloat(p[1]); break;
                    case "t": tone = Float.parseFloat(p[1]); break;
                    case "s": soft = Float.parseFloat(p[1]); break;
                    case "w": warm = Integer.parseInt(p[1]); break;
                }
            }
        } catch (Throwable ignored) { }
    }

    void save() {
        try {
            Settings.Global.putString(getContentResolver(), Config.KEY, String.format(Locale.US,
                    "e=%d;m=%d;p=%d;k=%d;g=%.2f;t=%.2f;s=%.2f;w=%d",
                    enabled ? 1 : 0, muteOnSilent ? 1 : 0, phase, src, gain, tone, soft, warm));
            if (hint != null) hint.setVisibility(View.GONE);
        } catch (SecurityException e) {
            if (hint != null) {
                hint.setText("لا توجد صلاحية الحفظ. اضغط الزر أدناه، أو نفّذ:\nsu -c pm grant " + getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS");
                hint.setVisibility(View.VISIBLE);
            }
        }
    }

    interface OnBool { void on(boolean v); }

    void addToggle(LinearLayout root, String label, boolean init, OnBool cb) {
        final Button bt = new Button(this);
        final boolean[] st = {init};
        bt.setAllCaps(false);
        Runnable paint = () -> bt.setText(label + ":  " + (st[0] ? "مفعّل ✔" : "متوقف ✘"));
        paint.run();
        bt.setOnClickListener(v -> { st[0] = !st[0]; paint.run(); cb.on(st[0]); });
        root.addView(bt);
    }

    void radios(LinearLayout root, String[] names, int sel, OnVal cb) {
        RadioGroup rg = new RadioGroup(this);
        for (String n : names) {
            RadioButton r = new RadioButton(this);
            r.setText(n);
            r.setId(View.generateViewId());
            rg.addView(r);
        }
        if (sel >= 0 && sel < names.length) ((RadioButton) rg.getChildAt(sel)).setChecked(true);
        rg.setOnCheckedChangeListener((g, id) -> cb.on(g.indexOfChild(g.findViewById(id))));
        root.addView(rg);
    }

    SeekBar seek(int min, int max, int cur, OnVal cb) {
        SeekBar s = new SeekBar(this);
        s.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // left = min, right = max
        s.setMax(max - min);
        s.setProgress(Math.max(0, Math.min(max - min, cur - min)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean user) { if (user) cb.on(v + min); }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        return s;
    }

    TextView text(LinearLayout root, String t, int sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setPadding(0, 12, 0, 6);
        root.addView(v);
        return v;
    }

    void addTest(LinearLayout root, String label, VibrationEffect fx) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setAllCaps(false);
        bt.setOnClickListener(v -> { if (vib != null) vib.vibrate(fx); });
        root.addView(bt);
    }
    // EFFECT_THUD is a @hide API — access via reflection at runtime
    void addTestThud(LinearLayout root) {
        Button bt = new Button(this);
        bt.setText("Thud");
        bt.setAllCaps(false);
        bt.setOnClickListener(v -> {
            if (vib == null) return;
            try {
                int effectId = (int) VibrationEffect.class.getField("EFFECT_THUD").get(null);
                vib.vibrate(VibrationEffect.createPredefined(effectId));
            } catch (Throwable e) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
            }
        });
        root.addView(bt);
    }

}
