package com.audiohaptics.lsp;

import android.app.Activity;
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
    float gain = 1f, tone = 1f;
    int warm = 1;
    TextView status, hint, gainLabel, toneLabel, report;
    Vibrator vib;

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
        report = text(root, "", 12);
        report.setTypeface(android.graphics.Typeface.MONOSPACE);
        gainLabel = text(root, "", 14);
        root.addView(seek(20, 250, (int) (gain * 100), v -> { gain = v / 100f; save(); refreshLabels(); }));
        toneLabel = text(root, "", 14);
        root.addView(seek(50, 200, (int) (tone * 100), v -> { tone = v / 100f; save(); refreshLabels(); }));

        text(root, "إبقاء مسار الصوت جاهزاً (أقل تأخير):", 14);
        RadioGroup rg = new RadioGroup(this);
        String[] names = {"عند الطلب فقط (تأخير أول نقرة ~100ms)", "أثناء تشغيل الشاشة (موصى به)", "دائماً"};
        for (int i = 0; i < 3; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(names[i]);
            r.setId(100 + i);
            rg.addView(r);
        }
        rg.check(100 + warm);
        rg.setOnCheckedChangeListener((g, id) -> { warm = id - 100; save(); });
        root.addView(rg);

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
        status.setText(has ? "✔ النظام يرى الآن هزازاً (الوحدة تعمل)"
                : "✘ لا يوجد هزاز بعد");
        String r = null;
        try { r = Settings.Global.getString(getContentResolver(), Status.KEY); } catch (Throwable ignored) { }
        report.setText(r != null ? "تقرير system_server:\n" + r
                : "تقرير system_server: (لا يوجد)\nالوحدة لم تعمل داخل system_server: تأكد أنها مفعّلة في LSPosed وأن «System Framework» مُحدَّد في نطاقها، ثم أعد التشغيل.");
    }

    void refreshLabels() {
        gainLabel.setText(String.format(Locale.US, "القوة العامة: %d%%", (int) (gain * 100)));
        toneLabel.setText(String.format(Locale.US, "حدّة النغمة (التردد): %d%%", (int) (tone * 100)));
    }

    void load() {
        try {
            String s = Settings.Global.getString(getContentResolver(), Config.KEY);
            if (s == null) return;
            for (String kv : s.split(";")) {
                String[] p = kv.split("=");
                if (p.length != 2) continue;
                if (p[0].equals("g")) gain = Float.parseFloat(p[1]);
                else if (p[0].equals("t")) tone = Float.parseFloat(p[1]);
                else if (p[0].equals("w")) warm = Integer.parseInt(p[1]);
            }
        } catch (Throwable ignored) { }
    }

    void save() {
        try {
            Settings.Global.putString(getContentResolver(), Config.KEY,
                    String.format(Locale.US, "g=%.2f;t=%.2f;w=%d", gain, tone, warm));
            if (hint != null) hint.setVisibility(View.GONE);
        } catch (SecurityException e) {
            if (hint != null) {
                hint.setText("لا توجد صلاحية الحفظ. اضغط الزر أدناه، أو نفّذ:\nsu -c pm grant " + getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS");
                hint.setVisibility(View.VISIBLE);
            }
        }
    }

    interface OnVal { void on(int v); }

    SeekBar seek(int min, int max, int cur, OnVal cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max - min);
        s.setProgress(cur - min);
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
