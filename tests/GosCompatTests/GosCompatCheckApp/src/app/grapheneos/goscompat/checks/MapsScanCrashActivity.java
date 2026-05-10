package app.grapheneos.goscompat.checks;

import android.app.Activity;
import android.os.Bundle;

public final class MapsScanCrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String mode = getIntent().getStringExtra(GosCompatContract.EXTRA_MAPS_SCAN_MODE);
        String token = getIntent().getStringExtra(GosCompatContract.EXTRA_MAPS_SCAN_TOKEN);
        MapsScanResultStore.clear(this);

        // Keep the native scan outside the launch transaction so a segfault is attributed
        // to this isolated app process rather than the instrumentation shell command.
        Thread launcher = new Thread(() -> {
            MapsScanResult result = GosCompatContract.MODE_REFLECTIVE.equals(mode)
                    ? MapsScanRunner.runReflective()
                    : MapsScanRunner.runDirect();
            MapsScanResultStore.save(getApplicationContext(), token, result);
            runOnUiThread(this::finish);
        }, "maps-scan-launcher");
        launcher.start();
    }
}
