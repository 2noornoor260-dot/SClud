package com.audiohaptics.lsp;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Crash-loop protection: after 3 boots that never reach boot_completed, the hook disables itself. */
final class Guard {
    static final String DISABLE = "/data/system/audiohaptics.disable";
    static final String COUNTER = "/data/system/audiohaptics.boot";

    static boolean allowed() {
        try {
            if (new File(DISABLE).exists()) { L.i("disabled by " + DISABLE); return false; }
            int c = read();
            if (c >= 3) { L.i("crash guard tripped (" + c + " failed boots); delete " + COUNTER + " to re-enable"); return false; }
            write(c + 1);
        } catch (Throwable t) { L.e("guard", t); }
        return true;
    }

    static void watchBoot() {
        Thread t = new Thread(() -> {
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class);
                for (int i = 0; i < 200; i++) {
                    if ("1".equals(get.invoke(null, "sys.boot_completed"))) {
                        new File(COUNTER).delete();
                        L.i("boot completed, crash counter cleared");
                        Status.bootCompleted();
                        return;
                    }
                    Thread.sleep(3000);
                }
            } catch (Throwable e) { L.e("watchBoot", e); }
        }, "AudioHaptics-guard");
        t.setDaemon(true);
        t.start();
    }

    private static int read() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(COUNTER))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Throwable e) { return 0; }
    }

    private static void write(int v) throws Exception {
        try (FileOutputStream o = new FileOutputStream(COUNTER)) { o.write(Integer.toString(v).getBytes()); }
    }
}
