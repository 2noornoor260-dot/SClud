package com.audiohaptics.lsp;

import android.content.Context;
import android.os.Handler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Scope: System Framework.
 * Injector.createHalVibratorManager is only 11 bytecode units, so AOT compilation inlines it into
 * VibratorManagerService.<init> and a hook on it never fires. We therefore hook the (non-inlinable) static factory
 * VintfHalVibratorManager.createHalVibratorManager as well, and deoptimize the constructor as a second safeguard.
 */
public class MainHook implements IXposedHookLoadPackage {
    private static final String PKG = "com.android.server.vibrator.";
    private final AtomicInteger calls = new AtomicInteger();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"android".equals(lp.packageName)) return;
        Status.put("1.loaded", "module entered system_server");
        if (!Guard.allowed()) { Status.put("2.hooks", "BLOCKED by crash guard / disable file"); return; }
        final ClassLoader cl = lp.classLoader;
        final StringBuilder hooked = new StringBuilder();

        XC_MethodHook factoryHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) { install(cl, p); }
        };

        try {
            Class<?> vint = XposedHelpers.findClass(PKG + "VintfHalVibratorManager", cl);
            Class<?> nativeHandler = XposedHelpers.findClass(PKG + "HalNativeHandler", cl);
            XposedHelpers.findAndHookMethod(vint, "createHalVibratorManager", Handler.class, nativeHandler, factoryHook);
            hooked.append("static-factory ");
        } catch (Throwable t) { hooked.append("static-factory:FAILED(").append(t).append(") "); }

        Class<?> inj = null;
        try {
            inj = XposedHelpers.findClass(PKG + "VibratorManagerService$Injector", cl);
            XposedHelpers.findAndHookMethod(inj, "createHalVibratorManager", Handler.class, factoryHook);
            hooked.append("injector ");
        } catch (Throwable t) { hooked.append("injector:FAILED(").append(t).append(") "); }

        try {
            Class<?> svc = XposedHelpers.findClass(PKG + "VibratorManagerService", cl);
            try {   // LSPosed extension: force interpreter so inlined callees go through hooks
                Method d = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
                for (Constructor<?> c : svc.getDeclaredConstructors()) d.invoke(null, c);
                hooked.append("deopt-ctor ");
            } catch (Throwable t) { hooked.append("deopt:unavailable "); }
            if (inj != null) {
                XposedHelpers.findAndHookConstructor(svc, Context.class, inj, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { reportService(p.thisObject); }
                });
                hooked.append("ctor-report ");
            }
        } catch (Throwable t) { hooked.append("service:FAILED(").append(t).append(") "); }

        Status.put("2.hooks", hooked.toString().trim());
        Guard.watchBoot();
    }

    private void install(ClassLoader cl, XC_MethodHook.MethodHookParam p) {
        int n = calls.incrementAndGet();
        try {
            Object orig = p.getResult();
            if (orig != null && Proxy.isProxyClass(orig.getClass())) return;   // already replaced by us
            int[] ids = null;
            try {
                ids = (int[]) cl.loadClass(PKG + "HalVibratorManager").getMethod("getVibratorIds").invoke(orig);
            } catch (Throwable ignored) { }
            boolean hal = realHalDeclared();
            if (hal || (ids != null && ids.length > 0)) {
                Status.put("3.factory", "call#" + n + ": real vibrator (hal=" + hal + ", ids=" + Arrays.toString(ids) + ") -> untouched");
                return;
            }
            p.setResult(FakeVibrator.createManager(cl));
            Status.put("3.factory", "call#" + n + ": FAKE VIBRATOR INSTALLED");
        } catch (Throwable t) {
            Status.put("3.factory", "call#" + n + ": ERROR " + t);
            L.e("install failed, keeping the original manager", t);
        }
    }

    private static boolean realHalDeclared() {
        try {
            Method m = Class.forName("android.os.ServiceManager").getMethod("isDeclared", String.class);
            return (Boolean) m.invoke(null, "android.hardware.vibrator.IVibratorManager/default")
                    || (Boolean) m.invoke(null, "android.hardware.vibrator.IVibrator/default");
        } catch (Throwable t) { return false; }
    }

    private static void reportService(Object svc) {
        try {
            int[] ids = (int[]) svc.getClass().getMethod("getVibratorIds").invoke(svc);
            Status.put("3b.service", "VibratorManagerService ready, vibratorIds=" + Arrays.toString(ids));
        } catch (Throwable t) { Status.put("3b.service", "ctor done (ids unreadable: " + t + ")"); }
    }
}
