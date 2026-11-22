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
    private int burst = 240, bufFrames, lastUnder, appliedSrc = -1;
    private boolean playing;
    private long framesWritten, lastTune;
    private float[] mix = new float[240];
    private short[] pcm = new short[480];
    private String trackInfo = "not created", lastState = "";

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
        Status.put("5.engine", "thread started");
    }

    void post(Runnable r, long delayMs) { cb.postDelayed(r, Math.max(0, delayMs)); }

    float strengthGain(int s) { return s <= 0 ? 0.5f : (s == 1 ? 1.0f : 1.6f); }

    long effect(int id, int strength) {
        int d = Synth.effectDur(id);
        if (d <= 0) return 0;
        float g = strengthGain(strength);
        if (id == 1) {
            float[] c = Synth.effect(0, cfg.tone);
            addPulse(c, g, 0);
            addPulse(c, g * 0.9f, 90);
        } else {
            addPulse(Synth.effect(id, cfg.tone), g, 0);
        }
        busy(d);
        return d;
    }

    void primitive(int id, float scale, int delayMs) {
        float[] w = Synth.effect(100 + id, cfg.tone);
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
        if (c == null) return null;
        ctx = c;
        registerReceivers(c);
        return ctx;
    }

    private void registerReceivers(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) screenOn = pm.isInteractive();
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) silent = am.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
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
        } catch (Throwable e) { L.e("receivers", e); }
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
                if (now - lastCfg > 1_000_000_000L) { cfg.refresh(ctx()); lastCfg = now; reportState(); }
                if (!enabledNow()) {
                    inbox.clear(); voices.clear(); susEnd = 0; susCur = 0;
                    if (playing) stopTrack();
                    LockSupport.parkNanos(150_000_000L);
                    continue;
                }
                if (track != null && appliedSrc != cfg.src) releaseTrack();   // source changed in the app
                boolean work = !inbox.isEmpty() || !voices.isEmpty() || now < susEnd || susCur > 1e-4f || cancelPending;
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

    private AudioTrack build(int src, AudioFormat fmt, int capBytes) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                AudioAttributes.Builder ab = new AudioAttributes.Builder();
                if (src == SRC_BEACON) {
                    // FLAG_BEACON -> "transmitted through speaker" strategy: speaker only, fixed 0 dB volume curve
                    ab.setUsage(AudioAttributes.USAGE_UNKNOWN).setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                            .setFlags(0x08 | (attempt == 0 ? 0x80 : 0));
                } else {
                    ab.setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
                    if (attempt == 0) ab.setFlags(0x40 | 0x80);
                }
                AudioTrack x = new AudioTrack.Builder().setAudioAttributes(ab.build()).setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(capBytes).build();
                if (x.getState() == AudioTrack.STATE_INITIALIZED) return x;
                x.release();
            } catch (Throwable e) { L.e("AudioTrack src=" + src + " attempt " + attempt, e); }
        }
        return null;
    }

    /** Plays a few bursts of silence and checks the stream is really routed and consumed. */
    private boolean verify(AudioTrack t) {
        try {
            short[] z = new short[burst * 2];
            t.write(z, 0, z.length, AudioTrack.WRITE_BLOCKING);
            t.play();
            for (int i = 0; i < 8; i++) t.write(z, 0, z.length, AudioTrack.WRITE_BLOCKING);
            Thread.sleep(90);
            boolean ok = t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING && t.getPlaybackHeadPosition() > 0;
            AudioDeviceInfo d = t.getRoutedDevice();
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
        pcm = new short[burst * 2];

        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        int capBytes = Math.max(AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT), burst * 4 * 4);

        int want = cfg.src;
        int[] order = want == 1 ? new int[]{SRC_BEACON} : want == 2 ? new int[]{SRC_ALARM} : new int[]{SRC_BEACON, SRC_ALARM};
        AudioTrack t = null;
        int used = 0;
        for (int s : order) {
            AudioTrack x = build(s, fmt, capBytes);
            if (x == null) { Status.put("6.audio.src" + s, "track creation failed"); continue; }
            if (verify(x)) { t = x; used = s; break; }
            Status.put("6.audio.src" + s, "not routed/consumed -> skipped");
            x.release();
        }
        if (t == null) return false;
        try {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { t.setPreferredDevice(d); break; }
            }
        } catch (Throwable e) { L.e("preferred device", e); }
        bufFrames = burst * 2;
        t.setBufferSizeInFrames(bufFrames);
        lastUnder = 0;
        track = t;
        appliedSrc = want;
        trackInfo = "src=" + (used == SRC_BEACON ? "BEACON(fixed volume)" : "ALARM") + " perfMode=" + t.getPerformanceMode()
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
        // softness: 2-pole low-pass, cutoff 1500 Hz (soft=0) .. 250 Hz (soft=1)
        double fc = 1500.0 * Math.pow(250.0 / 1500.0, cfg.soft);
        float alpha = (float) (1 - Math.exp(-2 * Math.PI * fc / SR));
        float g = cfg.gain * REF;
        boolean anti = cfg.phase == 1;
        for (int i = 0; i < n; i++) {
            lp1 += alpha * (mix[i] * g - lp1);
            lp2 += alpha * (lp1 - lp2);
            short s = (short) (Synth.limit(lp2) * 32767f);
            pcm[2 * i] = s;
            pcm[2 * i + 1] = anti ? (short) -s : s;
        }
        int w = track.write(pcm, 0, n * 2, AudioTrack.WRITE_BLOCKING);
        if (w < 0) throw new IllegalStateException("AudioTrack.write=" + w);
        framesWritten += w / 2;

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
