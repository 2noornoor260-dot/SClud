package com.audiohaptics.lsp;

import android.content.Context;

final class SysCtx {
    static Context get() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("currentActivityThread").invoke(null);
            return t == null ? null : (Context) at.getMethod("getSystemContext").invoke(t);
        } catch (Throwable e) { return null; }
    }
}
