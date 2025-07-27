package android.ext.settings;

import android.provider.Settings;

/** @hide */
public class GeocoderSettings {

    public static final int GEOCODER_DISABLED = 0;
    public static final int GEOCODER_SERVER_NOMINATIM = 1;
    public static final int GEOCODER_SERVER_GRAPHENEOS_PROXY = 2;

    public static final IntSetting GEOCODER_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.GEOCODER,
            GEOCODER_DISABLED, // default
            GEOCODER_SERVER_GRAPHENEOS_PROXY, GEOCODER_SERVER_NOMINATIM, GEOCODER_DISABLED // valid values
    );
}
