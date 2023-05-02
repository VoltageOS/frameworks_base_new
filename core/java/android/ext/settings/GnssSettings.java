package android.ext.settings;

import android.content.Context;
import android.provider.Settings;

/** @hide */
public class GnssSettings {

    public static final int SUPL_SERVER_STANDARD = 0;
    public static final int SUPL_DISABLED = 1;
    public static final int SUPL_SERVER_GRAPHENEOS_PROXY = 2;

    public static final IntSetting SUPL_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.GNSS_SUPL,
            SUPL_SERVER_GRAPHENEOS_PROXY, // default
            SUPL_SERVER_STANDARD, SUPL_DISABLED, SUPL_SERVER_GRAPHENEOS_PROXY // valid values
    );

    // keep in sync with bionic/libc/bionic/gnss_psds_setting.c
    public static final int PSDS_SERVER_GRAPHENEOS = 0;
    public static final int PSDS_SERVER_STANDARD = 1;
    public static final int PSDS_DISABLED = 2;

    public static final IntSetting STANDARD_PSDS_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.GNSS_PSDS_STANDARD,
            PSDS_SERVER_GRAPHENEOS, // default
            PSDS_SERVER_GRAPHENEOS, PSDS_SERVER_STANDARD, PSDS_DISABLED // valid values
    );

    public static IntSetting getPsdsSetting(Context ctx) {
        return STANDARD_PSDS_SETTING;
    }

    public static boolean isStandardPsds(Context ctx) {
        return getPsdsSetting(ctx) == STANDARD_PSDS_SETTING;
    }
}
