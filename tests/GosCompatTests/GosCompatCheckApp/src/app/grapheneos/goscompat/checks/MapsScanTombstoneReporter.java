package app.grapheneos.goscompat.checks;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

final class MapsScanTombstoneReporter {
    private static final long TOMBSTONE_FETCH_TIMEOUT_MILLIS = 10_000;
    private static final long POLL_INTERVAL_MILLIS = 200;

    private MapsScanTombstoneReporter() {
    }

    static MapsScanResult getNativeCrashResult(Context context, long startTimeMillis) {
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return MapsScanResult.failed(
                    "Maps scan process crashed before returning; ActivityManager unavailable.");
        }

        ApplicationExitInfo latestCrash = null;
        Throwable lastError = null;
        long deadline = System.currentTimeMillis() + TOMBSTONE_FETCH_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                List<ApplicationExitInfo> exits =
                        activityManager.getHistoricalProcessExitReasons(null, 0, 16);
                for (ApplicationExitInfo exit : exits) {
                    if (!isMatchingNativeCrash(exit, startTimeMillis)) {
                        continue;
                    }
                    latestCrash = exit;
                    String formattedTombstone = formatTombstone(exit);
                    if (formattedTombstone != null) {
                        return MapsScanResult.failed(
                                "Maps scan process crashed before returning.",
                                splitLines(formattedTombstone),
                                true);
                    }
                }
            } catch (Throwable t) {
                lastError = t;
            }

            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = e;
                break;
            }
        }

        ArrayList<String> details = new ArrayList<>();
        if (latestCrash != null) {
            details.add("Native crash was recorded, but its tombstone trace was unavailable.");
            details.add("pid=" + latestCrash.getPid()
                    + ", process=" + latestCrash.getProcessName()
                    + ", description=" + latestCrash.getDescription());
        }
        if (lastError != null) {
            details.add("tombstone lookup error=" + lastError.getClass().getSimpleName()
                    + ": " + lastError.getMessage());
        }

        return MapsScanResult.failed(
                "Maps scan process crashed before returning; no parsed tombstone was available.",
                details,
                true);
    }

    private static boolean isMatchingNativeCrash(ApplicationExitInfo exit, long startTimeMillis) {
        return exit.getTimestamp() >= startTimeMillis
                && exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE
                && GosCompatContract.MAPS_SCAN_PROCESS.equals(exit.getProcessName());
    }

    private static String formatTombstone(ApplicationExitInfo exit) throws Exception {
        try (InputStream input = exit.getTraceInputStream()) {
            if (input == null) {
                return null;
            }
            return MapsScanTombstoneFormatter.format(MapsScanTombstoneFormatter.readFully(input));
        }
    }

    private static ArrayList<String> splitLines(String text) {
        ArrayList<String> lines = new ArrayList<>();
        String[] split = text.split("\\R", -1);
        for (int i = 0; i < split.length; i++) {
            if (i == split.length - 1 && split[i].isEmpty()) {
                continue;
            }
            lines.add(split[i]);
        }
        return lines;
    }
}
