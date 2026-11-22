package com.audiohaptics.lsp;

import android.content.Context;
import android.provider.Settings;

/** Settings written by the UI app into Settings.Global ("e=1;m=1;p=1;k=0;g=1.00;t=1.00;s=0.60;w=1"). */
final class Config {
    static final String KEY = "audio_haptics_cfg";
    volatile boolean enabled = true;      // e: master switch
    volatile boolean muteOnSilent = true; // m: no haptics while the ringer is on silent
    volatile int phase = 1;               // p: 0 = in-phase (strong), 1 = anti-phase (quiet, dipole cancellation)
    volatile int src = 0;                 // k: 0 = auto, 1 = fixed-volume speaker (BEACON), 2 = alarm stream
    volatile float gain = 1.0f;           // g: strength (own control, 0..3)
    volatile float tone = 1.0f;           // t: carrier frequency multiplier
    volatile float soft = 0.6f;           // s: softness (low-pass), 0..1
    volatile int out = 0;                 // o: 0 = stereo (fast path), 1 = quad (4 channels, experimental)
    volatile int quadMode = 0;            // q: 0 all in phase, 1 left/right anti, 2 front/back anti, 3 diagonal anti
    volatile float punch = 1.8f;          // u: click length/energy multiplier
    volatile int labMode = 2, labFreq = 0; // lm / lf: speaker-lab tone (0 L only, 1 R only, 2 both, 3 anti-phase)
    volatile long sweep = 0;              // x: frequency-calibration sweep command id (0 = stop)
    volatile int warm = 1;                // w: 0 on demand, 1 while screen on, 2 always

    void refresh(Context c) {
        if (c == null) return;
        try {
            String s = Settings.Global.getString(c.getContentResolver(), KEY);
            if (s == null) return;
            for (String kv : s.split(";")) {
                String[] p = kv.split("=");
                if (p.length != 2) continue;
                switch (p[0]) {
                    case "e": enabled = !p[1].equals("0"); break;
                    case "m": muteOnSilent = !p[1].equals("0"); break;
                    case "p": phase = Math.max(0, Math.min(1, Integer.parseInt(p[1]))); break;
                    case "k": src = Math.max(0, Math.min(2, Integer.parseInt(p[1]))); break;
                    case "g": gain = clamp(Float.parseFloat(p[1]), 0f, 3f); break;
                    case "t": tone = clamp(Float.parseFloat(p[1]), 0.4f, 3f); break;
                    case "x": sweep = Long.parseLong(p[1]); break;
                    case "o": out = Math.max(0, Math.min(1, Integer.parseInt(p[1]))); break;
                    case "q": quadMode = Math.max(0, Math.min(3, Integer.parseInt(p[1]))); break;
                    case "u": punch = clamp(Float.parseFloat(p[1]), 0.5f, 3.5f); break;
                    case "lm": labMode = Math.max(0, Math.min(9, Integer.parseInt(p[1]))); break;
                    case "lf": labFreq = Math.max(0, Math.min(8000, Integer.parseInt(p[1]))); break;
                    case "s": soft = clamp(Float.parseFloat(p[1]), 0f, 1f); break;
                    case "w": warm = Math.max(0, Math.min(2, Integer.parseInt(p[1]))); break;
                }
            }
        } catch (Throwable ignored) { }
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
