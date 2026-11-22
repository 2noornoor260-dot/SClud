package com.audiohaptics.lsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Low-latency speaker haptics engine.
 * A LOW_LATENCY AudioTrack is kept running ("warm") and written one HAL burst at a time, so a request is mixed into
 * the very next burst. Output: low-pass -> soft limiter -> stereo (optionally anti-phase to cancel radiated sound).
 * Strength is our own digital gain; with the fixed-volume (BEACON) source it does not depend on any system volume.
 */
final class HapticEngine implements Runnable {
    interface StateListener { void onState(boolean vibrating); }

    static final int SR = Synth.SR;
    static final float REF = 0.5f;                       // digital peak at 100% strength
    private static final long KEEP_WARM_NS = 4_000_000_000L;
    private static final int SRC_BEACON = 1, SRC_ALARM = 2;

    static final class Voice {
        final float[] d; final float g; final int delay; long start;
        Voice(float[] d, float g, int delay) { this.d = d; this.g = g; this.delay = delay; }
    }

    final Config cfg = new Config();
    private final StateListener listener;
    private final HandlerThread cbThread = new HandlerThread("AudioHaptics-cb");
    private final Handler cb;

    private final ConcurrentLinkedQueue<Voice> inbox = new ConcurrentLinkedQueue<>();
    private final ArrayList<Voice> voices = new ArrayList<>();      // engine thread only
    private final AtomicBoolean vibState = new AtomicBoolean(false);

    private volatile boolean started, cancelPending;
    private volatile boolean screenOn = true, silent;
    private volatile long warmUntil, vibratingUntil, susEnd;
    private volatile float susAmp = 1f;
    volatile float currentAmp = 1f;

    private float susCur, lp1, lp2;
    private double susPh;

    private Thread thread;
    private Context ctx;
    private AudioTrack track;
    private int burst = 240, bufFrames, lastUnder, appliedSrc = -1, appliedOut = -1, ch = 2;
    private boolean playing;
    private long framesWritten, lastTune;
    private float[] mix = new float[240];
    private short[] pcm = new short[480];
    private String trackInfo = "not created", lastState = "";
    private volatile long lastPump = System.nanoTime();
    private volatile float comp = 1f;          // keeps strength constant when the ALARM source is used
    private int usedSrc, stalls;
    private boolean receiversOk;
    private volatile boolean quadBusy;
    private static final double SWEEP_S = 16.0;
    private volatile boolean sweepActive;
    private long sweepStart, lastSweepStatus, lastSweep = Long.MIN_VALUE;
    private double sweepPh, sweepDur = 16.0;
    private volatile int labF, labM;
    private long lastAlive;

    HapticEngine(StateListener l) {
        listener = l;
        cbThread.start();
        cb = new Handler(cbThread.getLooper());
    }

    // ------------------------------------------------------------------ public API (vibration thread)

    synchronized void start() {
        if (started) return;
        started = true;
        thread = new Thread(this, "AudioHaptics-engine");
        thread.setDaemon(true);
        thread.start();
        cb.postDelayed(watchdog, 2000);
        Status.put("5.engine", "thread started");
    }

    void post(Runnable r, long delayMs) { cb.postDelayed(r, Math.max(0, delayMs)); }

    float strengthGain(int s) { return s <= 0 ? 0.5f : (s == 1 ? 1.0f : 1.6f); }

    long effect(int id, int strength) {
        int d = Synth.effectDur(id, cfg.punch);
        if (d <= 0) return 0;
        float g = strengthGain(strength);
        if (id == 1) {
            float[] c = Synth.effect(0, cfg.tone, cfg.punch);
            addPulse(c, g, 0);
            addPulse(c, g * 0.9f, Math.round(Synth.effectBase(0) * cfg.punch) + 45);
        } else {
            addPulse(Synth.effect(id, cfg.tone, cfg.punch), g, 0);
        }
        busy(d);
        return d;
    }

    void primitive(int id, float scale, int delayMs) {
        float[] w = Synth.effect(100 + id, cfg.tone, cfg.punch);
        if (w.length > 0) addPulse(w, Config.clamp(scale, 0f, 1.5f), delayMs);
    }

