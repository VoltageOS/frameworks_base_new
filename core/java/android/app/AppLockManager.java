/*
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.RequiresPermission;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.os.RemoteException;

import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.VerifyCredentialResponse;

import java.util.List;

/**
 * @hide
 */
@SystemService(Context.APP_LOCK_SERVICE)
public final class AppLockManager {

    /** @hide */
    public static final long DEFAULT_TIMEOUT = 10 * 1000;

    /** @hide */
    public static final boolean DEFAULT_BIOMETRICS_ALLOWED = true;

    /** @hide */
    public static final boolean DEFAULT_PROTECT_APP = false;

    /** @hide */
    public static final boolean DEFAULT_REDACT_NOTIFICATION = false;

    /** @hide */
    public static final boolean DEFAULT_HIDE_IN_LAUNCHER = false;

    /** @hide */
    public static final int APP_LOCK_CREDENTIAL_TYPE_NONE = 0;

    /** @hide */
    public static final int APP_LOCK_CREDENTIAL_TYPE_PIN = 1;

    /** @hide */
    public static final int APP_LOCK_CREDENTIAL_TYPE_PATTERN = 2;

    /** @hide */
    public static final int APP_LOCK_RELOCK_BEHAVIOR_TIMEOUT = 0;

    /** @hide */
    public static final int APP_LOCK_RELOCK_BEHAVIOR_SCREEN_OFF = 1;

    /** @hide */
    public static final boolean DEFAULT_BIOMETRIC_PROMPT_ENABLED = false;

    /** @hide */
    public static final int DEFAULT_RELOCK_BEHAVIOR = APP_LOCK_RELOCK_BEHAVIOR_TIMEOUT;

    /**
     * Intent action for starting credential activity in SystemUI.
     * @hide
     */
    public static final String ACTION_UNLOCK_APP = "android.app.action.UNLOCK_APP";

    /**
     * Intent extra to indicate whether usage of biometrics is allowed.
     * @hide
     */
    public static final String EXTRA_ALLOW_BIOMETRICS = "android.app.AppLockManager.ALLOW_BIOMETRICS";

    /**
     * Intent extra for the name of the application to unlock.
     * @hide
     */
    public static final String EXTRA_PACKAGE_LABEL = "android.app.AppLockManager.PACKAGE_LABEL";

    private final Context mContext;
    private final IAppLockManagerService mService;

    /** @hide */
    AppLockManager(Context context, IAppLockManagerService service) {
        mContext = context;
        mService = service;
    }

    /**
     * Set whether app should be protected by app lock
     * in locked state. Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the package name.
     * @param shouldProtectApp true to hide notification content.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setShouldProtectApp(@NonNull String packageName, boolean shouldProtectApp) {
        try {
            mService.setShouldProtectApp(packageName, shouldProtectApp, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current auto lock timeout.
     *
     * @param userId the user id given by the caller.
     * @return the timeout in milliseconds if configuration for
     *     current user exists, -1 otherwise.
     * @hide
     */
    @UserHandleAware
    public long getTimeout() {
        try {
            return mService.getTimeout(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set auto lock timeout.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param timeout the timeout in milliseconds. Must be >= 5.
     * @param userId the user id given by the caller.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setTimeout(long timeout) {
        try {
            mService.setTimeout(timeout, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get all the packages protected with app lock.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @return a unique list of {@link AppLockData} of the protected apps.
     * @hide
     */
    @UserHandleAware
    @NonNull
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public List<AppLockData> getPackageData() {
        try {
            return mService.getPackageData(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether notification content should be redacted for a package
     * in locked state. Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the package name.
     * @param shouldRedactNotification true to hide notification content.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setShouldRedactNotification(@NonNull String packageName, boolean shouldRedactNotification) {
        try {
            mService.setShouldRedactNotification(packageName, shouldRedactNotification, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether to allow unlocking with biometrics.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param biometricsAllowed whether to use biometrics.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setBiometricsAllowed(boolean biometricsAllowed) {
        try {
            mService.setBiometricsAllowed(biometricsAllowed, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether biometrics is allowed for unlocking.
     *
     * @return true if biometrics will be used for unlocking, false otherwise.
     * @hide
     */
    @UserHandleAware
    public boolean isBiometricsAllowed() {
        try {
            return mService.isBiometricsAllowed(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether to auto-show the biometric prompt when app lock is opened.
     *
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setBiometricPromptEnabled(boolean enabled) {
        try {
            mService.setBiometricPromptEnabled(enabled, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Whether app lock should auto-show biometrics when available.
     *
     * @hide
     */
    @UserHandleAware
    public boolean isBiometricPromptEnabled() {
        try {
            return mService.isBiometricPromptEnabled(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the app lock relock behavior.
     *
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setRelockBehavior(int relockBehavior) {
        try {
            mService.setRelockBehavior(relockBehavior, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the app lock relock behavior.
     *
     * @hide
     */
    @UserHandleAware
    public int getRelockBehavior() {
        try {
            return mService.getRelockBehavior(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the separate app lock credential for the current user. This also enables separate
     * app lock credentials.
     *
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setSeparateCredential(@NonNull LockscreenCredential credential) {
        try {
            mService.setSeparateCredential(credential, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clear the separate app lock credential and fall back to the device credential.
     *
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void clearSeparateCredential() {
        try {
            mService.clearSeparateCredential(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Whether app lock uses a separate credential instead of the device credential.
     *
     * @hide
     */
    @UserHandleAware
    public boolean isSeparateCredentialEnabled() {
        try {
            return mService.isSeparateCredentialEnabled(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the currently configured separate app lock credential type.
     *
     * @hide
     */
    @UserHandleAware
    public int getSeparateCredentialType() {
        try {
            return mService.getSeparateCredentialType(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Verify the supplied app lock credential.
     *
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    @NonNull
    public VerifyCredentialResponse verifyCredential(@NonNull LockscreenCredential credential) {
        try {
            return mService.verifyCredential(credential, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unlock a package following authentication with credentials.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the name of the package to unlock.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void unlockPackage(@NonNull String packageName) {
        try {
            mService.unlockPackage(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Hide or unhide an application from launcher.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @param packageName the name of the package to hide or unhide.
     * @param hide whether to hide or not.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    public void setPackageHidden(@NonNull String packageName, boolean hide) {
        try {
            mService.setPackageHidden(packageName, hide, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the list of applications hidden from launcher.
     * Caller must hold {@link android.permission.MANAGE_APP_LOCK}.
     *
     * @return list of package names of the hidden apps.
     * @hide
     */
    @UserHandleAware
    @RequiresPermission(Manifest.permission.MANAGE_APP_LOCK)
    @NonNull
    public List<String> getHiddenPackages() {
        try {
            return mService.getHiddenPackages(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether package is protected by app lock
     *
     * @return true if package is protected by app lock, false otherwise.
     * @hide
     */
    @UserHandleAware
    public boolean isPackageProtected(@NonNull String packageName) {
        try {
            return mService.isPackageProtected(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether package is hidden by app lock
     *
     * @return true if package is hidden by app lock, false otherwise.
     * @hide
     */
    @UserHandleAware
    public boolean isPackageHidden(@NonNull String packageName) {
        try {
            return mService.isPackageHidden(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
