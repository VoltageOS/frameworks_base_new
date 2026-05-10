package app.grapheneos.goscompat.checks;

import android.net.Uri;

public final class GosCompatContract {
    public static final String APP_PACKAGE = "app.grapheneos.goscompat.checks";
    public static final String MAPS_SCAN_PROCESS = APP_PACKAGE + ":maps_scan";
    public static final String ACTIVITY = APP_PACKAGE + "/.GosCompatCheckActivity";
    public static final String MAPS_SCAN_CRASH_ACTIVITY =
            APP_PACKAGE + "/.MapsScanCrashActivity";
    public static final String MAPS_SCAN_AUTHORITY = APP_PACKAGE + ".maps_scan_provider";
    public static final Uri MAPS_SCAN_CONTENT_URI = Uri.parse("content://" + MAPS_SCAN_AUTHORITY);
    public static final String MAPS_SCAN_RESULT_FILE = "maps_scan_result.properties";

    public static final String EXTRA_MAPS_SCAN_MODE = "maps_scan_mode";
    public static final String EXTRA_MAPS_SCAN_TOKEN = "maps_scan_token";
    public static final String MODE_DIRECT = "direct";
    public static final String MODE_REFLECTIVE = "reflective";

    public static final String METHOD_RUN_MAPS_SCAN_CHECK = "run_maps_scan_check";
    public static final String METHOD_RUN_DIRECT_MAPS_SCAN_CHECK = "run_direct_maps_scan_check";
    public static final String METHOD_RUN_REFLECTIVE_MAPS_SCAN_CHECK =
            "run_reflective_maps_scan_check";
    public static final String METHOD_GET_MAPS_SCAN_RESULT = "get_maps_scan_result";
    public static final String METHOD_CLEAR_MAPS_SCAN_RESULT = "clear_maps_scan_result";

    public static final String KEY_STATUS_TEXT = "status_text";
    public static final String KEY_SUMMARY = "summary";
    public static final String KEY_MAPS_SCAN_RESULT_AVAILABLE = "maps_scan_result_available";
    public static final String KEY_MAPS_SCAN_COMPLETED = "maps_scan_completed";
    public static final String KEY_MAPS_SCAN_WORKER_THREAD = "maps_scan_worker_thread";
    public static final String KEY_MAPS_SCAN_SELECTED_RANGES = "maps_scan_selected_ranges";
    public static final String KEY_MAPS_SCAN_SCANNED_BYTES = "maps_scan_scanned_bytes";
    public static final String KEY_MAPS_SCAN_CALLER_TID = "maps_scan_caller_tid";
    public static final String KEY_MAPS_SCAN_WORKER_TID = "maps_scan_worker_tid";
    public static final String KEY_MAPS_SCAN_DETAILS = "maps_scan_details";
    public static final String KEY_MAPS_SCAN_ERRORS = "maps_scan_errors";

    private GosCompatContract() {
    }
}
