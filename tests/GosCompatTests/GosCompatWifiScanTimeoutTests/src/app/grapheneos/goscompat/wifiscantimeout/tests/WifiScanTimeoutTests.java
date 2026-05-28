package app.grapheneos.goscompat.wifiscantimeout.tests;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class WifiScanTimeoutTests {
    private static final String TAG = "GosCompatWifiScanTimeout";
    private static final String REQUEST_PROPERTY =
            "vendor.gos.dhdutil.request";
    private static final String RESULT_PROPERTY =
            "vendor.gos.dhdutil.result";
    private static final long FOREGROUND_SETTLE_MILLIS = 1_000;
    private static final long HELPER_RESULT_TIMEOUT_MILLIS = 10_000;
    private static final long HELPER_RESULT_INTERVAL_MILLIS = 100;
    private static final long SCAN_TIMEOUT_BROADCAST_TIMEOUT_MILLIS = 60_000;

    private Instrumentation mInstrumentation;
    private Context mContext;
    private WifiManager mWifiManager;
    private Activity mForegroundActivity;
    private boolean mInduceScanTimeoutArmed;

    @Before
    public void setUp() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = mInstrumentation.getContext();
        mWifiManager = mContext.getSystemService(WifiManager.class);
        LocationManager locationManager = mContext.getSystemService(LocationManager.class);

        assumeTrue("Wi-Fi hardware is required",
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI));
        assumeTrue("WifiManager is required", mWifiManager != null);
        assumeTrue("LocationManager is required", locationManager != null);
        assumeTrue("Wi-Fi must be enabled before running this scan timeout test",
                mWifiManager.isWifiEnabled());
        assumeTrue("Location must be enabled before running this scan timeout test",
                locationManager.isLocationEnabled());
    }

    @After
    public void tearDown() {
        if (mInduceScanTimeoutArmed) {
            clearInduceWlScanTimeout();
        }

        if (mForegroundActivity != null) {
            mForegroundActivity.finish();
            mInstrumentation.waitForIdleSync();
        }
    }

    @Test
    public void thirdPartyScanDoesNotPanicWhenWlScanTimeoutRuns() {
        startForegroundActivity();
        enableInduceWlScanTimeoutOrSkip();

        CountDownLatch failedScanBroadcast = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())
                        && !intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)) {
                    Log.i(TAG, "Received failed scan broadcast after induced wl_scan_timeout");
                    failedScanBroadcast.countDown();
                }
            }
        };

        mContext.registerReceiver(receiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_EXPORTED);
        try {
            assertWithMessage("Wi-Fi scan request should be accepted")
                    .that(mWifiManager.startScan()).isTrue();
            Log.i(TAG, "Accepted induced scan timeout request");

            // The kernel hook suppresses the normal scan completion so wl_scan_timeout() runs.
            // A fixed driver reports the timed out scan as a failed framework scan broadcast.
            assertWithMessage("induced wl_scan_timeout should report a failed scan")
                    .that(await(failedScanBroadcast, SCAN_TIMEOUT_BROADCAST_TIMEOUT_MILLIS))
                    .isTrue();
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    private void enableInduceWlScanTimeoutOrSkip() {
        // This asks the vendor helper to set Broadcom's "induce_error" iovar to
        // DHD_INDUCE_SCAN_TIMEOUT. In kernel_pixel wl_cfgscan.c, that makes the next completed
        // scan skip its normal completion event so the driver runs wl_scan_timeout() instead.
        String output = setInduceWlScanTimeout(true);
        assumeTrue("vendor wl_scan_timeout induction helper is unavailable: " + output,
                !output.startsWith("timeout:"));
        assertWithMessage(output).that(output).startsWith("ok:");
        mInduceScanTimeoutArmed = true;
    }

    private String setInduceWlScanTimeout(boolean enabled) {
        String token = (enabled ? "enable-" : "disable-")
                + Long.toHexString(SystemClock.elapsedRealtimeNanos());
        setVendorDebugWifiProperty(RESULT_PROPERTY, "pending:" + token + ":request");
        setVendorDebugWifiProperty(REQUEST_PROPERTY,
                "wifi-scan-timeout:" + (enabled ? "enable:" : "disable:") + token);

        String output = waitForInduceWlScanTimeoutResult(token);
        Log.i(TAG, "wl_scan_timeout induction " + (enabled ? "enable" : "disable")
                + " output: " + output);
        return output;
    }

    private String waitForInduceWlScanTimeoutResult(String token) {
        long deadline = SystemClock.elapsedRealtime() + HELPER_RESULT_TIMEOUT_MILLIS;
        String lastResult = "";

        while (SystemClock.elapsedRealtime() < deadline) {
            String result = getVendorDebugWifiProperty(RESULT_PROPERTY);
            if (result.startsWith("ok:" + token + ":")
                    || result.startsWith("error:" + token + ":")) {
                return result;
            }
            lastResult = result;
            SystemClock.sleep(HELPER_RESULT_INTERVAL_MILLIS);
        }

        return "timeout:" + token + ":last=" + lastResult;
    }

    private void setVendorDebugWifiProperty(String property, String value) {
        executeShellCommand("setprop " + property + " " + value);
    }

    private String getVendorDebugWifiProperty(String property) {
        return executeShellCommand("getprop " + property);
    }

    private void clearInduceWlScanTimeout() {
        String output = setInduceWlScanTimeout(false);
        if (!output.startsWith("ok:")) {
            Log.w(TAG, "Unable to clear wl_scan_timeout induction: " + output);
        }
        mInduceScanTimeoutArmed = false;
    }

    private String executeShellCommand(String command) {
        try (ParcelFileDescriptor pfd =
                        mInstrumentation.getUiAutomation().executeShellCommand(command);
                FileInputStream input = new FileInputStream(pfd.getFileDescriptor())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new AssertionError("Failed to execute shell command: " + command, e);
        }
    }

    private void startForegroundActivity() {
        Intent intent = new Intent(mContext, ForegroundScanActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mForegroundActivity = mInstrumentation.startActivitySync(intent);
        mInstrumentation.waitForIdleSync();
        SystemClock.sleep(FOREGROUND_SETTLE_MILLIS);
    }

    private static boolean await(CountDownLatch latch, long timeoutMillis) {
        try {
            return latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for scan timeout broadcast", e);
        }
    }
}
