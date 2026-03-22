package com.android.internal.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

public class SidebarUtils {
    public static boolean isSmartClipboardEnabled(Context context) {
        if (Settings.System.getInt(context.getContentResolver(), "sidebar_smart_clipboard", 0) == 0) {
            return false;
        }
        try {
            context.getPackageManager().getPackageInfo("com.libremobileos.sidebar", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
