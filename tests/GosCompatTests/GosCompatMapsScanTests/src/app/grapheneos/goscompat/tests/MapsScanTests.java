package app.grapheneos.goscompat.tests;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.Instrumentation;
import android.content.Context;
import android.device.loggers.TestLogData;
import android.os.Bundle;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import app.grapheneos.goscompat.checks.GosCompatContract;
import app.grapheneos.goscompat.checks.MapsScanTombstoneFormatter;

import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.ShellIdentityUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class MapsScanTests {
    private static final long OUTCOME_TIMEOUT_MILLIS = 30_000;
    private static final long TOMBSTONE_FETCH_TIMEOUT_MILLIS = 10_000;
    private static final long POLL_INTERVAL_MILLIS = 200;
    private static final int SCAN_ATTEMPTS = 10;
    private static final String TOMBSTONE_ARTIFACT_KEY_PREFIX =
            "goscompat_maps_scan_tombstone_";

    @Rule
    public final TestLogData mLogs = new TestLogData();

    private Context mContext;
    private UiDevice mDevice;
    private ActivityManager mActivityManager;

    @Before
    public void setUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = instrumentation.getContext();
        mDevice = UiDevice.getInstance(instrumentation);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
    }

    @After
    public void tearDown() throws Exception {
        mDevice.executeShellCommand("am force-stop " + GosCompatContract.APP_PACKAGE);
    }

    @Test
    public void directNativeMapsScanDoesNotCrashFromJavaWorkerThread() throws Exception {
        assertMapsScanModeDoesNotCrash(GosCompatContract.MODE_DIRECT);
    }

    @Test
    public void reflectiveNativeMapsScanDoesNotCrashFromJavaWorkerThread() throws Exception {
        assertMapsScanModeDoesNotCrash(GosCompatContract.MODE_REFLECTIVE);
    }

    private void assertMapsScanModeDoesNotCrash(String mode) throws Exception {
        for (int attempt = 1; attempt <= SCAN_ATTEMPTS; attempt++) {
            assertMapsScanAttemptDoesNotCrash(mode, attempt);
        }
    }

    private void assertMapsScanAttemptDoesNotCrash(String mode, int attempt) throws Exception {
        String token = UUID.randomUUID().toString();

        // The translucent trigger activity starts the scan asynchronously. Force-stop first so each
        // attempt gets a newly created helper process with fresh bionic thread mappings.
        mDevice.executeShellCommand("am force-stop " + GosCompatContract.APP_PACKAGE);

        long startTimeMillis = System.currentTimeMillis();
        // Use an activity launch because shell-started background services are rejected by
        // ActivityManager background-start restrictions.
        String output = mDevice.executeShellCommand("am start -n "
                + GosCompatContract.MAPS_SCAN_CRASH_ACTIVITY
                + " --es " + GosCompatContract.EXTRA_MAPS_SCAN_MODE + " " + mode
                + " --es " + GosCompatContract.EXTRA_MAPS_SCAN_TOKEN + " " + token);
        assertWithMessage(output).that(output).doesNotContain("Error");

        ScanOutcome outcome = waitForOutcome(token, startTimeMillis, mode, attempt);
        if (outcome.result != null) {
            assertCompletedResult(outcome.result, mode, attempt);
            mDevice.executeShellCommand("am force-stop " + GosCompatContract.APP_PACKAGE);
            return;
        }

        byte[] tombstoneProto = fetchTombstone(outcome.nativeCrash);
        String formattedTombstone = MapsScanTombstoneFormatter.format(tombstoneProto);
        reportTombstoneArtifacts(mode, attempt, formattedTombstone, tombstoneProto);
        fail("Maps scan crashed in " + mode + " mode on attempt " + attempt + " of "
                + SCAN_ATTEMPTS + ":\n" + formattedTombstone);
    }

    private ScanOutcome waitForOutcome(String token, long startTimeMillis, String mode,
            int attempt) throws Exception {
        long deadline = System.currentTimeMillis() + OUTCOME_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ApplicationExitInfo nativeCrash = findNativeCrash(startTimeMillis);
            if (nativeCrash != null) {
                return ScanOutcome.forNativeCrash(nativeCrash);
            }

            Bundle result = getStoredResult(token);
            if (result != null
                    && result.getBoolean(GosCompatContract.KEY_MAPS_SCAN_RESULT_AVAILABLE)) {
                return ScanOutcome.forResult(result);
            }

            java.lang.Thread.sleep(POLL_INTERVAL_MILLIS);
        }

        fail("Timed out waiting for maps scan result or native crash in " + mode
                + " mode on attempt " + attempt + " of " + SCAN_ATTEMPTS);
        return null;
    }

    private Bundle getStoredResult(String token) {
        try {
            // Poll a file instead of the provider so the instrumentation process never depends
            // on a binder transaction into a helper process that may be crashing.
            String output = mDevice.executeShellCommand("run-as " + GosCompatContract.APP_PACKAGE
                    + " cat files/" + GosCompatContract.MAPS_SCAN_RESULT_FILE + " 2>/dev/null");
            if (output == null || output.trim().isEmpty()) {
                return null;
            }

            Properties properties = new Properties();
            properties.load(new StringReader(output));
            if (!token.equals(properties.getProperty(GosCompatContract.EXTRA_MAPS_SCAN_TOKEN))) {
                return null;
            }

            boolean completed = getBoolean(properties,
                    GosCompatContract.KEY_MAPS_SCAN_COMPLETED);
            boolean workerThread = getBoolean(properties,
                    GosCompatContract.KEY_MAPS_SCAN_WORKER_THREAD);

            Bundle result = new Bundle();
            result.putBoolean(GosCompatContract.KEY_MAPS_SCAN_RESULT_AVAILABLE, true);
            result.putBoolean(GosCompatContract.KEY_MAPS_SCAN_COMPLETED, completed);
            result.putBoolean(GosCompatContract.KEY_MAPS_SCAN_WORKER_THREAD, workerThread);
            result.putInt(GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES,
                    getInt(properties, GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES));
            result.putLong(GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES,
                    getLong(properties, GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES));
            result.putInt(GosCompatContract.KEY_MAPS_SCAN_CALLER_TID,
                    getInt(properties, GosCompatContract.KEY_MAPS_SCAN_CALLER_TID));
            result.putInt(GosCompatContract.KEY_MAPS_SCAN_WORKER_TID,
                    getInt(properties, GosCompatContract.KEY_MAPS_SCAN_WORKER_TID));
            result.putString(GosCompatContract.KEY_STATUS_TEXT,
                    completed && workerThread ? "Completed" : "Failed");
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean getBoolean(Properties properties, String key) {
        return Boolean.parseBoolean(properties.getProperty(key));
    }

    private static int getInt(Properties properties, String key) {
        return Integer.parseInt(properties.getProperty(key, "0"));
    }

    private static long getLong(Properties properties, String key) {
        return Long.parseLong(properties.getProperty(key, "0"));
    }

    private ApplicationExitInfo findNativeCrash(long startTimeMillis) throws Exception {
        List<ApplicationExitInfo> exits = ShellIdentityUtils.invokeMethodWithShellPermissions(
                GosCompatContract.APP_PACKAGE,
                0,
                16,
                mActivityManager::getHistoricalProcessExitReasons,
                Manifest.permission.DUMP);
        for (ApplicationExitInfo exit : exits) {
            if (exit.getTimestamp() < startTimeMillis) {
                continue;
            }
            if (exit.getReason() != ApplicationExitInfo.REASON_CRASH_NATIVE) {
                continue;
            }
            if (!GosCompatContract.MAPS_SCAN_PROCESS.equals(exit.getProcessName())) {
                continue;
            }
            return exit;
        }
        return null;
    }

    private byte[] fetchTombstone(ApplicationExitInfo exitInfo) throws Exception {
        InputStream[] trace = new InputStream[1];
        PollingCheck.check("not able to get tombstone", TOMBSTONE_FETCH_TIMEOUT_MILLIS, () -> {
            trace[0] = ShellIdentityUtils.invokeMethodWithShellPermissions(
                    exitInfo,
                    (i) -> {
                        try {
                            return i.getTraceInputStream();
                        } catch (IOException e) {
                            return null;
                        }
                    },
                    Manifest.permission.DUMP);
            return trace[0] != null;
        });

        try (InputStream input = trace[0]) {
            return MapsScanTombstoneFormatter.readFully(input);
        }
    }

    private void assertCompletedResult(Bundle result, String mode, int attempt) {
        String message = "mode=" + mode
                + ", attempt=" + attempt + "/" + SCAN_ATTEMPTS
                + ", status=" + result.getString(GosCompatContract.KEY_STATUS_TEXT)
                + ", selectedRanges="
                + result.getInt(GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES)
                + ", scannedBytes="
                + result.getLong(GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES)
                + ", callerTid=" + result.getInt(GosCompatContract.KEY_MAPS_SCAN_CALLER_TID)
                + ", workerTid=" + result.getInt(GosCompatContract.KEY_MAPS_SCAN_WORKER_TID);

        assertWithMessage(message).that(result.getBoolean(
                GosCompatContract.KEY_MAPS_SCAN_COMPLETED)).isTrue();
        assertWithMessage(message).that(result.getBoolean(
                GosCompatContract.KEY_MAPS_SCAN_WORKER_THREAD)).isTrue();
        assertWithMessage(message).that(result.getInt(
                GosCompatContract.KEY_MAPS_SCAN_SELECTED_RANGES)).isAtLeast(1);
        assertWithMessage(message).that(result.getLong(
                GosCompatContract.KEY_MAPS_SCAN_SCANNED_BYTES)).isGreaterThan(0L);
    }

    private void reportTombstoneArtifacts(String mode, int attempt, String formattedTombstone,
            byte[] proto) throws IOException {
        File directory = mContext.getExternalCacheDir();
        if (directory == null) {
            directory = mContext.getCacheDir();
        }
        directory.mkdirs();

        String baseName = TOMBSTONE_ARTIFACT_KEY_PREFIX + mode.replaceAll("[^A-Za-z0-9._-]", "_")
                + "_attempt_" + attempt + "_" + System.nanoTime();
        File textFile = new File(directory, baseName + ".txt");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(textFile), StandardCharsets.UTF_8)) {
            writer.write(formattedTombstone);
        }

        mLogs.addTestLog(baseName + "_text", textFile);

        File protoFile = new File(directory, baseName + ".pb");
        try (FileOutputStream output = new FileOutputStream(protoFile)) {
            output.write(proto);
        }
        mLogs.addTestLog(baseName + "_proto", protoFile);
    }

    private static final class ScanOutcome {
        final Bundle result;
        final ApplicationExitInfo nativeCrash;

        private ScanOutcome(Bundle result, ApplicationExitInfo nativeCrash) {
            this.result = result;
            this.nativeCrash = nativeCrash;
        }

        static ScanOutcome forResult(Bundle result) {
            return new ScanOutcome(result, null);
        }

        static ScanOutcome forNativeCrash(ApplicationExitInfo nativeCrash) {
            return new ScanOutcome(null, nativeCrash);
        }
    }
}
