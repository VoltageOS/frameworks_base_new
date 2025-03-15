package com.android.server.ext;

import android.annotation.Nullable;
import android.nfc.NfcManager;
import android.nfc.NfcAdapter;
import android.ext.settings.ExtSettings;
import android.content.pm.PackageManager;
import android.content.IntentFilter;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;

import android.util.Slog;

class NfcAutoOff extends DelayedConditionalAction {
    private static final String TAG = NfcAutoOff.class.getSimpleName();

    private final Context context;

    private NfcAutoOff(SystemServerExt sse) {
        super(sse, ExtSettings.NFC_AUTO_OFF, sse.bgHandler);
        context = sse.context;
    }

    static void maybeInit(SystemServerExt sse) {
        if (sse.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC, 0)) {
            new NfcAutoOff(sse).init();
        }
    }

    @Override
    protected boolean shouldScheduleAlarm() {
        return isAdapterOn();
    }

    @Override
    protected void alarmTriggered() {
        if (isAdapterOn()) {
            Slog.d(TAG, "adapter.disable(true)");
            getAdapter().disable(true);
        }
    }

    @Override
    protected void registerStateListener() {
        IntentFilter f = new IntentFilter();
        f.addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);

        sse.context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context broadcastContext, Intent intent) {
                Slog.d(TAG, "" + intent + ", extras " + intent.getExtras().deepCopy());
                update();
            }
        }, f, null, handler);
    }

    @Nullable
    private NfcAdapter getAdapter() {
        return NfcAdapter.getDefaultAdapter(context);
    }

    private boolean isAdapterOn() {
        Slog.d(TAG, "isAdapterOn");
        var adapter = getAdapter();
        if (adapter != null) {
            boolean isEnabled = adapter.isEnabled();
            Slog.d(TAG, "isEnabled: " + isEnabled);
            return isEnabled;
        } else {
            Slog.d(TAG, "adapter is null");
        }
        return false;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
}
