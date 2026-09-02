package com.openminis.assisthook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Evolution X / AOSP assistant bridge for Miniss.
 *
 * Hooks SystemUI's AssistManager.startAssist(...) and redirects hardware or
 * gesture invocations to Miniss' exported AssistTriggerService. The service is
 * used instead of launching MainActivity directly so Miniss can capture the
 * current screen before its own window becomes foreground, mark the invocation
 * as an assist request, then open a fresh chat through the normal NewChat route.
 *
 * The stock Android assistant flow is suppressed only after startService()
 * succeeds. Any ROM/API mismatch fails open and leaves SystemUI untouched.
 */
public final class SystemAssistRedirect implements IXposedHookLoadPackage {

    private static final String TAG = "MinissAssistHook";
    private static final String SYSTEM_UI = "com.android.systemui";

    private static final String MINIS_PACKAGE = "com.openminis.app";
    private static final String MINIS_TRIGGER_SERVICE =
            "com.openminis.app.assist.AssistTriggerService";

    private static final String EXTRA_ATTACH_SCREEN =
            "com.openminis.hook.attach_screen";
    private static final String EXTRA_INVOCATION_TYPE =
            "com.openminis.hook.invocation_type";

    // AOSP AssistUtils invocation_type values.
    private static final int INVOCATION_UNKNOWN = 0;
    private static final int INVOCATION_GESTURE = 1;
    private static final int INVOCATION_PHYSICAL_GESTURE = 2;
    private static final int INVOCATION_VOICE = 3;
    private static final int INVOCATION_QUICK_SEARCH_BAR = 4;
    private static final int INVOCATION_HOME_LONG_PRESS = 5;
    private static final int INVOCATION_POWER_LONG_PRESS = 6;
    private static final int INVOCATION_ASSIST_BUTTON = 7;
    private static final int INVOCATION_NAV_HANDLE_LONG_PRESS = 8;

    private static final long DEBOUNCE_MS = 900L;
    private static long lastRedirectElapsed = 0L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SYSTEM_UI.equals(lpparam.packageName)) return;

        log("loading in " + lpparam.packageName + " process=" + lpparam.processName);

        try {
            Class<?> assistManager = XposedHelpers.findClassIfExists(
                    "com.android.systemui.assist.AssistManager",
                    lpparam.classLoader
            );
            if (assistManager == null) {
                log("AssistManager class not found; leaving SystemUI untouched");
                return;
            }

            int hooked = XposedBridge.hookAllMethods(
                    assistManager,
                    "startAssist",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            maybeRedirect(param);
                        }
                    }
            ).size();
            log("hooked AssistManager.startAssist overloads=" + hooked);
        } catch (Throwable t) {
            log("failed to install hook: " + t);
        }
    }

    private static void maybeRedirect(XC_MethodHook.MethodHookParam param) {
        try {
            Bundle args = findBundleArg(param.args);
            int invocationType = args != null
                    ? args.getInt("invocation_type", INVOCATION_UNKNOWN)
                    : INVOCATION_UNKNOWN;

            // Keep voice hotword / search bar on the stock path. This module is
            // intentionally scoped to the physical/system assistant gestures.
            if (!isDirectGestureType(invocationType)) {
                log("pass through invocation_type=" + invocationType);
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastRedirectElapsed < DEBOUNCE_MS) {
                param.setResult(null);
                log("duplicate invocation suppressed type=" + invocationType);
                return;
            }

            Context context = resolveContext(param.thisObject);
            if (context == null) {
                log("no SystemUI Context; falling back to stock assist");
                return;
            }

            ComponentName target = new ComponentName(
                    MINIS_PACKAGE,
                    MINIS_TRIGGER_SERVICE
            );

            try {
                context.getPackageManager().getServiceInfo(target, 0);
            } catch (PackageManager.NameNotFoundException notInstalled) {
                log("Miniss trigger service not installed; falling back to stock assist");
                return;
            }

            boolean attachScreen = invocationType != INVOCATION_POWER_LONG_PRESS;
            Intent intent = new Intent()
                    .setComponent(target)
                    .putExtra(EXTRA_ATTACH_SCREEN, attachScreen)
                    .putExtra(EXTRA_INVOCATION_TYPE, invocationType);

            ComponentName started = context.startService(intent);
            if (started == null) {
                log("startService returned null; falling back to stock assist");
                return;
            }

            lastRedirectElapsed = now;
            param.setResult(null);
            log("redirected invocation_type=" + invocationType
                    + " attach_screen=" + attachScreen
                    + " via AssistTriggerService");
        } catch (Throwable t) {
            // Fail open: a SystemUI internal change must never break the stock
            // assistant gesture path.
            log("redirect failed, stock assist will continue: " + t);
        }
    }

    private static boolean isDirectGestureType(int type) {
        switch (type) {
            case INVOCATION_UNKNOWN:
            case INVOCATION_GESTURE:
            case INVOCATION_PHYSICAL_GESTURE:
            case INVOCATION_HOME_LONG_PRESS:
            case INVOCATION_POWER_LONG_PRESS:
            case INVOCATION_ASSIST_BUTTON:
            case INVOCATION_NAV_HANDLE_LONG_PRESS:
                return true;
            case INVOCATION_VOICE:
            case INVOCATION_QUICK_SEARCH_BAR:
            default:
                return false;
        }
    }

    private static Bundle findBundleArg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Bundle) return (Bundle) arg;
        }
        return null;
    }

    private static Context resolveContext(Object assistManager) {
        if (assistManager == null) return null;

        try {
            Object value = XposedHelpers.getObjectField(assistManager, "mContext");
            if (value instanceof Context) return (Context) value;
        } catch (Throwable ignored) {
            // Fall through to a ROM-agnostic field scan.
        }

        Class<?> current = assistManager.getClass();
        while (current != null && current != Object.class) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                current = current.getSuperclass();
                continue;
            }

            for (Field field : fields) {
                if (!Context.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(assistManager);
                    if (value instanceof Context) return (Context) value;
                } catch (Throwable ignored) {
                    // Try the next candidate field.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }
}
