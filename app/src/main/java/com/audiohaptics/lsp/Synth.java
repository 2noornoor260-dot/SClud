package com.audiohaptics.lsp;

import java.util.HashMap;

/** Waveform recipes. Short enveloped sine bursts around the speaker's usable low end + a tiny transient "snap". */
final class Synth {
    static final int SR = 48000;
    static final float BUZZ_HZ = 140f;

    static final int[] SUPPORTED_EFFECTS = {0, 1, 2, 3, 4, 5, 21};   // CLICK DOUBLE TICK THUD POP HEAVY TEXTURE_TICK
    static final int[] PRIMITIVES = {1, 2, 3, 4, 5, 6, 7, 8};

    static int primDur(int id) {
        switch (id) {
            case 1: return 28; case 2: return 60; case 3: return 45; case 4: return 50;
            case 5: return 120; case 6: return 35; case 7: return 14; case 8: return 22;
            default: return 0;
        }
    }

    /** base durations (ms) at punch = 1 */
    static int effectBase(int id) {
        switch (id) {
            case 0: return 32; case 2: return 20; case 3: return 70; case 4: return 28; case 5: return 50; case 21: return 14;
            default: return 0;
        }
    }

    static int effectDur(int id, float punch) {
        if (id == 1) return 2 * Math.round(effectBase(0) * punch) + 45;
        return Math.round(effectBase(id) * punch);
    }

    private static final HashMap<Integer, float[]> cache = new HashMap<>();
    private static float cacheKey = -1f;

    /** id 0..21 = prebaked effects, 100+n = primitive n. */
    static synchronized float[] effect(int id, float tone, float punch) {
        float key = tone * 1000f + punch;
        if (key != cacheKey) { cache.clear(); cacheKey = key; }
        float[] w = cache.get(id);
        if (w == null) { w = build(id, tone, punch); cache.put(id, w); }
        return w;
    }

    private static float[] build(int id, float t, float k) {
        switch (id) {
            case 0: case 101: return burst(150 * t, 150 * t, 32 * k, 2.0f, 0f, 5f, 0.90f, 0f, 0.30f);
            case 2: case 107: return burst(210 * t, 210 * t, 20 * k, 1.5f, 0f, 4f, 0.60f, 0f, 0.25f);
            case 3: case 102: return burst(100 * t, 90 * t, 70 * k, 3.0f, 0f, 12f, 1.00f, 0f, 0.30f);
            case 4: return burst(185 * t, 165 * t, 28 * k, 2.0f, 0f, 5f, 0.80f, 0f, 0.30f);
            case 5: return burst(125 * t, 115 * t, 50 * k, 2.5f, 0f, 8f, 1.00f, 0f, 0.30f);
            case 21: return burst(240 * t, 240 * t, 14 * k, 1.0f, 0f, 3f, 0.40f, 0f, 0.20f);
            case 103: return burst(80 * t, 140 * t, 45, 4f, 0f, 8f, 0.80f, 0f, 0.25f);
            case 104: return burst(140 * t, 170 * t, 50, 44f, 0f, 4f, 0.80f, 0f, 0.25f);
            case 105: return burst(130 * t, 170 * t, 120, 110f, 0f, 8f, 0.85f, 0f, 0.25f);
            case 106: return burst(170 * t, 125 * t, 35, 1.5f, 12f, 4f, 0.80f, 0f, 0.25f);
            case 108: return burst(150 * t, 140 * t, 22 * k, 1.2f, 0f, 4f, 0.50f, 0f, 0.25f);
            default: return new float[0];
        }
    }

    /** f0->f1 sine, smoothstep attack, exponential decay (tau>0) or hold, linear release at the end. */
    static float[] burst(float f0, float f1, float durMs, float attMs, float tauMs, float relMs, float amp, float snap, float harm) {
        int n = Math.max(8, (int) (SR * durMs / 1000f));
        float[] o = new float[n];
        int att = Math.max(1, (int) (SR * attMs / 1000f));
        int rel = Math.max(1, (int) (SR * relMs / 1000f));
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double f = f0 + (f1 - f0) * (i / (double) n);
            ph += 2 * Math.PI * f / SR;
            float env;
            if (i < att) { float x = i / (float) att; env = x * x * (3 - 2 * x); }
            else env = tauMs > 0 ? (float) Math.exp(-(i - att) / (SR * tauMs / 1000f)) : 1f;
            if (i > n - rel) env *= (n - i) / (float) rel;
            o[i] = (float) (Math.sin(ph) + harm * Math.sin(2 * ph)) / (1f + harm) * env * amp;
        }
        if (snap > 0) {
            int sn = (int) (SR * 0.0012f);
            for (int i = 0; i < sn && i < n; i++) {
                float e = (float) Math.exp(-i / (SR * 0.0004f));
                o[i] += snap * amp * e * (float) Math.sin(2 * Math.PI * 1800 * i / SR);
            }
        }
        return o;
    }

    /** Soft limiter (protects the speakers). */
    static float limit(float x) {
        float a = Math.abs(x), y = a < 0.7f ? a : 0.7f + 0.3f * (float) Math.tanh((a - 0.7f) / 0.3f);
        y *= 0.97f;
        return x < 0 ? -y : y;
    }
}
