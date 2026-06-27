package android.ext.settings;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.provider.Settings;

/** @hide */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
public class CertTransparencyDownloaderSetting {
    /** Use GrapheneOS server */
    public static final int VAL_GRAPHENEOS = 0;
    /** Use standard (Google) server */
    public static final int VAL_STANDARD = 1;
    /** Fail all CertificateTransparencyDownloader downloads */
    public static final int VAL_OFF = 2;

    @NonNull
    public static final IntSetting SETTING = new IntSetting(Setting.Scope.GLOBAL,
            Settings.Global.CERT_TRANSPARENCY_DOWNLOADER, VAL_GRAPHENEOS);

    private CertTransparencyDownloaderSetting() {}
}
