package android.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;

class ActivityThreadHooks {

    private static volatile boolean called;

    // called after the initial app context is constructed
    // ActivityThread.handleBindApplication
    static Bundle onBind(Context appContext, ActivityThread.AppBindData appBindData) {
        Bundle args = appBindData.extraArgs;
        Objects.requireNonNull(args, "args bundle is null");

        if (called) {
            throw new IllegalStateException("onBind called for the second time");
        }
        called = true;

        AppGlobals.setInitialPackageId(appContext.getApplicationInfo().ext().getPackageId());

        if (Process.isIsolated()) {
            return null;
        }

        int[] flags = Objects.requireNonNull(args.getIntArray(AppBindArgs.KEY_FLAGS_ARRAY));

        return args;
    }

    // called after ActivityThread instrumentation is inited, which happens before execution of any
    // of app's code
    // ActivityThread.handleBindApplication
    static void onBind2(Context appContext, Bundle appBindArgs) {

    }

    static Service instantiateService(String className) {
        Service res = null;
        return res;
    }
}
