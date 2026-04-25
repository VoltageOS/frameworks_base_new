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

package com.android.systemui.doze;

import android.app.AlarmManager;
import android.content.Context;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.Handler;
import android.pocket.PocketManager;
import android.provider.Settings;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.AlarmTimeout;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Limits screen-off AOD peek sessions to a short timeout when full AOD is not active.
 */
@DozeScope
public class DozeScreenOffPeekController implements DozeMachine.Part {
    private static final String TAG = "DozeScreenOffPeek";

    private final Context mContext;
    private final AmbientDisplayConfiguration mConfig;
    private final UserTracker mUserTracker;
    private final AlarmTimeout mPeekTimeout;
    private final PocketManager mPocketManager;
    private final DozeParameters mDozeParameters;

    private DozeMachine mMachine;

    @Inject
    DozeScreenOffPeekController(
            Context context,
            AmbientDisplayConfiguration config,
            UserTracker userTracker,
            DozeParameters dozeParameters,
            @Main Handler handler,
            AlarmManager alarmManager) {
        mContext = context;
        mConfig = config;
        mUserTracker = userTracker;
        mDozeParameters = dozeParameters;
        mPeekTimeout = new AlarmTimeout(alarmManager, this::onTimeout, TAG, handler);
        mPocketManager = (PocketManager) context.getSystemService(Context.POCKET_SERVICE);
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        final boolean peekActive = newState == DozeMachine.State.DOZE_AOD && shouldRunPeek();
        mDozeParameters.setScreenOffPeekActive(peekActive);

        if (peekActive) {
            if (shouldSkipForPocket()) {
                mMachine.requestState(DozeMachine.State.DOZE);
                return;
            }
            mPeekTimeout.schedule(getPeekDurationMillis(),
                    AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
            return;
        }
        mPeekTimeout.cancel();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeScreenOffPeekController:");
        pw.println(" enabled=" + shouldRunPeek());
        pw.println(" durationMs=" + getPeekDurationMillis());
    }

    private boolean shouldRunPeek() {
        return mConfig.screenOffPeekEnabled(mUserTracker.getUserId());
    }

    private boolean shouldSkipForPocket() {
        final int userId = mUserTracker.getUserId();
        final boolean pocketJudgeEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.POCKET_JUDGE, 0, userId) == 1;
        return pocketJudgeEnabled && mPocketManager != null && mPocketManager.isDeviceInPocket();
    }

    private long getPeekDurationMillis() {
        return mConfig.getScreenOffPeekDurationMillis(mUserTracker.getUserId());
    }

    private void onTimeout() {
        mMachine.requestState(DozeMachine.State.DOZE);
    }
}
