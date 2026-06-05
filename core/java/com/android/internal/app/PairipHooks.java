package com.android.internal.app;

import android.content.ContentProvider;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import static java.util.Objects.requireNonNull;

/**
 * Hooks for bypassing the Play Store automatic protection system (pairip) for apps that have their
 * integrity verified by the Play Store APK source stamp.
 * For more info, see https://support.google.com/googleplay/android-developer/answer/10183279
 *
 * @hide
 */
public class PairipHooks {
    private static final String TAG = "PairipHooks";

    public static boolean shouldSkipOnCreate(ContentProvider p) {
        // startsWith() is used since the class name might be suffixed with a number in some cases
        if (!p.getClass().getName().startsWith("com.pairip.licensecheck.LicenseContentProvider")) {
            return false;
        }
        if (shouldBypass(p.requireContext())) {
            Log.d(TAG, "skipping pairip LicenseContentProvider checks");
            return true;
        }
        return false;
    }

    public static String maybeReplaceApplicationClassName(Context context, ClassLoader classLoader,
                                                          String className) throws ClassNotFoundException {
        if (!"com.pairip.application.Application".equals(className)) {
            return className;
        }
        if (!shouldBypass(context)) {
            return className;
        }
        Class cls = classLoader.loadClass(className);
        String res = requireNonNull(cls.getSuperclass()).getName();
        Log.d(TAG, "replaced pairip Application class with its parent " + className);
        return res;
    }

    private static boolean shouldBypass(Context context) {
        boolean res = context.getApplicationInfo().hasPlayStoreSourceStamp();
        if (res && android.os.Flags.isDevBuild()) {
            if (Settings.Global.getInt(context.getContentResolver(), "skip_pairip_bypass", 0) == 1) {
                Log.d(TAG, "skip_pairip_bypass is set, keeping pairip check", new Throwable());
                return false;
            }
            Log.d(TAG, "bypassing pairip check", new Throwable());
        }
        return res;
    }
}
