package android.ext.settings;

import android.provider.Settings;

/** @hide */
public class NetworkLocationSettings {

    public static final int NETWORK_LOCATION_DISABLED = 0;
    public static final int NETWORK_LOCATION_APPLE = 1;
    public static final int NETWORK_LOCATION_GRAPHENEOS_APPLE_PROXY = 2;
    public static final int NETWORK_LOCATION_APPLE_CHINA = 3;

    public static final IntSetting NETWORK_LOCATION_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.NETWORK_LOCATION,
            NETWORK_LOCATION_DISABLED, // default
            NETWORK_LOCATION_APPLE_CHINA, NETWORK_LOCATION_GRAPHENEOS_APPLE_PROXY, NETWORK_LOCATION_APPLE, NETWORK_LOCATION_DISABLED // valid values
    );
}