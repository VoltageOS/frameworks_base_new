package com.android.server.appop;

import android.app.AppOpsManager;
import android.content.Context;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.server.ext.SystemErrorNotification;

import java.util.ArrayList;
import java.util.Arrays;

import static android.app.AppOpsManager.*;

class HistoricalRegistryExt {
    static final String TAG = "HistoricalRegistryExt";

    static final int[] EXTRA_APP_OPS = {
            // Manifest.permission_group.ACTIVITY_RECOGNITION
            OP_ACTIVITY_RECOGNITION,
            // Manifest.permission_group.CALENDAR
            OP_READ_CALENDAR, OP_WRITE_CALENDAR,
            // Manifest.permission_group.CALL_LOG
            OP_READ_CALL_LOG, OP_WRITE_CALL_LOG, OP_PROCESS_OUTGOING_CALLS,
            // Manifest.permission_group.CONTACTS
            OP_READ_CONTACTS, OP_WRITE_CONTACTS, OP_GET_ACCOUNTS,
            // Manifest.permission_group.NEARBY_DEVICES
            OP_BLUETOOTH_ADVERTISE, OP_BLUETOOTH_CONNECT, OP_BLUETOOTH_SCAN, OP_UWB_RANGING,
            OP_NEARBY_WIFI_DEVICES, OP_RANGING, // OP_ACCESS_LOCAL_NETWORK (not enabled yet)
            // Manifest.permission_group.PHONE
            OP_READ_PHONE_STATE, OP_READ_PHONE_NUMBERS, OP_CALL_PHONE, OP_ADD_VOICEMAIL, OP_USE_SIP,
            OP_ANSWER_PHONE_CALLS, OP_ACCEPT_HANDOVER,
            // Manifest.permission_group.READ_MEDIA_AURAL
            OP_READ_MEDIA_AUDIO,
            // Manifest.permission_group.READ_MEDIA_VISUAL
            OP_READ_MEDIA_IMAGES, OP_READ_MEDIA_VIDEO, OP_READ_MEDIA_VISUAL_USER_SELECTED,
            // Manifest.permission_group.SMS
            OP_RECEIVE_SMS, OP_SEND_SMS, OP_READ_SMS, OP_RECEIVE_MMS, OP_RECEIVE_WAP_PUSH,
            OP_READ_CELL_BROADCASTS,
            // Manifest.permission_group.STORAGE,
            OP_READ_EXTERNAL_STORAGE, OP_WRITE_EXTERNAL_STORAGE, OP_ACCESS_MEDIA_LOCATION,
    };

    // Permission to permission group mapping is stored in PermissionController, which is not
    // available during system_server startup. PermissionController calls this method during startup
    // to verify that permission usage history config is in sync between system_server and PermissionController
    static void checkConfig(Context context, HistoricalRegistryInterface historicalRegistry, String[] extraPermissions) {
        var expectedSet = new SparseBooleanArray(extraPermissions.length);
        for (String perm : extraPermissions) {
            int op = AppOpsManager.permissionToOpCode(perm);
            if (op == AppOpsManager.OP_NONE) {
                Slog.d(TAG, "no op for " + perm);
                continue;
            }
            if (expectedSet.indexOfKey(op) >= 0) {
                throw new IllegalStateException(perm);
            }
            expectedSet.put(op, true);
        }

        int[] actual = historicalRegistry.getOpCodes();
        var actualOps = new SparseBooleanArray(actual.length);
        for (int op : actual) {
            actualOps.put(op, true);
        }

        var upstreamSet = new SparseBooleanArray();
        for (int op : HistoricalRegistry.SHORT_INTERVAL_OPS) {
            upstreamSet.put(op, true);
        }
        for (int op : HistoricalRegistry.IMPORTANT_OPS_FOR_SECURITY) {
            upstreamSet.put(op, true);
        }

        var extraOps = new ArrayList<String>();
        for (int i = 0, m = actualOps.size(); i < m; ++i) {
            int op = actualOps.keyAt(i);
            if (expectedSet.indexOfKey(op) < 0 && upstreamSet.indexOfKey(op) < 0) {
                extraOps.add(AppOpsManager.opToPublicName(op));
            }
        }
        var missingOps = new ArrayList<String>();
        for (int i = 0, m = expectedSet.size(); i < m; ++i) {
            int op = expectedSet.keyAt(i);
            if (actualOps.indexOfKey(op) < 0) {
                missingOps.add(AppOpsManager.opToPublicName(op));
            }
        }

        if (extraOps.size() + missingOps.size() == 0) {
            Slog.d(TAG, "config check has passed");
            return;
        }

        var msg = new StringBuilder();
        if (!missingOps.isEmpty()) {
            msg.append("missing ops: ");
            msg.append(Arrays.toString(missingOps.toArray()));
        }
        if (!extraOps.isEmpty()) {
            if (!msg.isEmpty()) {
                msg.append(", ");
            }
            msg.append("extra ops: ");
            msg.append(Arrays.toString(extraOps.toArray()));
        }
        new SystemErrorNotification("AppOpHistoricalRegistry config check", msg.toString()).show(context);
    }
}
