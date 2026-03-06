package android.ext.settings;

import android.provider.Settings;

/** @hide */
public class GeocoderSettings {

    public static final int GEOCODER_DISABLED = 0;
    public static final int GEOCODER_SERVER_OPENSTREETMAP = 1;
    public static final int GEOCODER_SERVER_GRAPHENEOS = 2;

    public static final IntSetting GEOCODER_SETTING = new IntSetting(
            Setting.Scope.GLOBAL, Settings.Global.GEOCODER,
            GEOCODER_DISABLED, // default
            GEOCODER_SERVER_GRAPHENEOS, GEOCODER_SERVER_OPENSTREETMAP, GEOCODER_DISABLED // valid values
    );
}
