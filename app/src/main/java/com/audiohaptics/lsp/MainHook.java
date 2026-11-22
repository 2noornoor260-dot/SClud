package com.audiohaptics.lsp;

import android.os.Handler;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Scope: System Framework. Replaces the empty HAL vibrator manager with a speaker-backed one. */
public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;
        if (!Guard.allowed()) return;
        try {
            final ClassLoader cl = lp.classLoader;
            Class<?> inj = XposedHelpers.findClass("com.android.server.vibrator.VibratorManagerService$Injector", cl);
            XposedHelpers.findAndHookMethod(inj, "createHalVibratorManager", Handler.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object orig = p.getResult();
                        if (orig != null) {
                            int[] ids = (int[]) orig.getClass().getMethod("getVibratorIds").invoke(orig);
                            if (ids != null && ids.length > 0) { L.i("real vibrator present, leaving untouched"); return; }
                        }
                        p.setResult(FakeVibrator.createManager(cl));
                        L.i("speaker vibrator installed");
                        XposedBridge.log("AudioHaptics: speaker vibrator installed");
                    } catch (Throwable t) {
                        L.e("install failed, keeping the original manager", t);
                    }
                }
            });
            Guard.watchBoot();
            L.i("hooked VibratorManagerService$Injector.createHalVibratorManager");
        } catch (Throwable t) {
            L.e("hook failed", t);
            XposedBridge.log("AudioHaptics: hook failed " + t);
        }
    }
}
