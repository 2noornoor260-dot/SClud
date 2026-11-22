package com.audiohaptics.lsp;

import android.app.Activity;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
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
    float gain = 1f, tone = 1f, soft = 0.6f, punch = 1.8f;
    int labModeCmd = 2, labFreqCmd = 0, labSel = 0, out = 0, quadMode = 0;
    TextView punchLabel, labLabel, meterLabel;
    // accelerometer-based vibration meter
    SensorManager sm;
    Sensor acc;
    final double[] lp = new double[3];
    long lastTs, rateT0, labT0, sweepT0;
    double sumSq, baseline, labSum, lastRms, bestMg;
    int accN, evTotal, rateHz, baseTicks, labCnt, bestF, maxHz;
    boolean labMeasuring;
    String labName = "";
    final java.util.TreeMap<Integer, Double> bins = new java.util.TreeMap<>();
    TextView status, report, hint, gainLabel, toneLabel, softLabel, sweepLabel;
    SeekBar toneBar;
    long sweepId;
    int lastF;
    boolean sweeping;
    final Handler ui = new Handler(Looper.getMainLooper());
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
        meterLabel = text(root, "", 13);
        meterLabel.setTypeface(Typeface.MONOSPACE);
        text(root, "اختياري: عداد الاهتزاز بمستشعر التسارع (للقياس بالأرقام فقط).", 11);

        addToggle(root, "الاهتزاز عبر السماعات (الزر الرئيسي)", enabled, on -> { enabled = on; save(); });
        addToggle(root, "إيقاف الاهتزاز عند الوضع الصامت", muteOnSilent, on -> { muteOnSilent = on; save(); });

        gainLabel = text(root, "", 14);
        root.addView(seek(0, 300, (int) (gain * 100), v -> { gain = v / 100f; save(); refreshLabels(); }));
        toneLabel = text(root, "", 14);
        toneBar = seek(40, 300, (int) (tone * 100), v -> { tone = v / 100f; save(); refreshLabels(); });
        root.addView(toneBar);
        softLabel = text(root, "", 14);
        root.addView(seek(0, 100, (int) (soft * 100), v -> { soft = v / 100f; save(); refreshLabels(); }));
        punchLabel = text(root, "", 14);
        root.addView(seek(50, 350, (int) (punch * 100), v -> { punch = v / 100f; save(); refreshLabels(); }));

        text(root, "طور السماعات (للستيريو):", 14);
        radios(root, new String[]{"هادئ: طور معاكس (صوت أقل، موصى به)", "قوي: طور متطابق (أقوى وأعلى صوتاً)"},
                phase == 1 ? 0 : 1, v -> { phase = (v == 0) ? 1 : 0; save(); });

        text(root, "إخراج الصوت:", 14);
        radios(root, new String[]{"ستيريو (قناتان، المسار السريع)", "رباعي (تجريبي: أربع قنوات، قد يزيد التأخير)"},
                out, v -> { out = v; save(); });
        text(root, "تخطيط السماعات الأربع (للإخراج الرباعي فقط، جرّبها بالإحساس):", 14);
        radios(root, new String[]{"الأربع بنفس الطور (أقوى دفع)", "يمين/يسار بطور معاكس", "أمام/خلف بطور معاكس", "قطري معاكس (تأرجح)"},
                quadMode, v -> { quadMode = v; save(); });

        text(root, "مصدر الصوت:", 14);
        radios(root, new String[]{"تلقائي", "ثابت (BEACON): تجريبي", "المنبّه مع تعويض مستوى الصوت (موصى به)"},
                src, v -> { src = v; save(); });

        text(root, "إبقاء مسار الصوت جاهزاً (أقل تأخير):", 14);
        radios(root, new String[]{"عند الطلب فقط (تأخير أول نقرة ~100ms)", "أثناء تشغيل الشاشة (موصى به)", "دائماً"},
                warm, v -> { warm = v; save(); });

        text(root, "مختبر السماعات: اعرف أي السماعات تعمل (ضع إصبعك على كل فتحة سماعة)", 14);
        radios(root, new String[]{"150 هرتز (اهتزاز)", "600 هرتز", "2500 هرتز"}, 0, v -> { labSel = v; });
        String[] labNames = {"ستيريو: اليسار فقط", "ستيريو: اليمين فقط", "ستيريو: الاثنان بنفس الطور", "ستيريو: الاثنان بطور معاكس",
                "رباعي: FL فقط", "رباعي: FR فقط", "رباعي: BL فقط", "رباعي: BR فقط",
                "رباعي: الأربع بنفس الطور", "رباعي: قطري معاكس (تأرجح)"};
        for (int i = 0; i < labNames.length; i++) {
            final int mode = i;
            final String nm = labNames[i];
            Button lb = new Button(this);
            lb.setText(nm + " (4 ثوانٍ)");
            lb.setAllCaps(false);
            lb.setOnClickListener(v -> {
                int[] hz = {150, 600, 2500};
                labModeCmd = mode; labFreqCmd = hz[labSel];
                sweeping = false; sweepId = System.currentTimeMillis();
                labName = nm + " @" + labFreqCmd + "Hz";
                labMeasuring = true; labT0 = SystemClock.elapsedRealtime(); labSum = 0; labCnt = 0;
                labLabel.setText("جارٍ القياس... لا تلمس الجهاز");
                save();
            });
            root.addView(lb);
        }
        labLabel = text(root, "النتيجة تظهر هنا بالملّي-جي (كلما زاد الرقم زاد اهتزاز الهيكل). وللتمييز بين السماعات بالسمع جرّب 600 أو 2500 هرتز وقرّب أذنك من كل ركن.", 12);

        text(root, "معايرة التردد: ابحث عن التردد الذي يهتز عنده هيكل الجهاز أقوى ما يمكن:", 14);
        Button sw1 = new Button(this);
        sw1.setText("ابدأ المسح (50 ← 400 هرتز خلال 16 ثانية)");
        sw1.setAllCaps(false);
        sw1.setOnClickListener(v -> { labFreqCmd = 0; labMeasuring = false; bins.clear(); bestF = 0; sweeping = true; lastF = 0; sweepT0 = SystemClock.elapsedRealtime(); sweepId = System.currentTimeMillis(); save(); ui.post(sweepPoll); });
        root.addView(sw1);
        sweepLabel = text(root, "امسك الجهاز بيديك أو ضعه على طاولة، واضغط الزر التالي عندما يكون الاهتزاز (لا الصوت) أقوى ما يمكن", 12);
        Button sw2 = new Button(this);
        sw2.setText("هذا هو الأقوى: اعتمد هذا التردد");
        sw2.setAllCaps(false);
        sw2.setOnClickListener(v -> {
            if (lastF >= 50) {
                tone = Math.max(0.4f, Math.min(3f, lastF / 150f));
                toneBar.setProgress(Math.max(0, Math.min(260, (int) (tone * 100) - 40)));
                refreshLabels();
            }
            sweeping = false; sweepId = 0; save();
            sweepLabel.setText("تم اعتماد ~" + lastF + " هرتز");
        });
        root.addView(sw2);
        Button sw3 = new Button(this);
        sw3.setText("اعتمد التردد الأقوى حسب القياس");
        sw3.setAllCaps(false);
        sw3.setOnClickListener(v -> {
            if (bestF >= 50) {
                tone = Math.max(0.4f, Math.min(3f, bestF / 150f));
                toneBar.setProgress(Math.max(0, Math.min(260, (int) (tone * 100) - 40)));
                refreshLabels();
                save();
                sweepLabel.setText("تم اعتماد ~" + bestF + " هرتز (مقاس)");
            } else sweepLabel.setText("لا توجد نتيجة قياس بعد: شغّل المسح حتى ينتهي (16 ثانية)");
        });
        root.addView(sw3);

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

    final Runnable sweepPoll = new Runnable() {
        @Override public void run() {
            if (!sweeping) return;
            try {
                String r = Settings.Global.getString(getContentResolver(), Status.KEY);
                if (r != null) for (String line : r.split("\n")) {
                    if (line.startsWith("10.sweep: f=")) { lastF = Integer.parseInt(line.substring(12).trim()); }
                    if (line.startsWith("10.sweep: finished") || line.startsWith("10.sweep: stopped")) { if (lastF > 0) finishSweep(); }
                }
                sweepLabel.setText("التردد الحالي: ~" + lastF + " هرتز");
                if (SystemClock.elapsedRealtime() - sweepT0 > 19000) finishSweep();
            } catch (Throwable ignored) { }
            ui.postDelayed(this, 300);
        }
    };

    void finishSweep() {
        if (!sweeping) return;
        sweeping = false;
        bestF = 0; bestMg = 0;
        for (java.util.Map.Entry<Integer, Double> en : bins.entrySet()) {
            if (en.getValue() > bestMg) { bestMg = en.getValue(); bestF = en.getKey() * 10 + 5; }
        }
        sweepLabel.setText(bestF > 0
                ? String.format(Locale.US, "أقوى اهتزاز مقاس عند ~%d هرتز (%.1f ملّي-جي، الأساس %.1f). اضغط «اعتمد التردد الأقوى حسب القياس»", bestF, bestMg, baseline)
                : "لم تُسجَّل قياسات: تأكد أن مستشعر التسارع يعمل");
    }

    final SensorEventListener accL = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            long ts = e.timestamp;
            if (lastTs == 0) { for (int i = 0; i < 3; i++) lp[i] = e.values[i]; lastTs = ts; return; }
            double dt = (ts - lastTs) / 1e9;
            lastTs = ts;
            double a = dt / (dt + 0.1);
            double sq = 0;
            for (int i = 0; i < 3; i++) {
                double x = e.values[i];
                lp[i] += a * (x - lp[i]);
                double h = x - lp[i];
                sq += h * h;
            }
            sumSq += sq; accN++; evTotal++;
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    final Runnable meterTick = new Runnable() {
        @Override public void run() {
            double r = accN > 0 ? Math.sqrt(sumSq / accN) / 9.80665 * 1000.0 : 0;
            sumSq = 0; accN = 0; lastRms = r;
            long now = SystemClock.elapsedRealtime();
            if (now - rateT0 >= 1000) { rateHz = (int) (evTotal * 1000L / (now - rateT0)); evTotal = 0; rateT0 = now; }
            if (baseTicks < 6 && !labMeasuring && !sweeping) { baseline = (baseline * baseTicks + r) / (baseTicks + 1); baseTicks++; }
            meterLabel.setText(String.format(Locale.US, "اهتزاز الجهاز: %.1f ملّي-جي | معدل المستشعر: %d هرتز (الأقصى %d) | الأساس: %.1f", r, rateHz, maxHz, baseline));
            if (labMeasuring) {
                long el = now - labT0;
                if (el > 1300 && el < 3900) { labSum += r; labCnt++; }
                if (el >= 5600) {
                    labMeasuring = false;
                    double avg = labCnt > 0 ? labSum / labCnt : 0;
                    labLabel.setText(String.format(Locale.US, "%s: %.1f ملّي-جي (الأساس %.1f، الصافي %.1f)", labName, avg, baseline, Math.max(0, avg - baseline)));
                }
            }
            if (sweeping && lastF > 0) {
                Double old = bins.get(lastF / 10);
                if (old == null || r > old) bins.put(lastF / 10, r);
            }
            ui.postDelayed(this, 300);
        }
    };

    void startMeter() {
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) { meterLabel.setText("لا يوجد مستشعر تسارع"); return; }
        maxHz = acc.getMinDelay() > 0 ? 1_000_000 / acc.getMinDelay() : 0;
        lastTs = 0; evTotal = 0; rateT0 = SystemClock.elapsedRealtime(); baseTicks = 0; baseline = 0;
        boolean ok = false;
        try { ok = sm.registerListener(accL, acc, 2500, ui); } catch (Throwable ignored) { }
        if (!ok) sm.registerListener(accL, acc, SensorManager.SENSOR_DELAY_FASTEST, ui);
        ui.post(meterTick);
    }

    void stopMeter() {
        if (sm != null) sm.unregisterListener(accL);
        ui.removeCallbacks(meterTick);
    }

    final Runnable reportRefresh = new Runnable() {
        @Override public void run() {
            showReport();
            ui.postDelayed(this, 2000);
        }
    };

    void showReport() {
        boolean has = vib != null && vib.hasVibrator();
        status.setText(has ? "✔ النظام يرى الآن هزازاً (الوحدة تعمل)" : "✘ لا يوجد هزاز بعد");
        String r = null;
        try { r = Settings.Global.getString(getContentResolver(), Status.KEY); } catch (Throwable ignored) { }
        report.setText(r != null ? "تقرير system_server (يتجدد كل ثانيتين):\n" + r : "تقرير system_server: (لا يوجد)");
    }

    @Override protected void onPause() {
        super.onPause();
        ui.removeCallbacks(reportRefresh);
        ui.removeCallbacks(sweepPoll);
        stopMeter();
        if (sweeping) { sweeping = false; sweepId = 0; save(); }
    }

    @Override protected void onResume() {
        super.onResume();
        ui.post(reportRefresh);
        startMeter();
    }

    void refreshLabels() {
        gainLabel.setText(String.format(Locale.US, "القوة (مستقلة): %d%%", (int) (gain * 100)));
        toneLabel.setText(String.format(Locale.US, "تردد الاهتزاز: ~%d هرتز (%d%%)", (int) (150 * tone), (int) (tone * 100)));
        punchLabel.setText(String.format(Locale.US, "طول/طاقة النقرة (أعلى = إحساس أقوى وأقل حدّة): %d%%", (int) (punch * 100)));
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
                    case "u": punch = Float.parseFloat(p[1]); break;
                    case "o": out = Integer.parseInt(p[1]); break;
                    case "q": quadMode = Integer.parseInt(p[1]); break;
                }
            }
        } catch (Throwable ignored) { }
    }

    void save() {
        try {
            Settings.Global.putString(getContentResolver(), Config.KEY, String.format(Locale.US,
                    "e=%d;m=%d;p=%d;k=%d;g=%.2f;t=%.2f;s=%.2f;w=%d;u=%.2f;o=%d;q=%d;lm=%d;lf=%d;x=%d",
                    enabled ? 1 : 0, muteOnSilent ? 1 : 0, phase, src, gain, tone, soft, warm, punch, out, quadMode, labModeCmd, labFreqCmd, sweepId));
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
