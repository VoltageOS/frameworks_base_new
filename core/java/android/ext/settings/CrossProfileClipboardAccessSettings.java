package android.ext.settings;

import android.annotation.NonNull;
import android.content.Context;
import android.provider.Settings;

/** @hide */
public class CrossProfileClipboardAccessSettings {

    public static final int FOLLOW_DEFAULT = 0;
    public static final int ALLOW_IMPORT_DEFAULTS_ONLY = 1;
    public static final int ALLOW_EXPORT_DEFAULTS_ONLY = 2;
    public static final int BLOCK = 3;

    public static final IntSetting CROSS_PROFILE_CLIPBOARD_ACCESS_SETTINGS = new IntSetting(
            Setting.Scope.PER_USER, Settings.Secure.CROSS_PROFILE_CLIPBOARD_ACCESS,
            // default
            FOLLOW_DEFAULT,
            // valid values
            FOLLOW_DEFAULT, ALLOW_IMPORT_DEFAULTS_ONLY, ALLOW_EXPORT_DEFAULTS_ONLY, BLOCK
    );

    public static boolean isExportAccessAllowed(@NonNull Context ctx, int userId) {
        int curSetting = CROSS_PROFILE_CLIPBOARD_ACCESS_SETTINGS.get(ctx, userId);
        return curSetting == FOLLOW_DEFAULT || curSetting == ALLOW_EXPORT_DEFAULTS_ONLY;
    }

    public static boolean isImportAccessAllowed(@NonNull Context ctx, int userId) {
        int curSetting = CROSS_PROFILE_CLIPBOARD_ACCESS_SETTINGS.get(ctx, userId);
        return curSetting == FOLLOW_DEFAULT || curSetting == ALLOW_IMPORT_DEFAULTS_ONLY;
    }
}
