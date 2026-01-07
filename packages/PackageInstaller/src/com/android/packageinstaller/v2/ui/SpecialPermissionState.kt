package com.android.packageinstaller.v2.ui

import android.content.pm.PackageInstaller.SessionParams.PERMISSION_STATE_DENIED
import android.content.pm.PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED

data class SpecialPermissionState(val permission: String, val state: Int) {

    init {
        check(state == PERMISSION_STATE_GRANTED || state == PERMISSION_STATE_DENIED)
    }

    override fun toString(): String {
        return "SpecialPermissionState[$permission: ${if (state == PERMISSION_STATE_GRANTED) "GRANTED" else "DENIED"}]"
    }
}
