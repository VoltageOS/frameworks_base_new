package app.grapheneos.goscompat.checks;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MapsScanResult {
    private final boolean mCompleted;
    private final boolean mWorkerThread;
    private final int mSelectedRanges;
    private final long mScannedBytes;
    private final int mCallerTid;
    private final int mWorkerTid;
    private final ArrayList<String> mDetails;
    private final ArrayList<String> mErrors;

    MapsScanResult(boolean completed, boolean workerThread, int selectedRanges, long scannedBytes,
            int callerTid, int workerTid, ArrayList<String> details, ArrayList<String> errors) {
        mCompleted = completed;
        mWorkerThread = workerThread;
        mSelectedRanges = selectedRanges;
        mScannedBytes = scannedBytes;
        mCallerTid = callerTid;
        mWorkerTid = workerTid;
        mDetails = details;
        mErrors = errors;
    }

    public boolean isCompleted() {
        return mCompleted;
    }

    public boolean usedWorkerThread() {
        return mWorkerThread;
    }

    public int getSelectedRanges() {
        return mSelectedRanges;
    }

    public long getScannedBytes() {
        return mScannedBytes;
    }

    public int getCallerTid() {
        return mCallerTid;
    }

    public int getWorkerTid() {
        return mWorkerTid;
    }

    public String getStatusText() {
        return mCompleted && mWorkerThread ? "Completed" : "Failed";
    }

    public String getSummary() {
        if (mCompleted && mWorkerThread) {
            return "Native maps scan completed from a worker thread.";
        }
        if (!mWorkerThread) {
            return "Native maps scan did not run from a distinct worker thread.";
        }
        return "Native maps scan did not complete.";
    }

    public List<String> getDetails() {
        return Collections.unmodifiableList(mDetails);
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(mErrors);
    }

    public Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(GosCompatContract.KEY_MAPS_SCAN_COMPLETED, mCompleted);
        bundle.putBoolean(GosCompatContract.KEY_MAPS_SCAN_WORKER_THREAD, mWorkerThread);
        bundle.putInt(GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES, mSelectedRanges);
        bundle.putLong(GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES, mScannedBytes);
        bundle.putInt(GosCompatContract.KEY_MAPS_SCAN_CALLER_TID, mCallerTid);
        bundle.putInt(GosCompatContract.KEY_MAPS_SCAN_WORKER_TID, mWorkerTid);
        bundle.putString(GosCompatContract.KEY_STATUS_TEXT, getStatusText());
        bundle.putString(GosCompatContract.KEY_SUMMARY, getSummary());
        bundle.putStringArrayList(GosCompatContract.KEY_MAPS_SCAN_DETAILS,
                new ArrayList<>(mDetails));
        bundle.putStringArrayList(GosCompatContract.KEY_MAPS_SCAN_ERRORS,
                new ArrayList<>(mErrors));
        return bundle;
    }

    public static MapsScanResult fromBundle(Bundle bundle) {
        return new MapsScanResult(
                bundle.getBoolean(GosCompatContract.KEY_MAPS_SCAN_COMPLETED),
                bundle.getBoolean(GosCompatContract.KEY_MAPS_SCAN_WORKER_THREAD),
                bundle.getInt(GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES),
                bundle.getLong(GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES),
                bundle.getInt(GosCompatContract.KEY_MAPS_SCAN_CALLER_TID),
                bundle.getInt(GosCompatContract.KEY_MAPS_SCAN_WORKER_TID),
                getStringArrayList(bundle, GosCompatContract.KEY_MAPS_SCAN_DETAILS),
                getStringArrayList(bundle, GosCompatContract.KEY_MAPS_SCAN_ERRORS));
    }

    public static MapsScanResult failed(String error) {
        return failed(error, Collections.emptyList(), false);
    }

    public static MapsScanResult failed(String error, List<String> details, boolean workerThread) {
        ArrayList<String> errors = new ArrayList<>();
        errors.add(error);
        return new MapsScanResult(false, workerThread, 0, 0, 0, 0,
                new ArrayList<>(details), errors);
    }

    private static ArrayList<String> getStringArrayList(Bundle bundle, String key) {
        ArrayList<String> value = bundle.getStringArrayList(key);
        return value != null ? value : new ArrayList<>();
    }
}
