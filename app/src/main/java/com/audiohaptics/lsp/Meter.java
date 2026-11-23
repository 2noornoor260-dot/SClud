package com.audiohaptics.lsp;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;

/** RMS of the high-passed accelerometer signal, in milli-g (gravity and slow motion removed). */
final class Meter implements SensorEventListener {
    private final double[] lp = new double[3];
    private long lastTs;
    private double sumSq;
    private int n;

    @Override public synchronized void onSensorChanged(SensorEvent e) {
        long ts = e.timestamp;
        if (lastTs == 0) {
            for (int i = 0; i < 3; i++) lp[i] = e.values[i];
            lastTs = ts;
            return;
        }
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
        sumSq += sq;
        n++;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    synchronized void reset() { sumSq = 0; n = 0; }

    synchronized double rms() { return n > 0 ? Math.sqrt(sumSq / n) / 9.80665 * 1000.0 : 0.0; }
}
