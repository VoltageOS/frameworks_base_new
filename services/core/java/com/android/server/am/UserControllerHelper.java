package com.android.server.am;

import android.annotation.UserIdInt;
import android.content.Context;
import android.ext.settings.ExtSettings;
import android.os.Binder;

import com.android.server.utils.Slogf;

final class UserControllerHelper {

    private static final String TAG = "UserControllerHelper";

    static boolean disallowDelayedLockingForUser(Context ctx, @UserIdInt int userId) {
        long token = Binder.clearCallingIdentity();
        try {
            return ExtSettings.DISALLOW_DELAYED_LOCKING_ON_USER_STOP.get(ctx, userId);
        } catch (SecurityException e) {
            Slogf.e(TAG, "caught exception for fetching setting to disallow delayed locking", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
