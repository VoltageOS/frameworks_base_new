package app.grapheneos.goscompat.checks.webviewua;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.os.Handler;
import android.os.SystemClock;
import android.webkit.WebSettings;

import app.grapheneos.goscompat.checks.GosCompatContract;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class WebViewUaStartupActivity extends Activity {
    private static final Object STARTUP_LOCK = new Object();
    private static final long UI_RUNNABLE_START_TIMEOUT_MILLIS = 5_000;
    private static final long EXIT_PROCESS_DELAY_MILLIS = 250;

    private final AtomicBoolean mResultSaved = new AtomicBoolean();
    private boolean mExitProcessAfterResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String token = getIntent().getStringExtra(
                GosCompatContract.WebViewUaStartup.Extra.TOKEN);
        mExitProcessAfterResult = getIntent().getBooleanExtra(
                GosCompatContract.WebViewUaStartup.Extra.EXIT_PROCESS, false);
        WebViewUaStartupResultStore.clear(this);

        Thread worker = new Thread(() -> runLockInversionScenario(token),
                "webview-ua-startup-worker");
        worker.start();
    }

    private void runLockInversionScenario(String token) {
        final int workerTid = Process.myTid();
        final AtomicInteger uiTid = new AtomicInteger();
        final AtomicLong durationMillis = new AtomicLong(-1L);
        final AtomicInteger userAgentLength = new AtomicInteger();
        final AtomicBoolean workerCompleted = new AtomicBoolean();
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch uiRunnableStarted = new CountDownLatch(1);

        try {
            synchronized (STARTUP_LOCK) {
                runOnUiThread(() -> {
                    int currentUiTid = Process.myTid();
                    uiTid.set(currentUiTid);
                    uiRunnableStarted.countDown();

                    synchronized (STARTUP_LOCK) {
                        boolean completed = workerCompleted.get();
                        String failure = completed ? null : error.get();
                        if (failure == null && !completed) {
                            failure = "Worker released lock before getDefaultUserAgent completed";
                        }
                        saveResult(token, completed, workerTid != currentUiTid, true,
                                Looper.myLooper() == Looper.getMainLooper(), workerTid,
                                currentUiTid, durationMillis.get(), userAgentLength.get(),
                                failure);
                        finish();
                    }
                });

                if (!uiRunnableStarted.await(UI_RUNNABLE_START_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS)) {
                    saveResult(token, false, false, false, false, workerTid, 0,
                            durationMillis.get(), userAgentLength.get(),
                            "UI runnable did not start while worker held lock");
                    runOnUiThread(this::finish);
                    return;
                }

                long startMillis = SystemClock.elapsedRealtime();
                String userAgent = WebSettings.getDefaultUserAgent(getApplicationContext());
                durationMillis.set(SystemClock.elapsedRealtime() - startMillis);
                userAgentLength.set(userAgent != null ? userAgent.length() : 0);
                if (userAgent == null || userAgent.isEmpty()) {
                    error.set("Default user agent was empty");
                } else {
                    workerCompleted.set(true);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveResult(token, false, true, uiTid.get() > 0, false, workerTid, uiTid.get(),
                    durationMillis.get(), userAgentLength.get(),
                    "Interrupted while waiting for UI runnable");
            runOnUiThread(this::finish);
        } catch (RuntimeException e) {
            saveResult(token, false, true, uiTid.get() > 0, false, workerTid, uiTid.get(),
                    durationMillis.get(), userAgentLength.get(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            runOnUiThread(this::finish);
        }
    }

    private void saveResult(String token, boolean completed, boolean workerThread,
            boolean uiThread, boolean mainLooper, int workerTid, int uiTid, long durationMillis,
            int userAgentLength, String error) {
        if (!mResultSaved.compareAndSet(false, true)) {
            return;
        }
        WebViewUaStartupResultStore.save(getApplicationContext(), token, completed, workerThread,
                uiThread, mainLooper, workerTid, uiTid, durationMillis, userAgentLength, error);
        if (mExitProcessAfterResult) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                finish();
                Process.killProcess(Process.myPid());
            }, EXIT_PROCESS_DELAY_MILLIS);
        }
    }
}
