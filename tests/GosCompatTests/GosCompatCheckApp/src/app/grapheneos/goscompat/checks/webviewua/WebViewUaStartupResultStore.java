package app.grapheneos.goscompat.checks.webviewua;

import android.content.Context;

import app.grapheneos.goscompat.checks.GosCompatContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

final class WebViewUaStartupResultStore {
    private WebViewUaStartupResultStore() {
    }

    static void save(Context context, String token, boolean completed, boolean workerThread,
            boolean uiThread, boolean mainLooper, int workerTid, int uiTid, long durationMillis,
            int userAgentLength, String error) {
        if (token == null || token.isEmpty()) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty(GosCompatContract.WebViewUaStartup.Extra.TOKEN, token);
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.RESULT_AVAILABLE,
                Boolean.toString(true));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.COMPLETED,
                Boolean.toString(completed));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.WORKER_THREAD,
                Boolean.toString(workerThread));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.UI_THREAD,
                Boolean.toString(uiThread));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.MAIN_LOOPER,
                Boolean.toString(mainLooper));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.WORKER_TID,
                Integer.toString(workerTid));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.UI_TID,
                Integer.toString(uiTid));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.DURATION_MS,
                Long.toString(durationMillis));
        properties.setProperty(GosCompatContract.WebViewUaStartup.Key.USER_AGENT_LENGTH,
                Integer.toString(userAgentLength));
        if (error != null && !error.isEmpty()) {
            properties.setProperty(GosCompatContract.WebViewUaStartup.Key.ERROR, error);
        }

        try (FileOutputStream output = new FileOutputStream(resultFile(context))) {
            properties.store(output, null);
        } catch (IOException ignored) {
        }
    }

    static void clear(Context context) {
        resultFile(context).delete();
    }

    private static File resultFile(Context context) {
        return new File(context.getFilesDir(), GosCompatContract.WebViewUaStartup.RESULT_FILE);
    }
}
