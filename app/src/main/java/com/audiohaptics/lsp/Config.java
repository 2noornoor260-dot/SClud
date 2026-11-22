package com.audiohaptics.lsp;

import android.content.Context;
import android.provider.Settings;

/** Settings written by the UI app into Settings.Global ("g=1.00;t=1.00;w=1"). */
final class Config {
    static final String KEY = "audio_haptics_cfg";
    volatile float gain = 1.0f;   // master strength
    volatile float tone = 1.0f;   // carrier frequency multiplier
    volatile int warm = 1;        // 0 = on demand, 1 = while screen is on, 2 = always

    void refresh(Context c) {
        if (c == null) return;
        try {
            String s = Settings.Global.getString(c.getContentResolver(), KEY);
            if (s == null) return;
            for (String kv : s.split(";")) {
                String[] p = kv.split("=");
                if (p.length != 2) continue;
                switch (p[0]) {
                    case "g": gain = clamp(Float.parseFloat(p[1]), 0f, 2.5f); break;
                    case "t": tone = clamp(Float.parseFloat(p[1]), 0.5f, 2f); break;
                    case "w": warm = Math.max(0, Math.min(2, Integer.parseInt(p[1]))); break;
                }
            }
        } catch (Throwable ignored) { }
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
