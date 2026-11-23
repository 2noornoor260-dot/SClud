package com.audiohaptics.lsp;

import android.content.Context;
import android.provider.Settings;

import java.util.LinkedHashMap;
import java.util.Map;

/** Settings shared between the app (writer) and system_server (reader) through Settings.Global. */
final class Config {
    static final String KEY = "audio_haptics_cfg2";

    volatile boolean enabled = true;          // e
    volatile boolean muteOnSilent = true;     // m
    volatile boolean offWithExternal = true;  // xo: stop while headphones / bluetooth / usb audio are connected
    volatile boolean fastStereo = false;      // f: stereo fast path instead of the 4-channel output
    volatile float gain = 1.0f;               // g: touch & typing strength
    volatile float alertGain = 1.0f;          // a: alerts & continuous vibration strength
    volatile float tone = 1.0f;               // t: carrier multiplier (set by auto-tune)
    volatile float soft = 0.6f;               // s
    volatile float punch = 1.8f;              // u: click length / energy
    volatile int warm = 1;                    // w
    // qs: per-channel polarity FL,FR,BL,BR. Measured on SM-T505N: the left pair is inverted relative to the right pair.
    volatile float[] signs = {1f, -1f, 1f, -1f};
    volatile long cal = 0;                    // c: auto-tune command id

    void refresh(Context c) {
        if (c == null) return;
        Map<String, String> m = parse(read(c));
        if (m.isEmpty()) return;
        try {
            for (Map.Entry<String, String> e : m.entrySet()) {
                String v = e.getValue();
                switch (e.getKey()) {
                    case "e": enabled = !v.equals("0"); break;
                    case "m": muteOnSilent = !v.equals("0"); break;
                    case "xo": offWithExternal = !v.equals("0"); break;
                    case "f": fastStereo = v.equals("1"); break;
                    case "g": gain = clamp(Float.parseFloat(v), 0f, 2.5f); break;
                    case "a": alertGain = clamp(Float.parseFloat(v), 0f, 2.5f); break;
                    case "t": tone = clamp(Float.parseFloat(v), 0.4f, 3f); break;
                    case "s": soft = clamp(Float.parseFloat(v), 0f, 1f); break;
                    case "u": punch = clamp(Float.parseFloat(v), 0.5f, 3.5f); break;
                    case "w": warm = Math.max(0, Math.min(2, Integer.parseInt(v))); break;
                    case "c": cal = Long.parseLong(v); break;
                    case "qs": {
                        String[] p = v.split(",");
                        if (p.length == 4) {
                            float[] s = new float[4];
                            for (int i = 0; i < 4; i++) s[i] = clamp(Float.parseFloat(p[i]), -1f, 1f);
                            signs = s;
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    static String read(Context c) {
        try { return Settings.Global.getString(c.getContentResolver(), KEY); } catch (Throwable t) { return null; }
    }

    static Map<String, String> parse(String s) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (s == null) return m;
        for (String kv : s.split(";")) {
            int i = kv.indexOf('=');
            if (i > 0) m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    /** Merges the updates into the stored string. Returns false when the write is not permitted. */
    static boolean write(Context c, Map<String, String> updates) {
        try {
            Map<String, String> m = parse(read(c));
            m.putAll(updates);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : m.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return Settings.Global.putString(c.getContentResolver(), KEY, sb.toString());
        } catch (Throwable t) { return false; }
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
