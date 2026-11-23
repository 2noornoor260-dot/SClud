package com.audiohaptics.lsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Speaker haptics engine.
 *
 * Routing safety: the audio track exists ONLY while haptics are really active. It is released (not just paused) when
 * the master switch is off, the ringer is silent, a call is in progress, or any external audio device (AirPods,
 * bluetooth, wired, USB, HDMI) is connected. An always-open "sonification" stream was what made the audio policy move
 * media from the earbuds to the tablet speaker.
 *
 * Output: touch bus + alert bus -> low-pass -> soft limiter -> 4 channels with per-channel polarity (or stereo fallback).
 */
final class HapticEngine implements Runnable {
    interface StateListener { void onState(boolean vibrating); }

    static final int SR = Synth.SR;
    static final float REF = 0.5f;                       // digital peak at 100% strength
    private static final long KEEP_WARM_NS = 4_000_000_000L;
    private static final int SRC_BEACON = 1, SRC_ALARM = 2;
    private static final int M_ACTIVE = 0, M_OFF = 1, M_SILENT = 2, M_EXTERNAL = 3, M_CALL = 4;
    private static final String[] M_NAMES = {"active", "off", "silent", "external-audio", "call"};

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
    private volatile boolean screenOn = true, silent, external, inCall, calBusy, calCancel, calRunning;
    private volatile long warmUntil, vibratingUntil, susEnd;
    private volatile float susAmp = 1f;
    volatile float currentAmp = 1f;
    private volatile long lastPump = System.nanoTime();
    private volatile float comp = 1f;

    private float susCur, lp1, lp2;
    private double susPh, calPh;
    private float calComp = 1f;

