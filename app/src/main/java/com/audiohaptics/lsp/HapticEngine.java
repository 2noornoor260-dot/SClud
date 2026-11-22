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
 * One AudioTrack (LOW_LATENCY / FAST path) is kept running ("warm") and written one HAL burst at a time,
 * so a haptic request is mixed into the very next burst. Pulses are pre-rendered, overlapping, and soft-limited.
 */
final class HapticEngine implements Runnable {
    interface StateListener { void onState(boolean vibrating); }

    static final int SR = Synth.SR;
    private static final long KEEP_WARM_NS = 4_000_000_000L;

    static final class Voice {
        final float[] d; final float g; final int delay; long start;
        Voice(float[] d, float g, int delay) { this.d = d; this.g = g; this.delay = delay; }
    }

    final Config cfg = new Config();
    private final StateListener listener;
    private final HandlerThread cbThread = new HandlerThread("AudioHaptics-cb");
    private final Handler cb;

    private final ConcurrentLinkedQueue<Voice> inbox = new ConcurrentLinkedQueue<>();
    private final ArrayList<Voice> active = new ArrayList<>();
    private final AtomicBoolean vibState = new AtomicBoolean(false);

    private volatile boolean started, cancelPending;
    private volatile boolean screenOn = true;
    private volatile long warmUntil;
    private volatile long vibratingUntil;
    private volatile long susEnd;
    private volatile float susAmp = 1f;
    volatile float currentAmp = 1f;

    private float susCur;
    private double susPh;

    private Thread thread;
    private Context ctx;
    private AudioTrack track;
    private int burst = 240, bufFrames, lastUnder;
    private boolean playing;
    private long framesWritten, lastTune;
    private float[] mix = new float[240];
    private short[] pcm = new short[480];
    private String trackInfo = "not created";

    HapticEngine(StateListener l) {
        listener = l;
        cbThread.start();
        cb = new Handler(cbThread.getLooper());
    }

    // ------------------------------------------------------------------ public API (called from vibration thread)

    synchronized void start() {
        if (started) return;
        started = true;
        thread = new Thread(this, "AudioHaptics-engine");
        thread.setDaemon(true);
        thread.start();
        L.i("engine started");
    }

    void post(Runnable r, long delayMs) { cb.postDelayed(r, Math.max(0, delayMs)); }

    float strengthGain(int s) { return s <= 0 ? 0.7f : (s == 1 ? 1.0f : 1.4f); }

    /** Prebaked effect. Returns its duration in ms (0 = unsupported). */
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
        if (w.length > 0) addPulse(w, Config.clamp(scale, 0f, 1f), delayMs);
    }

    /** Continuous buzz for ms milliseconds (amplitude follows setAmplitude). */
    void sustained(long ms) {
        susEnd = System.nanoTime() + ms * 1_000_000L;
        susAmp = currentAmp;
        wake();
        busy(ms);
    }

    void setAmplitude(float a) {
        currentAmp = Config.clamp(a, 0f, 1f);
        susAmp = currentAmp;
    }

    void off() {
        susEnd = 0;
        cancelPending = true;
        wake();
    }

    boolean isVibrating() { return System.nanoTime() < vibratingUntil; }

    void busy(long ms) {
        long until = System.nanoTime() + ms * 1_000_000L;
        if (until > vibratingUntil) vibratingUntil = until;
        if (!vibState.getAndSet(true)) notifyState(true);
        cb.postDelayed(endCheck, ms + 3);
    }

    String status() {
        return "engine=" + (started ? "running" : "stopped") + " playing=" + playing + " burst=" + burst
                + " buf=" + bufFrames + " " + trackInfo + " gain=" + cfg.gain + " tone=" + cfg.tone + " warm=" + cfg.warm;
    }

    // ------------------------------------------------------------------ internals

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
        if (w == null || w.length == 0) return;
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
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("currentActivityThread").invoke(null);
            if (t == null) return null;
            Context c = (Context) at.getMethod("getSystemContext").invoke(t);
            if (c == null) return null;
            ctx = c;
            registerScreen(c);
        } catch (Throwable e) { L.e("ctx", e); }
        return ctx;
    }

    private void registerScreen(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) screenOn = pm.isInteractive();
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                    if (screenOn) wake();
                }
            }, f, null, cb);
        } catch (Throwable e) { L.e("screen receiver", e); }
    }

    private void boostThread() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
        try {   // SCHED_FIFO | RESET_ON_FORK
            Process.class.getMethod("setThreadScheduler", int.class, int.class, int.class)
                    .invoke(null, Process.myTid(), 1 | 0x40000000, 2);
            L.i("engine thread is SCHED_FIFO");
        } catch (Throwable ignored) { }
    }

    @Override public void run() {
        boostThread();
        long lastCfg = 0;
        while (started) {
            try {
                long now = System.nanoTime();
                if (now - lastCfg > 1_500_000_000L) { cfg.refresh(ctx()); lastCfg = now; }
                boolean work = !inbox.isEmpty() || !active.isEmpty() || now < susEnd || susCur > 1e-4f || cancelPending;
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
                L.e("engine loop", t);
                releaseTrack();
                LockSupport.parkNanos(1_000_000_000L);
            }
        }
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
        int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int capBytes = Math.max(min, burst * 4 * 4);

        AudioTrack t = null;
        for (int attempt = 0; attempt < 2 && t == null; attempt++) {
            try {
                AudioAttributes.Builder ab = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)            // independent of media volume / ringer mute
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
                if (attempt == 0) ab.setFlags(0x40 | 0x80);               // BYPASS_INTERRUPTION_POLICY | BYPASS_MUTE
                AudioTrack x = new AudioTrack.Builder().setAudioAttributes(ab.build()).setAudioFormat(fmt)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setBufferSizeInBytes(capBytes).build();
                if (x.getState() == AudioTrack.STATE_INITIALIZED) t = x; else x.release();
            } catch (Throwable e) { L.e("AudioTrack attempt " + attempt, e); }
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
        trackInfo = "perfMode=" + t.getPerformanceMode() + " cap=" + t.getBufferCapacityInFrames();
        L.i("track ready: burst=" + burst + " " + trackInfo + " (perfMode 1 = LOW_LATENCY)");
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
    }

    private void pump() {
        final int n = burst;
        Voice v;
        while ((v = inbox.poll()) != null) { v.start = framesWritten + v.delay; active.add(v); }
        if (cancelPending) {
            cancelPending = false;
            for (int i = active.size() - 1; i >= 0; i--) if (active.get(i).start > framesWritten) active.remove(i);
        }
        Arrays.fill(mix, 0, n, 0f);
        long base = framesWritten;
        for (int a = active.size() - 1; a >= 0; a--) {
            Voice x = active.get(a);
            long k0 = base - x.start;
            for (int i = 0; i < n; i++) {
                long k = k0 + i;
                if (k < 0) continue;
                if (k >= x.d.length) break;
                mix[i] += x.d[(int) k] * x.g;
            }
            if (base + n - x.start >= x.d.length) active.remove(a);
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
        float gain = cfg.gain;
        for (int i = 0; i < n; i++) {
            short s = Synth.toPcm(mix[i] * gain);
            pcm[2 * i] = s;
            pcm[2 * i + 1] = s;
        }
        int w = track.write(pcm, 0, n * 2, AudioTrack.WRITE_BLOCKING);
        if (w < 0) throw new IllegalStateException("AudioTrack.write=" + w);
        framesWritten += w / 2;

        long now = System.nanoTime();
        if (now - lastTune > 500_000_000L) {      // grow the buffer if the device underruns
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
