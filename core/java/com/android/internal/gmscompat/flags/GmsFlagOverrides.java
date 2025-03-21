package com.android.internal.gmscompat.flags;

import android.content.Context;

import com.android.internal.gmscompat.GmsCompatConfig;
import com.android.internal.gmscompat.GmsHooks;

public class GmsFlagOverrides {
    private static final String TAG = "GmsFlagOverrides";

    public static void init(Context ctx) {
        applyOverrides();
    }

    public static void applyOverrides() {
        GmsCompatConfig config = GmsHooks.config();
        GservicesFlags.applyOverrides(config);
        PhenotypeFlags.applyOverrides(config);
    }
}
