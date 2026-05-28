package app.grapheneos.goscompat.wifiscantimeout.tests;

import android.app.Activity;
import android.os.Bundle;

public final class ForegroundScanActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
    }
}
