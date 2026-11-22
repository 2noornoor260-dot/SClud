package com.audiohaptics.lsp;

import android.os.IInterface;
import android.os.RemoteCallbackList;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Android 17 routes every vibration through the interfaces HalVibratorManager / HalVibrator.
 * We hand the framework Proxy implementations of both: one vibrator (id 0) that plays through HapticEngine.
 * Interface shapes were verified against this ROM's services.jar (on(vibrationId, stepId, ms|segment)).
 */
final class FakeVibrator {
    static final int ID = 0;
    // ON_CALLBACK | PERFORM_CALLBACK | AMPLITUDE_CONTROL | COMPOSE_EFFECTS
    static final long CAPS = 1 | 2 | 4 | 32;

    private final ClassLoader cl;
    private final HapticEngine engine;
    private final RemoteCallbackList<IInterface> listeners = new RemoteCallbackList<>();
    private Method onVibrating;
    private Method cbComplete;
    private volatile Object vibCallbacks;
    private Object info;
    private Object vibProxy, mgrProxy;

    static Object createManager(ClassLoader cl) throws Throwable {
        FakeVibrator f = new FakeVibrator(cl);
        return f.mgrProxy;
    }

    private FakeVibrator(ClassLoader cl) throws Throwable {
        this.cl = cl;
        engine = new HapticEngine(this::notifyState);
        Class<?> mgrI = cl.loadClass("com.android.server.vibrator.HalVibratorManager");
        Class<?> vibI = cl.loadClass("com.android.server.vibrator.HalVibrator");
        cbComplete = cl.loadClass("com.android.server.vibrator.HalVibrator$Callbacks")
                .getMethod("onVibrationStepComplete", int.class, long.class, long.class);
        try { onVibrating = Class.forName("android.os.IVibratorStateListener").getMethod("onVibrating", boolean.class); }
        catch (Throwable t) { L.e("IVibratorStateListener", t); }
        info = buildInfo();
        vibProxy = Proxy.newProxyInstance(cl, new Class<?>[]{vibI}, (InvocationHandler) this::vibrator);
        mgrProxy = Proxy.newProxyInstance(cl, new Class<?>[]{mgrI}, (InvocationHandler) this::manager);
    }

    // ---------------------------------------------------------------- VibratorInfo

    private Object buildInfo() throws Throwable {
        Class<?> b = Class.forName("android.os.VibratorInfo$Builder");
        Object bld = b.getConstructor(int.class).newInstance(ID);
        b.getMethod("setCapabilities", long.class).invoke(bld, CAPS);
        b.getMethod("setSupportedEffects", int[].class).invoke(bld, (Object) Synth.SUPPORTED_EFFECTS);
        Method prim = b.getMethod("setSupportedPrimitive", int.class, int.class);
        for (int p : Synth.PRIMITIVES) prim.invoke(bld, p, Synth.primDur(p));
        b.getMethod("setCompositionSizeMax", int.class).invoke(bld, 32);
        b.getMethod("setPrimitiveDelayMax", int.class).invoke(bld, 1000);
        return b.getMethod("build").invoke(bld);
    }

    // ---------------------------------------------------------------- HalVibratorManager

    private Object manager(Object proxy, Method m, Object[] a) {
        try {
            if (m.getDeclaringClass() == Object.class) return objectMethod(proxy, m, a);
            switch (m.getName()) {
                case "getVibratorIds": return new int[]{ID};
                case "getVibrator": return ((Integer) a[0]) == ID ? vibProxy : null;
                case "getCapabilities": return 0L;
                case "hasCapability": return ((Long) a[0]) == 0L;
                case "init":
                    if (a != null && a.length > 1 && a[1] != null) vibCallbacks = a[1];
                    engine.start();
                    return null;
                case "onSystemReady": engine.start(); return null;
                case "dump": dump(a[0]); return null;
                default: return def(m.getReturnType());
            }
        } catch (Throwable t) { L.e("manager." + m.getName(), t); return def(m.getReturnType()); }
    }

    // ---------------------------------------------------------------- HalVibrator

