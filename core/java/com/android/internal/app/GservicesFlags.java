package com.android.internal.app;

import android.util.Log;

/**
 * Process-local Gservices flags provider redirect.
 */
public class GservicesFlags {
    private static final String TAG = "GservicesFlags";

    public static final String GSERVICES_AUTHORITY = "com.google.android.gsf.gservices";
    private static final String GMSCOMPAT_GSERVICES_AUTHORITY =
            "app.grapheneos.gmscompat.gservices";

    private static volatile boolean isEnabled;

    public static void enable() {
        isEnabled = true;
        ContentProviderRedirector.enable();
    }

    public static String maybeTranslateAuthority(String auth) {
        if (!isEnabled) {
            return null;
        }

        if (GSERVICES_AUTHORITY.equals(auth)) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "redirecting authority " + GSERVICES_AUTHORITY
                        + " to " + GMSCOMPAT_GSERVICES_AUTHORITY);
            }
            return GMSCOMPAT_GSERVICES_AUTHORITY;
        }

        return null;
    }
}
