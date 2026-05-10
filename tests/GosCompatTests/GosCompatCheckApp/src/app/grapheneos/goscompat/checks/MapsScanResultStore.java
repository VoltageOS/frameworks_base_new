package app.grapheneos.goscompat.checks;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

final class MapsScanResultStore {
    private static final String PREFS = "maps_scan_results";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_COMPLETED = "completed";
    private static final String KEY_WORKER_THREAD = "worker_thread";
    private static final String KEY_SELECTED_RANGES = "selected_ranges";
    private static final String KEY_SCANNED_BYTES = "scanned_bytes";
    private static final String KEY_CALLER_TID = "caller_tid";
    private static final String KEY_WORKER_TID = "worker_tid";

    private MapsScanResultStore() {
    }

    // Crash-mode tests start the scan asynchronously in an isolated process. A current-token
    // result here means that process completed the native scan without crashing.
    static void save(Context context, String token, MapsScanResult result) {
        if (token == null || token.isEmpty()) {
            return;
        }

        prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putBoolean(KEY_COMPLETED, result.isCompleted())
                .putBoolean(KEY_WORKER_THREAD, result.usedWorkerThread())
                .putInt(KEY_SELECTED_RANGES, result.getSelectedRanges())
                .putLong(KEY_SCANNED_BYTES, result.getScannedBytes())
                .putInt(KEY_CALLER_TID, result.getCallerTid())
                .putInt(KEY_WORKER_TID, result.getWorkerTid())
                .commit();

        Properties properties = new Properties();
        properties.setProperty(GosCompatContract.EXTRA_MAPS_SCAN_TOKEN, token);
        properties.setProperty(GosCompatContract.KEY_MAPS_SCAN_COMPLETED,
                Boolean.toString(result.isCompleted()));
        properties.setProperty(GosCompatContract.KEY_MAPS_SCAN_WORKER_THREAD,
                Boolean.toString(result.usedWorkerThread()));
        properties.setProperty(GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES,
                Integer.toString(result.getSelectedRanges()));
        properties.setProperty(GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES,
                Long.toString(result.getScannedBytes()));
        properties.setProperty(GosCompatContract.KEY_MAPS_SCAN_CALLER_TID,
                Integer.toString(result.getCallerTid()));
        properties.setProperty(GosCompatContract.KEY_MAPS_SCAN_WORKER_TID,
                Integer.toString(result.getWorkerTid()));
        try (FileOutputStream output = new FileOutputStream(resultFile(context))) {
            properties.store(output, null);
        } catch (IOException ignored) {
        }
    }

    static Bundle load(Context context, String token) {
        Bundle bundle = new Bundle();
        SharedPreferences prefs = prefs(context);
        if (token == null || !token.equals(prefs.getString(KEY_TOKEN, null))) {
            bundle.putBoolean(GosCompatContract.KEY_MAPS_SCAN_RESULT_AVAILABLE, false);
            return bundle;
        }

        boolean completed = prefs.getBoolean(KEY_COMPLETED, false);
        boolean workerThread = prefs.getBoolean(KEY_WORKER_THREAD, false);
        bundle.putBoolean(GosCompatContract.KEY_MAPS_SCAN_RESULT_AVAILABLE, true);
        bundle.putBoolean(GosCompatContract.KEY_MAPS_SCAN_COMPLETED, completed);
        bundle.putBoolean(GosCompatContract.KEY_MAPS_SCAN_WORKER_THREAD, workerThread);
        bundle.putInt(GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES,
                prefs.getInt(KEY_SELECTED_RANGES, 0));
        bundle.putLong(GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES,
                prefs.getLong(KEY_SCANNED_BYTES, 0));
        bundle.putInt(GosCompatContract.KEY_MAPS_SCAN_CALLER_TID,
                prefs.getInt(KEY_CALLER_TID, 0));
        bundle.putInt(GosCompatContract.KEY_MAPS_SCAN_WORKER_TID,
                prefs.getInt(KEY_WORKER_TID, 0));
        bundle.putString(GosCompatContract.KEY_STATUS_TEXT,
                completed && workerThread ? "Completed" : "Failed");
        bundle.putString(GosCompatContract.KEY_SUMMARY,
                completed && workerThread
                        ? "Native maps scan completed from a worker thread."
                        : "Native maps scan did not complete from a worker thread.");
        bundle.putStringArrayList(GosCompatContract.KEY_MAPS_SCAN_DETAILS, new ArrayList<>());
        bundle.putStringArrayList(GosCompatContract.KEY_MAPS_SCAN_ERRORS, new ArrayList<>());
        return bundle;
    }

    static Bundle clear(Context context) {
        prefs(context).edit().clear().commit();
        resultFile(context).delete();

        Bundle bundle = new Bundle();
        bundle.putBoolean(GosCompatContract.KEY_MAPS_SCAN_RESULT_AVAILABLE, false);
        return bundle;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File resultFile(Context context) {
        return new File(context.getFilesDir(), GosCompatContract.MAPS_SCAN_RESULT_FILE);
    }
}
