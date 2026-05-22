package app.grapheneos.goscompat.webview.tests;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.fail;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.webkit.WebSettings;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import app.grapheneos.goscompat.checks.GosCompatContract;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.StringReader;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebViewTests {
    private static final long OUTCOME_TIMEOUT_MILLIS = 15_000;
    private static final long POLL_INTERVAL_MILLIS = 200;
    private static final int START_ATTEMPTS = 5;
    // Source: Chromium android_webview/browser/aw_content_browser_client.cc
    // GetUserAgent() WebView hardcodes this platform when WebViewReduceUAAndroidVersionDeviceModel 
    // is enabled.
    private static final String REDUCED_WEBVIEW_PLATFORM = "Linux; Android 10; K; wv";
    private static final Pattern CHROME_PRODUCT_PATTERN =
            Pattern.compile("\\bChrome/(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)\\b");

    private UiDevice mDevice;

    @Before
    public void setUp() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        mDevice = UiDevice.getInstance(instrumentation);
    }

    @After
    public void tearDown() throws Exception {
        mDevice.executeShellCommand("am force-stop " + GosCompatContract.App.PACKAGE_NAME);
    }

    @Test
    public void defaultUserAgentDoesNotDeadlockWhenUiThreadWaitsOnAppLock() throws Exception {
        for (int attempt = 1; attempt <= START_ATTEMPTS; attempt++) {
            assertFreshProcessStartupAttemptCompletes(attempt, START_ATTEMPTS);
        }
    }

    @Test
    public void defaultUserAgentIsReduced() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext()
                .getApplicationContext();

        assertDefaultUserAgentIsReduced(WebSettings.getDefaultUserAgent(context));
    }

    private void assertFreshProcessStartupAttemptCompletes(int attempt, int totalAttempts)
            throws Exception {
        String token = UUID.randomUUID().toString();

        // Force-stop before every attempt so WebView startup is exercised from a fresh app process.
        mDevice.executeShellCommand("am force-stop " + GosCompatContract.App.PACKAGE_NAME);

        String output = mDevice.executeShellCommand("am start -n "
                + GosCompatContract.WebViewUaStartup.ACTIVITY
                + " --es " + GosCompatContract.WebViewUaStartup.Extra.TOKEN + " " + token);
        assertWithMessage(output).that(output).doesNotContain("Error");

        Bundle result = waitForResult(token, attempt, totalAttempts);
        assertCompletedResult(result, attempt, totalAttempts);

        mDevice.executeShellCommand("am force-stop " + GosCompatContract.App.PACKAGE_NAME);
    }

    private Bundle waitForResult(String token, int attempt, int totalAttempts) throws Exception {
        long deadline = System.currentTimeMillis() + OUTCOME_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Bundle result = getStoredResult(token);
            if (result != null
                    && result.getBoolean(
                            GosCompatContract.WebViewUaStartup.Key.RESULT_AVAILABLE)) {
                return result;
            }

            java.lang.Thread.sleep(POLL_INTERVAL_MILLIS);
        }

        fail("Timed out waiting for WebView default user agent startup result on attempt "
                + attempt + " of " + totalAttempts + ". A worker thread holds an app lock while "
                + "WebSettings.getDefaultUserAgent() waits for UI-thread WebView startup, and the "
                + "UI thread is blocked trying to acquire the same app lock.");
        return null;
    }

    private Bundle getStoredResult(String token) throws Exception {
        String output = mDevice.executeShellCommand(
                "run-as " + GosCompatContract.App.PACKAGE_NAME
                        + " cat files/" + GosCompatContract.WebViewUaStartup.RESULT_FILE
                        + " 2>/dev/null");
        if (output == null || output.trim().isEmpty()) {
            return null;
        }

        Properties properties = new Properties();
        try {
            properties.load(new StringReader(output));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!token.equals(properties.getProperty(
                GosCompatContract.WebViewUaStartup.Extra.TOKEN))) {
            return null;
        }

        Bundle result = new Bundle();
        result.putBoolean(GosCompatContract.WebViewUaStartup.Key.RESULT_AVAILABLE,
                getBoolean(properties,
                        GosCompatContract.WebViewUaStartup.Key.RESULT_AVAILABLE));
        result.putBoolean(GosCompatContract.WebViewUaStartup.Key.COMPLETED,
                getBoolean(properties, GosCompatContract.WebViewUaStartup.Key.COMPLETED));
        result.putBoolean(GosCompatContract.WebViewUaStartup.Key.WORKER_THREAD,
                getBoolean(properties,
                        GosCompatContract.WebViewUaStartup.Key.WORKER_THREAD));
        result.putBoolean(GosCompatContract.WebViewUaStartup.Key.UI_THREAD,
                getBoolean(properties, GosCompatContract.WebViewUaStartup.Key.UI_THREAD));
        result.putBoolean(GosCompatContract.WebViewUaStartup.Key.MAIN_LOOPER,
                getBoolean(properties,
                        GosCompatContract.WebViewUaStartup.Key.MAIN_LOOPER));
        result.putInt(GosCompatContract.WebViewUaStartup.Key.WORKER_TID,
                getInt(properties, GosCompatContract.WebViewUaStartup.Key.WORKER_TID));
        result.putInt(GosCompatContract.WebViewUaStartup.Key.UI_TID,
                getInt(properties, GosCompatContract.WebViewUaStartup.Key.UI_TID));
        result.putLong(GosCompatContract.WebViewUaStartup.Key.DURATION_MS,
                getLong(properties, GosCompatContract.WebViewUaStartup.Key.DURATION_MS));
        result.putInt(GosCompatContract.WebViewUaStartup.Key.USER_AGENT_LENGTH,
                getInt(properties,
                        GosCompatContract.WebViewUaStartup.Key.USER_AGENT_LENGTH));
        result.putString(GosCompatContract.WebViewUaStartup.Key.ERROR,
                properties.getProperty(GosCompatContract.WebViewUaStartup.Key.ERROR, ""));
        return result;
    }

    private void assertCompletedResult(Bundle result, int attempt, int totalAttempts) {
        String message = "attempt=" + attempt + "/" + totalAttempts
                + ", completed="
                + result.getBoolean(GosCompatContract.WebViewUaStartup.Key.COMPLETED)
                + ", workerThread="
                + result.getBoolean(GosCompatContract.WebViewUaStartup.Key.WORKER_THREAD)
                + ", uiThread="
                + result.getBoolean(GosCompatContract.WebViewUaStartup.Key.UI_THREAD)
                + ", mainLooper="
                + result.getBoolean(GosCompatContract.WebViewUaStartup.Key.MAIN_LOOPER)
                + ", workerTid="
                + result.getInt(GosCompatContract.WebViewUaStartup.Key.WORKER_TID)
                + ", uiTid=" + result.getInt(GosCompatContract.WebViewUaStartup.Key.UI_TID)
                + ", durationMs="
                + result.getLong(GosCompatContract.WebViewUaStartup.Key.DURATION_MS)
                + ", userAgentLength="
                + result.getInt(GosCompatContract.WebViewUaStartup.Key.USER_AGENT_LENGTH)
                + ", error="
                + result.getString(GosCompatContract.WebViewUaStartup.Key.ERROR);

        assertWithMessage(message).that(result.getBoolean(
                GosCompatContract.WebViewUaStartup.Key.COMPLETED)).isTrue();
        assertWithMessage(message).that(result.getBoolean(
                GosCompatContract.WebViewUaStartup.Key.WORKER_THREAD)).isTrue();
        assertWithMessage(message).that(result.getBoolean(
                GosCompatContract.WebViewUaStartup.Key.UI_THREAD)).isTrue();
        assertWithMessage(message).that(result.getBoolean(
                GosCompatContract.WebViewUaStartup.Key.MAIN_LOOPER)).isTrue();
        assertWithMessage(message).that(result.getInt(
                GosCompatContract.WebViewUaStartup.Key.USER_AGENT_LENGTH)).isGreaterThan(0);
    }

    private void assertDefaultUserAgentIsReduced(String userAgent) {
        String message = "userAgent=" + userAgent;

        assertWithMessage(message).that(userAgent).isNotEmpty();
        assertWithMessage(message).that(extractPlatform(userAgent))
                .isEqualTo(REDUCED_WEBVIEW_PLATFORM);
        assertWithMessage(message).that(userAgent).doesNotContain("Build/");

        Matcher chromeProductMatcher = CHROME_PRODUCT_PATTERN.matcher(userAgent);
        assertWithMessage(message).that(chromeProductMatcher.find()).isTrue();
        assertWithMessage(message).that(chromeProductMatcher.group(2)).isEqualTo("0");
        assertWithMessage(message).that(chromeProductMatcher.group(3)).isEqualTo("0");
        assertWithMessage(message).that(chromeProductMatcher.group(4)).isEqualTo("0");
    }

    private static String extractPlatform(String userAgent) {
        int start = userAgent.indexOf('(');
        int end = userAgent.indexOf(')', start + 1);
        if (start < 0 || end <= start) {
            return "";
        }
        return userAgent.substring(start + 1, end);
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
}
