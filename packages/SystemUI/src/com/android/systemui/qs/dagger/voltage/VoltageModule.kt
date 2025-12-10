/*
 * Copyright (C) 2025 VoltageOS
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

package com.android.systemui.qs.dagger.voltage

import android.content.Context
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.RefreshRateTile
import com.android.systemui.qs.tiles.CellularTileLegacy
import com.android.systemui.qs.tiles.WifiTileLegacy
import com.android.systemui.qs.tiles.base.shared.model.QSTileConfig;
import com.android.systemui.qs.tiles.base.shared.model.QSTilePolicy;
import com.android.systemui.qs.tiles.base.shared.model.QSTileUIConfig;
import com.android.systemui.res.R

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface VoltageModule {

    @Binds
    @IntoMap
    @StringKey(RefreshRateTile.TILE_SPEC)
    fun bindRefreshRateTile(refreshRateTile: RefreshRateTile): QSTileImpl<*>

    /** Inject CellularTileLegacy into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CellularTileLegacy.TILE_SPEC)
    fun bindCellularTileLegacy(cellularTileLegacy: CellularTileLegacy): QSTileImpl<*>


    /** Inject WifiTileLegacy into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(WifiTileLegacy.TILE_SPEC)
    fun bindWifiTileLegacy(wifiTileLegacy: WifiTileLegacy): QSTileImpl<*>

    companion object {
        @Provides
        @IntoMap
        @StringKey(RefreshRateTile.TILE_SPEC)
        fun provideRefreshRateTileConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(RefreshRateTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_refresh_rate,
                    labelRes = R.string.refresh_rate_tile_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }

        @Provides
        @IntoMap
        @StringKey(CellularTileLegacy.TILE_SPEC)
        fun provideCellularTileLegacyConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(CellularTileLegacy.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_swap_vert,
                    labelRes = R.string.quick_settings_cellular_detail_title
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(WifiTileLegacy.TILE_SPEC)
        fun provideWifiTileLegacyConfig(uiEventLogger: QsEventLogger, context: Context): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(WifiTileLegacy.TILE_SPEC),
		uiConfig = QSTileUIConfig.Resource(
                    iconRes = context.resources.getIdentifier(
                        "ic_signal_wifi_transient_animation", "drawable", "android"
                    ),
                    labelRes = R.string.quick_settings_wifi_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )                       
        }
    }
}