    private Thread thread;
    private Context ctx;
    private AudioTrack track;
    private int burst = 240, bufFrames, lastUnder, appliedOut = -1, ch = 2, usedSrc, stalls;
    private boolean playing, receiversOk, observerOk, callbackOk;
    private long framesWritten, lastTune, lastAlive, lastCal = Long.MIN_VALUE;
    private float[] mix = new float[240], mixA = new float[240];
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
        if (!canPlay()) return;
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
        return "engine=" + (started ? "running" : "stopped") + " mode=" + M_NAMES[modeCode()] + " playing=" + playing
                + " burst=" + burst + " buf=" + bufFrames + " " + trackInfo;
    }

    // ------------------------------------------------------------------ state

    private int modeCode() {
        if (!cfg.enabled) return M_OFF;
        if (inCall) return M_CALL;
        if (cfg.muteOnSilent && silent) return M_SILENT;
        if (cfg.offWithExternal && external) return M_EXTERNAL;
        return M_ACTIVE;
    }

    private boolean canPlay() { return modeCode() == M_ACTIVE && !calBusy; }

    private final Runnable endCheck = new Runnable() {
        @Override public void run() {
            long left = vibratingUntil - System.nanoTime();
            if (left > 0) { cb.postDelayed(this, left / 1_000_000L + 2); return; }
            if (vibState.getAndSet(false)) notifyState(false);
        }
    };

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

    private void notifyState(boolean v) {
        try { listener.onState(v); } catch (Throwable t) { L.e("state", t); }
    }

    private void addPulse(float[] w, float gain, int delayMs) {
        if (w == null || w.length == 0 || !canPlay()) return;
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

    private static boolean isExternalType(int t) {
        switch (t) {
            case 3: case 4: case 5: case 6: case 7: case 8: case 9: case 10: case 11: case 12: case 13:
            case 22: case 23: case 26: case 27: case 29: case 30:
                return true;
            default:
                return false;
        }
    }

    /** Registered lazily and retried every second: services may not exist yet when the engine first starts. */
    private void ensureHooks(final Context c) {
        if (c == null) return;
        if (!receiversOk) {
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
                        else pollState(c);
                    }
                }, f, null, cb);
                receiversOk = true;
            } catch (Throwable e) { L.e("receivers (will retry)", e); }
        }
        if (!observerOk) {
            try {
                c.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Config.KEY), false,
                        new ContentObserver(cb) {
                            @Override public void onChange(boolean selfChange) {
                                cfg.refresh(c);
                                handleCommands();
                                wake();
                            }
                        });
                observerOk = true;
            } catch (Throwable e) { L.e("observer (will retry)", e); }
        }
        if (!callbackOk) {
            try {
                AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                    @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { pollState(c); wake(); }
                    @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { pollState(c); wake(); }
                }, cb);
                callbackOk = true;
            } catch (Throwable e) { L.e("device callback (will retry)", e); }
        }
    }

    /** Reads the real state (also polled every second as a safety net next to the callbacks). */
    private void pollState(Context c) {
        if (c == null) return;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null) screenOn = pm.isInteractive();
        } catch (Throwable ignored) { }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            boolean s = am.getRingerMode() == AudioManager.RINGER_MODE_SILENT;
            if (s && !silent) off();
            silent = s;
            inCall = am.getMode() != AudioManager.MODE_NORMAL;
            boolean ext = false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (isExternalType(d.getType())) { ext = true; break; }
            }
            external = ext;
        } catch (Throwable ignored) { }
    }

    private synchronized void handleCommands() {
        long id = cfg.cal;
        if (lastCal == Long.MIN_VALUE) { lastCal = id; return; }   // never start a stale command after a reboot
        if (id == lastCal) return;
        lastCal = id;
        if (id > 0) startCalibration(); else calCancel = true;
    }

    private void reportState() {
        String st = "mode=" + M_NAMES[modeCode()] + " enabled=" + cfg.enabled + " silent=" + silent + " external=" + external
                + " call=" + inCall + " out=" + (cfg.fastStereo ? "stereo" : "quad") + " touch=" + cfg.gain
                + " alerts=" + cfg.alertGain + " tone=" + cfg.tone + " soft=" + cfg.soft + " punch=" + cfg.punch
                + " signs=" + Arrays.toString(cfg.signs);
        if (!st.equals(lastState)) {
            lastState = st;
            Status.put("0.mode", M_NAMES[modeCode()]);
            Status.put("8.state", st);
        }
    }

    private void boostThread() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
        try {
            Process.class.getMethod("setThreadScheduler", int.class, int.class, int.class)
                    .invoke(null, Process.myTid(), 1 | 0x40000000, 2);
        } catch (Throwable ignored) { }
    }

    private float alarmComp(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            int idx = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            float cur = am.getStreamVolumeDb(AudioManager.STREAM_ALARM, idx, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            float top = am.getStreamVolumeDb(AudioManager.STREAM_ALARM, max, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
            return Config.clamp((float) Math.pow(10, (top - cur) / 20.0), 1f, 4f);
        } catch (Throwable e) { return 1f; }
    }

    // ------------------------------------------------------------------ main loop

    @Override public void run() {
        boostThread();
        long lastCfg = 0;
        while (started) {
            try {
                long now = System.nanoTime();
                if (now - lastCfg > 1_000_000_000L) {
                    lastCfg = now;
                    Context c = ctx();
                    cfg.refresh(c);
                    ensureHooks(c);
                    pollState(c);
                    handleCommands();
                    reportState();
                    if (c != null && track != null) comp = usedSrc == SRC_ALARM ? alarmComp(c) : 1f;
                    if (now - lastAlive > 5_000_000_000L) {
                        lastAlive = now;
                        Status.put("9.alive", new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date())
                                + " comp=" + String.format(Locale.US, "%.2f", comp));
                    }
                }
                lastPump = Math.max(lastPump, now - 1_000_000_000L);

                if (modeCode() != M_ACTIVE) {                // nothing may stay open while inactive
                    inbox.clear(); voices.clear(); susEnd = 0; susCur = 0;
                    if (track != null) releaseTrack();
                    LockSupport.parkNanos(150_000_000L);
                    continue;
                }
                if (track != null && appliedOut != (cfg.fastStereo ? 0 : 1)) releaseTrack();
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

    private AudioTrack build(int src, AudioFormat fmt, int capBytes, AudioManager am) {
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                AudioAttributes.Builder ab = new AudioAttributes.Builder();
                if (src == SRC_BEACON) {
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
        mixA = new float[burst];
        int wantOut = cfg.fastStereo ? 0 : 1;
        int[] order = {SRC_ALARM, SRC_BEACON};
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
        appliedOut = wantOut;
        Status.remove("6.audio.src1");
        Status.remove("6.audio.src2");
        lastPump = System.nanoTime();
        comp = used == SRC_ALARM ? alarmComp(c) : 1f;
        trackInfo = "out=" + (ch == 4 ? "QUAD" : "STEREO") + " src=" + (used == SRC_BEACON ? "BEACON" : "ALARM")
                + " perfMode=" + t.getPerformanceMode() + " " + trackInfo;
        Status.put("6.audio", "track ready burst=" + burst + " " + trackInfo);
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
        Arrays.fill(mixA, 0, n, 0f);
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
                mixA[i] += susCur * (float) (Math.sin(susPh) + 0.35 * Math.sin(2 * susPh)) / 1.35f;
            }
            if (susPh > 1e6) susPh %= 2 * Math.PI;
        }
        double fc = 1500.0 * Math.pow(250.0 / 1500.0, cfg.soft);   // softness: 1500 Hz (0) .. 250 Hz (1)
        float alpha = (float) (1 - Math.exp(-2 * Math.PI * fc / SR));
        float gT = cfg.gain * REF * comp, gA = cfg.alertGain * REF * comp;
        float[] sg = cfg.signs;
        for (int i = 0; i < n; i++) {
            lp1 += alpha * (mix[i] * gT + mixA[i] * gA - lp1);
            lp2 += alpha * (lp1 - lp2);
            float y = Synth.limit(lp2) * 32767f;
            if (ch == 2) {
                pcm[2 * i] = (short) (y * sg[0]);
                pcm[2 * i + 1] = (short) (y * sg[1]);
            } else {
                pcm[4 * i] = (short) (y * sg[0]);
                pcm[4 * i + 1] = (short) (y * sg[1]);
                pcm[4 * i + 2] = (short) (y * sg[2]);
                pcm[4 * i + 3] = (short) (y * sg[3]);
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

    // ------------------------------------------------------------------ auto-tune (measured with the accelerometer)

    private void startCalibration() {
        if (calRunning) return;
        if (modeCode() != M_ACTIVE) {
            Status.put("12.cal", "ABORT: haptics are not active now (" + M_NAMES[modeCode()] + ")");
            return;
        }
        calRunning = true;
        calCancel = false;
        calBusy = true;
        new Thread(this::calibrate, "AudioHaptics-cal").start();
    }

    private double calSegment(AudioTrack t, float[] sg, double freq, double sec, double skip, Meter m) {
        final int chunk = 480;
        short[] buf = new short[4 * chunk];
        long frames = (long) (sec * SR);
        double w = 2 * Math.PI * freq / SR;
        boolean reset = false, snapped = false;
        double snap = 0;
        float amp = (float) Math.min(0.9, 0.5 * calComp);
        for (long pos = 0; pos < frames && !calCancel; pos += chunk) {
            double t0 = pos / (double) SR;
            if (!reset && t0 >= skip) { m.reset(); reset = true; }
            if (!snapped && t0 >= sec - 0.06) { snap = m.rms(); snapped = true; }
            for (int i = 0; i < chunk; i++) {
                double te = (pos + i) / (double) SR;
                double ramp = Math.max(0, Math.min(1, Math.min(te, sec - te) / 0.03));
                calPh += w;
                short x = (short) (amp * ramp * Math.sin(calPh) * 32767.0);
                for (int k = 0; k < 4; k++) buf[4 * i + k] = (short) (x * sg[k]);
            }
            int wr = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
            if (wr < 0) throw new IllegalStateException("calibration write=" + wr);
        }
        if (!snapped) snap = m.rms();
        if (calPh > 1e6) calPh %= 2 * Math.PI;
        return snap;
    }

    private void calibrate() {
        AudioTrack t = null;
        SensorManager sm = null;
        Meter meter = new Meter();
        try {
            Context c = ctx();
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            Sensor acc = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) { Status.put("12.cal", "ERROR: no accelerometer"); return; }
            if (!sm.registerListener(meter, acc, 2500, cb)) sm.registerListener(meter, acc, SensorManager.SENSOR_DELAY_FASTEST, cb);
            calComp = alarmComp(c);

            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_QUAD).build();
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).setFlags(0x40 | 0x80).build();
            int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_QUAD, AudioFormat.ENCODING_PCM_16BIT);
            t = new AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(min, SR / 10 * 8)).build();
            setPreferred(t, am);
            t.play();

            Status.put("12.cal", "1/3 measuring the noise floor... keep the tablet still on a table");
            double base = calSegment(t, new float[]{0, 0, 0, 0}, 100, 1.2, 0.4, meter);

            double[] net = new double[8];
            int best = 0;
            for (int p = 0; p < 8 && !calCancel; p++) {
                float[] sg = {1f, (p & 1) != 0 ? -1f : 1f, (p & 2) != 0 ? -1f : 1f, (p & 4) != 0 ? -1f : 1f};
                Status.put("12.cal", "2/3 testing speaker combinations " + (p + 1) + "/8");
                double r = calSegment(t, sg, 150, 1.6, 0.6, meter);
                net[p] = Math.sqrt(Math.max(0, r * r - base * base));
                if (net[p] > net[best]) best = p;
            }
            if (calCancel) { Status.put("12.cal", "CANCELLED"); return; }
            if (net[best] < 2.5) {
                Status.put("12.cal", "ERROR: signal too weak (" + String.format(Locale.US, "%.1f", net[best])
                        + " mg). Put the tablet flat on a firm table and try again");
                return;
            }
            float[] bs = {1f, (best & 1) != 0 ? -1f : 1f, (best & 2) != 0 ? -1f : 1f, (best & 4) != 0 ? -1f : 1f};

            double[] fnet = new double[13];
            int fi = 0;
            for (int f = 70; f <= 190 && !calCancel; f += 10) {
                Status.put("12.cal", "3/3 finding the strongest frequency: " + f + " Hz");
                double r = calSegment(t, bs, f, 0.8, 0.4, meter);
                fnet[fi++] = Math.sqrt(Math.max(0, r * r - base * base));
            }
            if (calCancel) { Status.put("12.cal", "CANCELLED"); return; }
            int bi = 0;
            double bv = -1;
            for (int i = 0; i < 13; i++) {
                double s = 0.25 * fnet[Math.max(0, i - 1)] + 0.5 * fnet[i] + 0.25 * fnet[Math.min(12, i + 1)];
                if (s > bv) { bv = s; bi = i; }
            }
            int bestF = 70 + 10 * bi;
            float toneNew = Config.clamp(bestF / 150f, 0.5f, 1.3f);

            Map<String, String> up = new HashMap<>();
            up.put("qs", String.format(Locale.US, "%.1f,%.1f,%.1f,%.1f", bs[0], bs[1], bs[2], bs[3]));
            up.put("t", String.format(Locale.US, "%.2f", toneNew));
            Config.write(c, up);
            double ratio = net[best] / Math.max(0.1, net[5]);
            Status.put("12.cal", String.format(Locale.US,
                    "DONE: polarity=%.0f,%.0f,%.0f,%.0f frequency=%d Hz vibration=%.1f mg (x%.1f vs default)",
                    bs[0], bs[1], bs[2], bs[3], bestF, net[best], ratio));
        } catch (Throwable e) {
            Status.put("12.cal", "ERROR: " + e);
            L.e("calibration", e);
        } finally {
            try { if (sm != null) sm.unregisterListener(meter); } catch (Throwable ignored) { }
            try { if (t != null) t.release(); } catch (Throwable ignored) { }
            calBusy = false;
            calRunning = false;
        }
    }
}