    private Object vibrator(Object proxy, Method m, Object[] a) {
        try {
            if (m.getDeclaringClass() == Object.class) return objectMethod(proxy, m, a);
            switch (m.getName()) {
                case "init": if (a != null && a.length > 0 && a[0] != null) vibCallbacks = a[0]; return null;
                case "onSystemReady": engine.start(); return null;
                case "getInfo": return info;
                case "isVibrating": return engine.isVibrating();
                case "getCurrentAmplitude": return engine.currentAmp;
                case "off": engine.off(); return Boolean.TRUE;
                case "setAmplitude": engine.setAmplitude((Float) a[0]); return Boolean.TRUE;
                case "registerVibratorStateListener": return listeners.register((IInterface) a[0]);
                case "unregisterVibratorStateListener": return listeners.unregister((IInterface) a[0]);
                case "dump": dump(a[0]); return null;
                case "on": return on(m.getParameterTypes()[2], a);
                default: return def(m.getReturnType());   // setExternalControl, setAlwaysOn -> false
            }
        } catch (Throwable t) { L.e("vibrator." + m.getName(), t); return def(m.getReturnType()); }
    }

    /** on(vibrationId, stepId, ms | PrebakedSegment | PrimitiveSegment[] | PwlePoint[] | VendorEffect) -> duration ms, 0 = unsupported. */
    private long on(Class<?> third, Object[] a) throws Throwable {
        long vibId = (Long) a[0], stepId = (Long) a[1];
        engine.start();
        long dur;
        if (third == long.class) {
            dur = Math.max(1, Math.min((Long) a[2], 30_000L));
            engine.sustained(dur);
        } else if (third.getSimpleName().equals("PrebakedSegment")) {
            Object seg = a[2];
            int id = (Integer) seg.getClass().getMethod("getEffectId").invoke(seg);
            int strength = (Integer) seg.getClass().getMethod("getEffectStrength").invoke(seg);
            dur = engine.effect(id, strength);
        } else if (third.getSimpleName().equals("PrimitiveSegment[]")) {
            dur = composePrimitives((Object[]) a[2]);
        } else {
            return 0;   // PWLE / vendor effects are not advertised
        }
        if (dur > 0) complete(vibId, stepId, dur);
        return dur;
    }

    private long composePrimitives(Object[] segs) throws Throwable {
        long t = 0, prevStart = 0, end = 0;
        for (Object s : segs) {
            Class<?> c = s.getClass();
            int id = (Integer) c.getMethod("getPrimitiveId").invoke(s);
            float scale = (Float) c.getMethod("getScale").invoke(s);
            int delay = (Integer) c.getMethod("getDelay").invoke(s);
            int delayType = (Integer) c.getMethod("getDelayType").invoke(s);
            long start = delayType == 1 ? prevStart + delay : t + delay;   // 1 = RELATIVE_START_OFFSET
            if (id != 0) {
                engine.primitive(id, scale, (int) start);
                t = start + Synth.primDur(id);
            } else {
                t = start;
            }
            prevStart = start;
            end = Math.max(end, t);
        }
        long dur = Math.max(1, end);
        engine.busy(dur);
        return dur;
    }

    private void complete(final long vibId, final long stepId, long delayMs) {
        engine.post(() -> {
            try { Object cbs = vibCallbacks; if (cbs != null) cbComplete.invoke(cbs, ID, vibId, stepId); }
            catch (Throwable t) { L.e("callback", t); }
        }, delayMs);
    }

    private void notifyState(boolean vibrating) {
        if (onVibrating == null) return;
        int n = listeners.beginBroadcast();
        try {
            for (int i = 0; i < n; i++) {
                try { onVibrating.invoke(listeners.getBroadcastItem(i), vibrating); } catch (Throwable ignored) { }
            }
        } finally { listeners.finishBroadcast(); }
    }

    private void dump(Object pw) {
        if (pw instanceof PrintWriter) ((PrintWriter) pw).println("AudioHaptics fake vibrator: " + engine.status());
    }

    private Object objectMethod(Object proxy, Method m, Object[] a) {
        switch (m.getName()) {
            case "toString": return "AudioHapticsFakeVibrator";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == a[0];
            default: return null;
        }
    }

    private static Object def(Class<?> r) {
        if (r == void.class) return null;
        if (r == boolean.class) return Boolean.FALSE;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        return null;
    }
}
