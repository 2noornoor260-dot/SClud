package com.audiohaptics.lsp;

import android.content.Context;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stage/status report written by system_server into Settings.Global so the app can show it without logcat.
 * Nothing is written until boot has completed (touching the settings provider earlier could stall system_server).
 */
final class Status {
    static final String KEY = "audio_haptics_status";
    private static final LinkedHashMap<String, String> map = new LinkedHashMap<>();
    private static volatile boolean bootDone;
    private static final ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AudioHaptics-status");
        t.setDaemon(true);
        return t;
    });

    static synchronized void put(String k, String v) {
        map.put(k, v);
        L.i(k + ": " + v);
        if (bootDone) ex.execute(Status::flush);
    }

    static synchronized void remove(String k) {
        if (map.remove(k) != null && bootDone) ex.execute(Status::flush);
    }

    static void bootCompleted() {
        bootDone = true;
        ex.execute(Status::flush);
    }

    private static synchronized String render() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        sb.append("updated: ").append(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
        return sb.toString();
    }

    private static void flush() {
        try {
            Context c = SysCtx.get();
            if (c != null) Settings.Global.putString(c.getContentResolver(), KEY, render());
        } catch (Throwable t) { L.e("status flush", t); }
    }
}
