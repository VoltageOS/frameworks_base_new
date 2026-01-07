package com.android.packageinstaller.v2.ui;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.util.ArraySet;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;

import com.android.packageinstaller.R;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class SpecialRuntimePermUtils {
    private static final String TAG = "SpecialRuntimePermUtils";

    private interface PermissionNameSupplier {
        String get();
    }

    public static void setInitialCheckboxStates(PackageInfo pkgInfo, CheckBox... checkBoxes) {
        ApplicationInfo ai = pkgInfo.applicationInfo;
        boolean isSystemApp = ai != null && ai.isSystemApp();
        if (isSystemApp) {
            return;
        }
        String[] permsArr = pkgInfo.requestedPermissions;
        if (permsArr == null) {
            return;
        }

        var perms = new ArraySet<>(permsArr);
        for (CheckBox checkBox : checkBoxes) {
            int id = checkBox.getId();
            String perm;
            if (id == R.id.install_allow_INTERNET_permission) {
                perm = Manifest.permission.INTERNET;
            } else {
                throw new IllegalArgumentException(checkBox.getText().toString());
            }

            if (perms.contains(perm)) {
                checkBox.setVisibility(View.VISIBLE);
                PermissionNameSupplier supplier = () -> perm;
                // use a private interface to make sure that the tag is not rewritten by upstream code
                checkBox.setTag(supplier);
            }
        }
    }

    public static void collectCheckboxState(CheckBox checkbox, List<SpecialPermissionState> dst) {
        if (checkbox.getVisibility() != View.VISIBLE) {
            return;
        }
        checkbox.setVisibility(View.GONE);

        var permName = (PermissionNameSupplier) requireNonNull(checkbox.getTag(), "PermissionNameSupplier");
        checkbox.setTag(null);

        int state = checkbox.isChecked() ?
                PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED :
                PackageInstaller.SessionParams.PERMISSION_STATE_DENIED;
        dst.add(new SpecialPermissionState(permName.get(), state));
    }

    public static void updatePermissionStates(PackageInstaller pkgInstaller, int sessionId, List<SpecialPermissionState> list) {
        if (list.isEmpty()) {
            return;
        }

        int num = list.size();
        String[] permissions = new String[num];
        int[] states = new int[num];
        for (int i = 0; i < num; ++i) {
            SpecialPermissionState ps = list.get(i);
            permissions[i] = ps.getPermission();
            states[i] = ps.getState();
            Log.d(TAG, "updatePermissionStates: " + ps);
        }

        pkgInstaller.updatePermissionStates(sessionId, permissions, states);
    }
}
