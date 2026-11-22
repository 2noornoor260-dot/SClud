package com.audiohaptics.lsp;

import android.util.Log;

final class L {
    static final String TAG = "AudioHaptics";
    static void i(String m) { Log.i(TAG, m); }
    static void e(String m, Throwable t) { Log.e(TAG, m, t); }
}
