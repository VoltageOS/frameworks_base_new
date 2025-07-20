/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs.footer.dagger

import android.content.Context
import android.telephony.SubscriptionManager
import com.android.settingslib.net.DataUsageController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.footer.data.repository.ForegroundServicesRepository
import com.android.systemui.qs.footer.data.repository.ForegroundServicesRepositoryImpl
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractorImpl
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsDataUsageViewModel
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.connectivity.NetworkController
import android.net.wifi.WifiManager
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.settings.GlobalSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import javax.inject.Inject

/** Dagger module to provide/bind footer actions singletons. */
@Module
interface FooterActionsModule {

    @Binds
    fun foregroundServicesRepository(
        impl: ForegroundServicesRepositoryImpl
    ): ForegroundServicesRepository

    @Binds fun footerActionsInteractor(impl: FooterActionsInteractorImpl): FooterActionsInteractor

    companion object {
        @Provides
        @SysUISingleton
        fun provideDataUsageController(context: Context): DataUsageController {
            return DataUsageController(context)
        }

        @Provides
        @SysUISingleton
        fun provideFooterActionsDataUsageViewModel(
            context: Context,
            dataController: DataUsageController,
            subManager: SubscriptionManager,
            wifiManager: WifiManager?,
            networkController: NetworkController,
            tunerService: TunerService,
            globalSettings: GlobalSettings,
            activityStarter: ActivityStarter,
            userTracker: UserTracker
        ): FooterActionsDataUsageViewModel {
            return FooterActionsDataUsageViewModel(
                context,
                dataController,
                subManager,
                wifiManager,
                networkController,
                tunerService,
                globalSettings,
                activityStarter,
                userTracker
            )
        }
    }
}
