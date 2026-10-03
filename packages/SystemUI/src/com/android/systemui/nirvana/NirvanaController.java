/*
 * Copyright (C) 2026 VoltageOS
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
package com.android.systemui.nirvana;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.SuspendDialogInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.internal.util.voltage.nirvana.NirvanaConstants;
import com.android.internal.util.voltage.nirvana.NirvanaState;
import com.android.internal.util.voltage.nirvana.NirvanaSuspension;
import com.android.internal.util.voltage.nirvana.NirvanaTimeLimits;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

@SysUISingleton
public class NirvanaController implements CoreStartable, TunerService.Tunable {
    private final Context mContext;
    private final StatusBarIconController mIconController;
    private final TunerService mTunerService;
    private final Handler mHandler;
    private final ContentResolver mResolver;
    private final String mSlot;
    private boolean mIconInit;
    private boolean mShowIcon;

    private final NirvanaTimeLimits.DialogInfoFactory mTimeLimitFactory =
            new NirvanaTimeLimits.DialogInfoFactory() {
                @Override
                public SuspendDialogInfo forPackage(Context context, String packageName) {
                    String label = packageName;
                    try {
                        label = context.getPackageManager()
                                .getApplicationInfo(packageName, 0)
                                .loadLabel(context.getPackageManager()).toString();
                    } catch (Exception ignored) {
                    }
                    SuspendDialogInfo.Builder builder = new SuspendDialogInfo.Builder()
                            .setTitle(R.string.nirvana_time_limit_dialog_title)
                            .setMessage(context.getString(
                                    R.string.nirvana_time_limit_dialog_message, label))
                            .setIcon(R.drawable.ic_qs_nirvana);
                    return builder.build();
                }
            };

    @Inject
    public NirvanaController(Context context,
            StatusBarIconController iconController,
            TunerService tunerService,
            @Main Handler handler) {
        mContext = context;
        mIconController = iconController;
        mTunerService = tunerService;
        mHandler = handler;
        mResolver = context.getContentResolver();
        mSlot = NirvanaConstants.SLOT_NIRVANA;
    }

    @Override
    public void start() {
        SettingsObserver observer = new SettingsObserver(mHandler);
        observer.observe();
        IntentFilter filter = new IntentFilter();
        filter.addAction(NirvanaConstants.ACTION_UPDATE);
        filter.addAction(NirvanaConstants.ACTION_DAILY_RESET);
        filter.addAction(NirvanaConstants.ACTION_FORCE_UNSUSPEND);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter limitFilter = new IntentFilter();
        limitFilter.addAction(NirvanaConstants.ACTION_LIMIT_REACHED);
        limitFilter.addDataScheme("nirvana-limit");
        mContext.registerReceiver(mReceiver, limitFilter, Context.RECEIVER_NOT_EXPORTED);
        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiver(mReceiver, packageFilter, Context.RECEIVER_NOT_EXPORTED);
        mTunerService.addTunable(this, NirvanaConstants.KEY_STATUSBAR_ICON);
        mHandler.post(() -> {
            NirvanaSuspension.migrateLegacySuspensions(mContext);
            NirvanaSuspension.validateTrackedState(mContext);
            handleUpdate(false);
            NirvanaTimeLimits.onBoot(mContext, mTimeLimitFactory);
            updateStatusbarIcon();
        });
    }

    private SuspendDialogInfo buildModeDialogInfo() {
        return new SuspendDialogInfo.Builder()
                .setTitle(R.string.nirvana_mode_dialog_title)
                .setMessage(mContext.getString(R.string.nirvana_mode_dialog_message))
                .setIcon(R.drawable.ic_qs_nirvana)
                .build();
    }

    private void handleUpdate(boolean fromBoot) {
        NirvanaSuspension.reconcileState(mContext, buildModeDialogInfo());
        if (NirvanaState.isScheduleEnabled(mResolver)) {
            NirvanaSuspension.scheduleNextAlarm(mContext);
        }
        if (!fromBoot) {
            NirvanaTimeLimits.refresh(mContext, mTimeLimitFactory);
        }
        updateStatusbarIcon();
    }

    private void updateStatusbarIcon() {
        try {
            if (!mIconInit) {
                mIconController.setIcon(mSlot, R.drawable.ic_qs_nirvana,
                        mContext.getString(R.string.quick_settings_nirvana_label));
                mIconInit = true;
            }
            boolean visible = mShowIcon && NirvanaState.shouldBeActive(mResolver);
            mIconController.setIconVisibility(mSlot, visible);
        } catch (Exception ignored) {
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (NirvanaConstants.ACTION_UPDATE.equals(action)
                    || Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    NirvanaSuspension.migrateLegacySuspensions(mContext);
                    NirvanaSuspension.validateTrackedState(mContext);
                    handleUpdate(true);
                    NirvanaTimeLimits.onBoot(mContext, mTimeLimitFactory);
                } else {
                    handleUpdate(false);
                }
            } else if (NirvanaConstants.ACTION_LIMIT_REACHED.equals(action)) {
                String pkg = intent.getStringExtra(NirvanaConstants.EXTRA_LIMIT_PACKAGE);
                int userId = intent.getIntExtra(
                        NirvanaConstants.EXTRA_LIMIT_USER, UserHandle.myUserId());
                if (pkg != null && !pkg.isEmpty()) {
                    NirvanaTimeLimits.onLimitReached(mContext, pkg, userId, mTimeLimitFactory);
                    updateStatusbarIcon();
                }
            } else if (NirvanaConstants.ACTION_DAILY_RESET.equals(action)) {
                NirvanaTimeLimits.onDailyReset(mContext, mTimeLimitFactory);
                updateStatusbarIcon();
            } else if (NirvanaConstants.ACTION_FORCE_UNSUSPEND.equals(action)) {
                NirvanaSuspension.forceUnsuspendAll(mContext);
                NirvanaTimeLimits.refresh(mContext, mTimeLimitFactory);
                updateStatusbarIcon();
            } else if (Intent.ACTION_PACKAGE_FULLY_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                NirvanaTimeLimits.onPackagesChanged(mContext, mTimeLimitFactory);
            } else if (Intent.ACTION_PACKAGES_UNSUSPENDED.equals(action)
                    || Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                if (NirvanaState.shouldBeActive(mResolver)) {
                    String[] changed =
                            intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    Uri data = intent.getData();
                    String single = data != null ? data.getSchemeSpecificPart() : null;
                    List<String> candidates = new ArrayList<>();
                    if (changed != null) {
                        for (String pkg : changed) {
                            candidates.add(pkg);
                        }
                    }
                    if (single != null) {
                        candidates.add(single);
                    }
                    if (!candidates.isEmpty()) {
                        NirvanaSuspension.enforcePackages(mContext, candidates,
                                buildModeDialogInfo());
                    }
                }
                NirvanaTimeLimits.onPackagesChanged(mContext, mTimeLimitFactory);
            }
        }
    };

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (NirvanaConstants.KEY_STATUSBAR_ICON.equals(key)) {
            mShowIcon = TunerService.parseIntegerSwitch(newValue, false);
            mHandler.post(() -> updateStatusbarIcon());
        }
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(NirvanaConstants.KEY_MANUAL_ACTIVE),
                    false, this, UserHandle.USER_ALL);
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(NirvanaConstants.KEY_SCHEDULE_ENABLED),
                    false, this, UserHandle.USER_ALL);
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(NirvanaConstants.KEY_START_TIME),
                    false, this, UserHandle.USER_ALL);
            mResolver.registerContentObserver(
                    Settings.Secure.getUriFor(NirvanaConstants.KEY_END_TIME),
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.post(() -> {
                NirvanaSuspension.reconcileState(mContext, buildModeDialogInfo());
                updateStatusbarIcon();
            });
        }
    }
}
