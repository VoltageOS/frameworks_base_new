package com.android.providers.settings;

import android.ext.settings.ConnChecksSetting;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

class SettingsParserState {
    private static final String TAG = "SettingsParserState";

    private final int type;

    private String captivePortalHttpsUrl;
    private String captivePortalMode;

    SettingsParserState(int type) {
        this.type = type;
    }

    // Return false to discard the setting
    boolean onSettingRead(String key, String val) {
        if (key == null) {
            return true;
        }

        switch (type) {
            case SettingsState.SETTINGS_TYPE_GLOBAL: {
                switch (key) {
                    case Settings.Global.CAPTIVE_PORTAL_HTTPS_URL:
                        captivePortalHttpsUrl = val;
                        // fallthrough
                    case Settings.Global.CAPTIVE_PORTAL_HTTP_URL:
                    case Settings.Global.CAPTIVE_PORTAL_FALLBACK_URL:
                    case Settings.Global.CAPTIVE_PORTAL_OTHER_FALLBACK_URLS:
                        // skip legacy captive portal detection URLs, remember one of them for
                        // migration in onFinish()
                        return false;
                    case Settings.Global.CAPTIVE_PORTAL_MODE:
                        // checked during migration of connectivity checks setting
                        captivePortalMode = val;
                        return false;
                    default:
                        break;
                }
                break;
            }
            case SettingsState.SETTINGS_TYPE_SECURE: {
                switch (key) {
                    case Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD:
                    case Settings.Secure.DEFAULT_INPUT_METHOD:
                    case Settings.Secure.DISABLED_SYSTEM_INPUT_METHODS:
                    case Settings.Secure.ENABLED_INPUT_METHODS:
                        if (isBootingInSafeMode()) {
                            // discard the IME settings to switch to the system keyboard
                            Log.d(TAG, "device is booting in safe mode, discarding IME setting \"" + key + " = " + val + '"');
                            return false;
                        }
                        break;
                    default:
                        break;
                }
                break;
            }
        }

        return true;
    }

    void onFinish() {
        switch (type) {
            case SettingsState.SETTINGS_TYPE_GLOBAL: {
                maybeMigrateConnChecksSetting();
                return;
            }
            case SettingsState.SETTINGS_TYPE_SECURE: {
                return;
            }
        }
    }

    private void maybeMigrateConnChecksSetting() {
        if (ConnChecksSetting.isSet()) {
            return;
        }

        final int val;
        boolean connChecksDisabled = Integer.toString(Settings.Global.CAPTIVE_PORTAL_MODE_IGNORE)
                .equals(captivePortalMode);

        if (connChecksDisabled) {
            val = ConnChecksSetting.VAL_DISABLED;
        } else {
            if ("https://www.google.com/generate_204".equals(captivePortalHttpsUrl)) {
                val = ConnChecksSetting.VAL_STANDARD;
            } else {
                val = ConnChecksSetting.VAL_GRAPHENEOS;
            }
        }

        ConnChecksSetting.put(val);
    }

    private static Boolean isSafeModeCache;

    private static boolean isBootingInSafeMode() {
        var cache = isSafeModeCache;
        if (cache != null) {
            return cache.booleanValue();
        }

        boolean isSafeMode = SystemProperties.getInt("persist.sys.safemode", 0) != 0;
        isSafeModeCache = Boolean.valueOf(isSafeMode);
        Log.d(TAG, "isSafeMode: " + isSafeMode);
        return isSafeMode;
    }
}