    void sustained(long ms) {
        busy(ms);
        if (!enabledNow()) return;
        susEnd = System.nanoTime() + ms * 1_000_000L;
        susAmp = currentAmp;
        wake();
    }

    void setAmplitude(float a) { currentAmp = Config.clamp(a, 0f, 1f); susAmp = currentAmp; }

    void off() { susEnd = 0; cancelPending = true; wake(); }

    boolean isVibrating() { return System.nanoTime() < vibratingUntil; }

    void busy(long ms) {
        long until = System.nanoTime() + ms * 1_000_000L;
        if (until > vibratingUntil) vibratingUntil = until;
        if (!vibState.getAndSet(true)) notifyState(true);
        cb.postDelayed(endCheck, ms + 3);
    }

    String status() {
        return "engine=" + (started ? "running" : "stopped") + " playing=" + playing + " burst=" + burst
                + " buf=" + bufFrames + " " + trackInfo;
    }

    // ------------------------------------------------------------------ internals

    /** If the engine stops consuming audio (blocked write), releasing the track from here unblocks and re-inits it. */
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                AudioTrack t = track;
                if (started && t != null && playing && System.nanoTime() - lastPump > 2_500_000_000L) {
                    stalls++;
                    Status.put("9.watchdog", "engine stalled -> track released (count=" + stalls + ")");
                    try { t.release(); } catch (Throwable ignored) { }
                    lastPump = System.nanoTime();
                }
            } catch (Throwable ignored) { }
            cb.postDelayed(this, 1000);
        }
    };

    private void updateComp(Context c) {
        if (usedSrc != SRC_ALARM) { comp = 1f; return; }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int idx = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            float cur = am.getStreamVolumeDb(AudioManager.STREAM_ALARM, idx, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            float top = am.getStreamVolumeDb(AudioManager.STREAM_ALARM, max, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            comp = Config.clamp((float) Math.pow(10, (top - cur) / 20.0), 1f, 4f);
        } catch (Throwable e) { comp = 1f; }
    }

    private void handleSweep() {
        long id = cfg.sweep;
        if (lastSweep == Long.MIN_VALUE) { lastSweep = id; return; }     // never start a stale sweep after a reboot
        if (id == lastSweep) return;
        lastSweep = id;
        if (id > 0 && enabledNow() && cfg.labFreq > 0 && cfg.labMode >= 4) {
            quadLab(cfg.labMode, cfg.labFreq);
            return;
        }
        if (id > 0 && enabledNow()) {
            labF = cfg.labFreq;
            labM = cfg.labMode;
            sweepDur = labF > 0 ? 4.0 : SWEEP_S;
            sweepStart = System.nanoTime();
            sweepPh = 0;
            sweepActive = true;
            wake();
        } else {
            sweepActive = false;
            Status.put("10.sweep", "stopped");
        }
    }

    /**
     * 4-channel (FL, FR, BL, BR) test track: modes 4..7 = one channel only, 8 = all in phase, 9 = diagonal anti-phase.
     * Goes through the multichannel (direct) output if the policy accepts it; reports what the system actually did.
     */
    private void quadLab(final int mode, final int freq) {
        if (quadBusy) return;
        quadBusy = true;
        new Thread(() -> {
            AudioTrack t = null;
            try {
                Context c = ctx();
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_QUAD).build();
                AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setFlags(0x40 | 0x80).build();
                int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_QUAD, AudioFormat.ENCODING_PCM_16BIT);
                t = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min, SR / 5 * 8)).build();
                setPreferred(t, am);
                final AudioTrack tt = t;
                cb.postDelayed(() -> { try { tt.release(); } catch (Throwable ignored) { } }, 9000);
                t.play();
                final int chunk = 960;
                short[] buf = new short[4 * chunk];
                double ph = 0, w = 2 * Math.PI * freq / SR;
                float amp = 0.4f * Config.clamp(cfg.gain, 0.3f, 2f);
                AudioDeviceInfo d = null;
                int total = SR * 4;
                for (int pos = 0; pos < total; pos += chunk) {
                    for (int i = 0; i < chunk; i++) {
                        double te = (pos + i) / (double) SR;
                        double ramp = Math.max(0.0, Math.min(1.0, Math.min(te, 4.0 - te) / 0.05));
                        short x = (short) (amp * ramp * Math.sin(ph) * 32767.0);
                        short y = (short) -x;
                        ph += w;
                        short fl = 0, fr = 0, bl = 0, br = 0;
                        switch (mode) {
                            case 4: fl = x; break;
                            case 5: fr = x; break;
                            case 6: bl = x; break;
                            case 7: br = x; break;
                            case 8: fl = fr = bl = br = x; break;
                            default: fl = x; br = x; fr = y; bl = y; break;
                        }
                        buf[4 * i] = fl; buf[4 * i + 1] = fr; buf[4 * i + 2] = bl; buf[4 * i + 3] = br;
                    }
                    t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                    if (pos == 0) d = t.getRoutedDevice();
                }
                Status.put("11.quad", "ok mode=" + mode + " f=" + freq + " channels=" + t.getChannelCount()
                        + " perfMode=" + t.getPerformanceMode() + " routed=" + (d == null ? "none" : "type" + d.getType()));
            } catch (Throwable e) {
                Status.put("11.quad", "FAILED mode=" + mode + " " + e);
            } finally {
                try { if (t != null) t.release(); } catch (Throwable ignored) { }
                quadBusy = false;
            }
        }, "AudioHaptics-quadlab").start();
    }

    private boolean enabledNow() { return cfg.enabled && !(cfg.muteOnSilent && silent); }

    private final Runnable endCheck = new Runnable() {
        @Override public void run() {
            long left = vibratingUntil - System.nanoTime();
            if (left > 0) { cb.postDelayed(this, left / 1_000_000L + 2); return; }
            if (vibState.getAndSet(false)) notifyState(false);
        }
    };

    private void notifyState(boolean v) {
        try { listener.onState(v); } catch (Throwable t) { L.e("state", t); }
    }

    private void addPulse(float[] w, float gain, int delayMs) {
        if (w == null || w.length == 0 || !enabledNow()) return;
        inbox.add(new Voice(w, gain, (int) (delayMs * (SR / 1000L))));
        wake();
    }

    private void wake() {
        warmUntil = System.nanoTime() + KEEP_WARM_NS;
        Thread t = thread;
        if (t != null) LockSupport.unpark(t);
    }

    private Context ctx() {
        if (ctx != null) return ctx;
        Context c = SysCtx.get();
        if (c != null) ctx = c;
        return ctx;
    }

    /** Registered lazily and retried every second: AudioService may not exist yet when the engine first starts. */
    private void ensureReceivers(Context c) {
        if (receiversOk || c == null) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    String a = intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(a)) { screenOn = true; wake(); }
                    else if (Intent.ACTION_SCREEN_OFF.equals(a)) screenOn = false;
                    else if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(a)) {
                        silent = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                                == AudioManager.RINGER_MODE_SILENT;
                        if (silent) off();
                    }
                }
            }, f, null, cb);
            receiversOk = true;
        } catch (Throwable e) { L.e("receivers (will retry)", e); }
    }

    /** Polls the real state once a second as a safety net next to the broadcasts. */
    private void pollState(Context c) {
        if (c == null) return;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) screenOn = pm.isInteractive();
        } catch (Throwable ignored) { }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                boolean s = am.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
                if (s && !silent) off();
                silent = s;
            }
        } catch (Throwable ignored) { }
    }

    private void boostThread() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
        try {
            Process.class.getMethod("setThreadScheduler", int.class, int.class, int.class)
                    .invoke(null, Process.myTid(), 1 | 0x40000000, 2);
        } catch (Throwable ignored) { }
    }

    private void reportState() {
        String st = "enabled=" + cfg.enabled + " muteOnSilent=" + cfg.muteOnSilent + " silent=" + silent
                + " active=" + enabledNow() + " phase=" + (cfg.phase == 1 ? "anti" : "in") + " gain=" + cfg.gain
                + " tone=" + cfg.tone + " soft=" + cfg.soft;
        if (!st.equals(lastState)) { lastState = st; Status.put("8.state", st); }
    }

    @Override public void run() {
        boostThread();
        long lastCfg = 0;
        while (started) {
            try {
                long now = System.nanoTime();
                if (now - lastCfg > 1_000_000_000L) {
                    cfg.refresh(ctx()); lastCfg = now;
                    ensureReceivers(ctx); pollState(ctx);
                    handleSweep();
                    reportState();
                    if (ctx != null && track != null) updateComp(ctx);
                    if (now - lastAlive > 5_000_000_000L) {
                        lastAlive = now;
                        Status.put("9.alive", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                                + " comp=" + String.format(java.util.Locale.US, "%.2f", comp));
                    }
                }
                lastPump = Math.max(lastPump, now - 1_000_000_000L);
                if (!enabledNow()) {
                    inbox.clear(); voices.clear(); susEnd = 0; susCur = 0; sweepActive = false;
                    if (playing) stopTrack();
                    LockSupport.parkNanos(150_000_000L);
                    continue;
                }
                if (track != null && (appliedSrc != cfg.src || appliedOut != cfg.out)) releaseTrack();   // source changed in the app
                boolean work = !inbox.isEmpty() || !voices.isEmpty() || now < susEnd || susCur > 1e-4f || cancelPending || sweepActive;
                boolean warm = work || now < warmUntil || cfg.warm == 2 || (cfg.warm == 1 && screenOn);
                if (!warm) {
                    if (playing) stopTrack();
                    LockSupport.parkNanos(100_000_000L);
                    continue;
                }
                if (track == null && !initTrack()) { LockSupport.parkNanos(1_500_000_000L); continue; }
                if (!playing) startTrack();
                pump();
            } catch (Throwable t) {
                Status.put("6.audio", "ERROR " + t);
                L.e("engine loop", t);
                releaseTrack();
                LockSupport.parkNanos(1_000_000_000L);
            }
        }
    }

    // ------------------------------------------------------------------ audio track

    private AudioTrack build(int src, AudioFormat fmt, int capBytes, AudioManager am) {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                AudioAttributes.Builder ab = new AudioAttributes.Builder();
                if (src == SRC_BEACON) {
                    // FLAG_BEACON -> "transmitted through speaker" strategy: speaker only, fixed 0 dB volume curve
                    ab.setUsage(AudioAttributes.USAGE_UNKNOWN).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                            .setFlags(0x08 | (attempt % 2 == 0 ? 0x80 : 0));
                } else {
                    ab.setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
                    if (attempt % 2 == 0) ab.setFlags(0x40 | 0x80);
                }
                AudioTrack x = new AudioTrack.Builder().setAudioAttributes(ab.build()).setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(attempt < 2 ? AudioTrack.PERFORMANCE_MODE_LOW_LATENCY : AudioTrack.PERFORMANCE_MODE_NONE)
                        .setBufferSizeInBytes(capBytes).build();
                if (x.getState() == AudioTrack.STATE_INITIALIZED) { setPreferred(x, am); return x; }
                x.release();
            } catch (Throwable e) { L.e("AudioTrack src=" + src + " attempt " + attempt, e); }
        }
        return null;
    }

    private void setPreferred(AudioTrack t, AudioManager am) {
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { t.setPreferredDevice(d); return; }
            }
        } catch (Throwable e) { L.e("preferred device", e); }
    }

    /** Starts the track with silence (non-blocking) and waits up to ~700 ms for a routed device / consumed frames. */
    private boolean verify(AudioTrack t) {
        try {
            short[] z = new short[burst * ch];
            t.write(z, 0, z.length, AudioTrack.WRITE_NON_BLOCKING);
            t.play();
            boolean ok = false;
            AudioDeviceInfo d = null;
            for (int i = 0; i < 14 && !ok; i++) {
                Thread.sleep(50);
                t.write(z, 0, z.length, AudioTrack.WRITE_NON_BLOCKING);
                d = t.getRoutedDevice();
                ok = d != null || t.getPlaybackHeadPosition() > 0;
            }
            trackInfo = "routed=" + (d == null ? "none" : "type" + d.getType());
            t.pause();
            t.flush();
            return ok;
        } catch (Throwable e) { L.e("verify", e); return false; }
    }

    private boolean initTrack() {
        Context c = ctx();
        if (c == null) return false;
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return false;
        try {
            String p = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            if (p != null) burst = Math.max(48, Integer.parseInt(p));
        } catch (Throwable ignored) { }
        mix = new float[burst];
        int wantOut = cfg.out;
        int want = cfg.src;
        int[] order = want == 1 ? new int[]{SRC_BEACON, SRC_ALARM} : want == 2 ? new int[]{SRC_ALARM} : new int[]{SRC_ALARM, SRC_BEACON};
        AudioTrack t = null;
        int used = 0;
        for (int o : (wantOut == 1 ? new int[]{1, 0} : new int[]{0})) {
            ch = o == 1 ? 4 : 2;
            int mask = o == 1 ? AudioFormat.CHANNEL_OUT_QUAD : AudioFormat.CHANNEL_OUT_STEREO;
            pcm = new short[burst * ch];
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR).setChannelMask(mask).build();
            int capBytes = Math.max(AudioTrack.getMinBufferSize(SR, mask, AudioFormat.ENCODING_PCM_16BIT), burst * ch * 2 * 4);
            for (int s : order) {
                AudioTrack x = build(s, fmt, capBytes, am);
                if (x == null) { Status.put("6.audio.src" + s, "track creation failed"); continue; }
                boolean ok = verify(x);
                if (ok || s == SRC_ALARM) { t = x; used = s; break; }
                Status.put("6.audio.src" + s, "not routed after 700ms -> skipped");
                x.release();
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
            }
            if (t != null) break;
            if (o == 1) Status.put("6.audio.quad", "quad track could not be created -> falling back to stereo");
        }
        if (t == null) return false;
        if (ch == 4) Status.remove("6.audio.quad");
        bufFrames = burst * 2;
        t.setBufferSizeInFrames(bufFrames);
        lastUnder = 0;
        track = t;
        usedSrc = used;
        appliedSrc = want;
        appliedOut = wantOut;
        Status.remove("6.audio.src1");
        Status.remove("6.audio.src2");
        lastPump = System.nanoTime();
        trackInfo = "out=" + (ch == 4 ? "QUAD" : "STEREO") + " src=" + (used == SRC_BEACON ? "BEACON(experimental)" : "ALARM(+volume compensation)") + " perfMode=" + t.getPerformanceMode()
                + " " + trackInfo;
        Status.put("6.audio", "track ready burst=" + burst + " " + trackInfo + " (perfMode 1 = LOW_LATENCY)");
        return true;
    }

    private void startTrack() {
        Arrays.fill(pcm, (short) 0);
        track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
        track.play();
        playing = true;
    }

    private void stopTrack() {
        try { track.pause(); track.flush(); } catch (Throwable ignored) { }
        playing = false;
    }

    private void releaseTrack() {
        try { if (track != null) track.release(); } catch (Throwable ignored) { }
        track = null;
        playing = false;
        appliedSrc = -1;
        appliedOut = -1;
    }

    private void pump() {
        final int n = burst;
        Voice v;
        while ((v = inbox.poll()) != null) { v.start = framesWritten + v.delay; voices.add(v); }
        if (cancelPending) {
            cancelPending = false;
            for (int i = voices.size() - 1; i >= 0; i--) if (voices.get(i).start > framesWritten) voices.remove(i);
        }
        Arrays.fill(mix, 0, n, 0f);
        long base = framesWritten;
        for (int a = voices.size() - 1; a >= 0; a--) {
            Voice x = voices.get(a);
            long k0 = base - x.start;
            for (int i = 0; i < n; i++) {
                long k = k0 + i;
                if (k < 0) continue;
                if (k >= x.d.length) break;
                mix[i] += x.d[(int) k] * x.g;
            }
            if (base + n - x.start >= x.d.length) voices.remove(a);
        }
        float target = System.nanoTime() < susEnd ? susAmp * 0.6f : 0f;   // continuous buzz is capped (speaker safety)
        if (target > 0f || susCur > 1e-4f) {
            double w = 2 * Math.PI * Synth.BUZZ_HZ * cfg.tone / SR;
            float k = 1f / (SR * 0.004f);
            for (int i = 0; i < n; i++) {
                susCur += (target - susCur) * k;
                susPh += w;
                mix[i] += susCur * (float) (Math.sin(susPh) + 0.35 * Math.sin(2 * susPh)) / 1.35f;
            }
            if (susPh > 1e6) susPh %= 2 * Math.PI;
        }
        if (sweepActive) {
            long nowN = System.nanoTime();
            double el = (nowN - sweepStart) / 1e9;
            if (el > sweepDur) {
                sweepActive = false;
                Status.put("10.sweep", "finished");
            } else {
                for (int i = 0; i < n; i++) {
                    double te = el + i / (double) SR;
                    double f = labF > 0 ? labF : 50.0 * Math.pow(8.0, Math.min(1.0, te / sweepDur));
                    sweepPh += 2 * Math.PI * f / SR;
                    double ramp = Math.max(0.0, Math.min(1.0, Math.min(te, sweepDur - te) / 0.05));
                    mix[i] += 0.7f * (float) ramp * (float) Math.sin(sweepPh);
                }
                if (sweepPh > 1e6) sweepPh %= 2 * Math.PI;
                if (labF == 0 && nowN - lastSweepStatus > 250_000_000L) {
                    lastSweepStatus = nowN;
                    Status.put("10.sweep", "f=" + (int) (50.0 * Math.pow(8.0, el / sweepDur)));
                }
            }
        }
        // softness: 2-pole low-pass, cutoff 1500 Hz (soft=0) .. 250 Hz (soft=1)
        double fc = 1500.0 * Math.pow(250.0 / 1500.0, cfg.soft);
        boolean lab = sweepActive && labF > 0;
        float alpha = lab ? 1f : (float) (1 - Math.exp(-2 * Math.PI * fc / SR));
        float g = cfg.gain * REF * comp;
        boolean anti = cfg.phase == 1;
        int qm = cfg.quadMode;
        for (int i = 0; i < n; i++) {
            lp1 += alpha * (mix[i] * g - lp1);
            lp2 += alpha * (lp1 - lp2);
            short s = (short) (Synth.limit(lp2) * 32767f);
            short ns = (short) -s;
            if (ch == 2) {
                short l = s, r = anti ? ns : s;
                if (lab) { l = labM == 1 ? 0 : s; r = labM == 0 ? 0 : (labM == 3 ? ns : s); }
                pcm[2 * i] = l;
                pcm[2 * i + 1] = r;
            } else {
                short fl, fr, bl, br;
                if (lab) {
                    short l = labM == 1 ? 0 : s, r = labM == 0 ? 0 : (labM == 3 ? ns : s);
                    fl = l; bl = l; fr = r; br = r;
                } else {
                    switch (qm) {
                        case 1: fl = s; bl = s; fr = ns; br = ns; break;     // left/right anti-phase
                        case 2: fl = s; fr = s; bl = ns; br = ns; break;     // front/back anti-phase
                        case 3: fl = s; br = s; fr = ns; bl = ns; break;     // diagonal anti-phase (rocking)
                        default: fl = s; fr = s; bl = s; br = s; break;      // all in phase (max net push)
                    }
                }
                pcm[4 * i] = fl; pcm[4 * i + 1] = fr; pcm[4 * i + 2] = bl; pcm[4 * i + 3] = br;
            }
        }
        int w = track.write(pcm, 0, n * ch, AudioTrack.WRITE_BLOCKING);
        if (w < 0) throw new IllegalStateException("AudioTrack.write=" + w);
        framesWritten += w / ch;
        lastPump = System.nanoTime();

        long now = System.nanoTime();
        if (now - lastTune > 500_000_000L) {
            lastTune = now;
            int u = track.getUnderrunCount();
            if (u > lastUnder && bufFrames < burst * 6) {
                bufFrames += burst;
                track.setBufferSizeInFrames(bufFrames);
                L.i("underruns " + u + " -> buffer " + bufFrames + " frames");
            }
            lastUnder = u;
        }
    }
}
