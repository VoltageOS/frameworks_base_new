package app.grapheneos.goscompat.checks;

import android.net.Uri;

public final class GosCompatContract {
    public static final class App {
        public static final String PACKAGE_NAME = "app.grapheneos.goscompat.checks";

        private App() {
        }
    }

    public static final class MapsScan {
        public static final String PROCESS = App.PACKAGE_NAME + ":maps_scan";
        public static final String CRASH_ACTIVITY = App.PACKAGE_NAME + "/.MapsScanCrashActivity";
        public static final String AUTHORITY = App.PACKAGE_NAME + ".maps_scan_provider";
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
        public static final String RESULT_FILE = "maps_scan_result.properties";

        public static final class Extra {
            public static final String MODE = "maps_scan_mode";
            public static final String TOKEN = "maps_scan_token";

            private Extra() {
            }
        }

        public static final class Mode {
            public static final String DIRECT = "direct";
            public static final String REFLECTIVE = "reflective";

            private Mode() {
            }
        }

        public static final class Method {
            public static final String RUN_CHECK = "run_maps_scan_check";
            public static final String RUN_DIRECT_CHECK = "run_direct_maps_scan_check";
            public static final String RUN_REFLECTIVE_CHECK = "run_reflective_maps_scan_check";
            public static final String GET_RESULT = "get_maps_scan_result";
            public static final String CLEAR_RESULT = "clear_maps_scan_result";

            private Method() {
            }
        }

        public static final class Key {
            public static final String RESULT_AVAILABLE = "maps_scan_result_available";
            public static final String COMPLETED = "maps_scan_completed";
            public static final String WORKER_THREAD = "maps_scan_worker_thread";
            public static final String SELECTED_RANGES = "maps_scan_selected_ranges";
            public static final String SCANNED_BYTES = "maps_scan_scanned_bytes";
            public static final String CALLER_TID = "maps_scan_caller_tid";
            public static final String WORKER_TID = "maps_scan_worker_tid";
            public static final String STATUS_TEXT = "status_text";
            public static final String SUMMARY = "summary";
            public static final String DETAILS = "maps_scan_details";
            public static final String ERRORS = "maps_scan_errors";

            private Key() {
            }
        }

        private MapsScan() {
        }
    }

    public static final class WebViewUaStartup {
        public static final String ACTIVITY =
                App.PACKAGE_NAME + "/.webviewua.WebViewUaStartupActivity";
        public static final String RESULT_FILE = "webview_ua_startup_result.properties";

        public static final class Extra {
            public static final String TOKEN = "webview_ua_startup_token";
            public static final String EXIT_PROCESS = "webview_ua_startup_exit_process";

            private Extra() {
            }
        }

        public static final class Key {
            public static final String RESULT_AVAILABLE =
                    "webview_ua_startup_result_available";
            public static final String COMPLETED = "webview_ua_startup_completed";
            public static final String WORKER_THREAD = "webview_ua_startup_worker_thread";
            public static final String UI_THREAD = "webview_ua_startup_ui_thread";
            public static final String MAIN_LOOPER = "webview_ua_startup_main_looper";
            public static final String WORKER_TID = "webview_ua_startup_worker_tid";
            public static final String UI_TID = "webview_ua_startup_ui_tid";
            public static final String DURATION_MS = "webview_ua_startup_duration_ms";
            public static final String USER_AGENT_LENGTH =
                    "webview_ua_startup_user_agent_length";
            public static final String ERROR = "webview_ua_startup_error";

            private Key() {
            }
        }

        private WebViewUaStartup() {
        }
    }

    private GosCompatContract() {
    }
}
