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
import com.android.systemui.qs.tiles.NfcTile
import com.android.systemui.qs.tiles.HeadsUpTile
import com.android.systemui.qs.tiles.SyncTile
import com.android.systemui.qs.tiles.CPUInfoTile
import com.android.systemui.qs.tiles.FPSInfoTile
import com.android.systemui.qs.tiles.AmbientDisplayTile
import com.android.systemui.qs.tiles.AODTile
import com.android.systemui.qs.tiles.CaffeineTile
import com.android.systemui.qs.tiles.CompassTile
import com.android.systemui.qs.tiles.ScreenshotTile
import com.android.systemui.qs.tiles.SoundTile
import com.android.systemui.qs.tiles.UsbTetherTile
import com.android.systemui.qs.tiles.VPNTetheringTile
import com.android.systemui.qs.tiles.VolumeTile
import com.android.systemui.qs.tiles.VpnTile
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

    /** Inject CaffeineTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CaffeineTile.TILE_SPEC)
    fun bindCaffeineTile(caffeineTile: CaffeineTile): QSTileImpl<*>

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

    /** Inject CompassTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CompassTile.TILE_SPEC)
    fun bindCompassTile(compassTile: CompassTile): QSTileImpl<*>

    /** Inject HeadsUpTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(HeadsUpTile.TILE_SPEC)
    fun bindHeadsUpTile(headsUpTile: HeadsUpTile): QSTileImpl<*>

    /** Inject ScreenshotTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(ScreenshotTile.TILE_SPEC)
    fun bindScreenshotTile(screenshotTile: ScreenshotTile): QSTileImpl<*>

    /** Inject SyncTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(SyncTile.TILE_SPEC)
    fun bindSyncTile(syncTile: SyncTile): QSTileImpl<*>

    /** Inject AmbientDisplayTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AmbientDisplayTile.TILE_SPEC)
    fun bindAmbientDisplayTile(ambientDisplayTile: AmbientDisplayTile): QSTileImpl<*>

    /** Inject AODTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(AODTile.TILE_SPEC)
    fun bindAODTile(aodTile: AODTile): QSTileImpl<*>

    /** Inject SoundTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(SoundTile.TILE_SPEC)
    fun bindSoundTile(soundTile: SoundTile): QSTileImpl<*>

    /** Inject UsbTetherTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(UsbTetherTile.TILE_SPEC)
    fun bindUsbTetherTile(usbTetherTile: UsbTetherTile): QSTileImpl<*>

    /** Inject VPNTetheringTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(VPNTetheringTile.TILE_SPEC)
    fun bindVPNTetheringTile(vpnTetheringTile: VPNTetheringTile): QSTileImpl<*>

    /** Inject CPUInfoTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(CPUInfoTile.TILE_SPEC)
    fun CPUInfoTile(cpuInfoTile: CPUInfoTile): QSTileImpl<*>

    /** Inject FPSInfoTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(FPSInfoTile.TILE_SPEC)
    fun FPSInfoTile(fpsInfoTile: FPSInfoTile): QSTileImpl<*>

    /** Inject VolumeTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(VolumeTile.TILE_SPEC)
    fun bindVolumeTile(volumeTile: VolumeTile): QSTileImpl<*>

    /** Inject VpnTile into tileMap in QSModule */
    @Binds
    @IntoMap
    @StringKey(VpnTile.TILE_SPEC)
    fun bindVpnTile(vpnTile: VpnTile): QSTileImpl<*>

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

        @Provides
        @IntoMap
        @StringKey(NfcTile.TILE_SPEC)
        fun provideNfcConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(NfcTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_nfc,
                    labelRes = R.string.quick_settings_nfc_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }
        @Provides
        @IntoMap
        @StringKey(HeadsUpTile.TILE_SPEC)
        fun provideHeadsUpConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(HeadsUpTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_heads_up,
                    labelRes = R.string.quick_settings_heads_up_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }

        @Provides
        @IntoMap
        @StringKey(SyncTile.TILE_SPEC)
        fun provideSyncConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(SyncTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_sync,
                    labelRes = R.string.quick_settings_sync_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }

        @Provides
        @IntoMap
        @StringKey(AmbientDisplayTile.TILE_SPEC)
        fun provideAmbientDisplayConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(AmbientDisplayTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_ambient_display,
                    labelRes = R.string.quick_settings_ambient_display_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }
 
        @Provides
        @IntoMap
        @StringKey(AODTile.TILE_SPEC)
        fun provideAODConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(AODTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_aod,
                    labelRes = R.string.quick_settings_aod_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }

        @Provides
        @IntoMap
        @StringKey(CaffeineTile.TILE_SPEC)
        fun provideCaffeineConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(CaffeineTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_caffeine,
                    labelRes = R.string.quick_settings_caffeine_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }

       @Provides
        @IntoMap
        @StringKey(SoundTile.TILE_SPEC)
        fun provideSoundConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(SoundTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_ringer_audible,
                    labelRes = R.string.quick_settings_sound_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

       @Provides
        @IntoMap
        @StringKey(CompassTile.TILE_SPEC)
        fun provideCompassConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(CompassTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_compass,
                    labelRes = R.string.quick_settings_compass_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

       @Provides
        @IntoMap
        @StringKey(UsbTetherTile.TILE_SPEC)
        fun provideUsbTetherConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(UsbTetherTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_usb_tether,
                    labelRes = R.string.quick_settings_usb_tether_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

       @Provides
        @IntoMap  
        @StringKey(VPNTetheringTile.TILE_SPEC)
        fun provideVPNTetherConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(VPNTetheringTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_vpn_tethering,
                    labelRes = R.string.vpn_tethering_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

       @Provides
        @IntoMap
        @StringKey(CPUInfoTile.TILE_SPEC)
        fun provideCPUInfoConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(CPUInfoTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_cpu_info,
                    labelRes = R.string.quick_settings_cpuinfo_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

       @Provides
        @IntoMap
        @StringKey(FPSInfoTile.TILE_SPEC)
        fun provideFPSInfoConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(FPSInfoTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_fps_info,
                    labelRes = R.string.quick_settings_fpsinfo_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.DISPLAY
            )
        }

       @Provides
        @IntoMap
        @StringKey(VolumeTile.TILE_SPEC)
        fun provideVolumeInfoConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(VolumeTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_volume_panel,
                    labelRes = R.string.quick_settings_volume_panel_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }

       @Provides
        @IntoMap
        @StringKey(ScreenshotTile.TILE_SPEC)
        fun provideScreenshotInfoConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(ScreenshotTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_screenshot,
                    labelRes = R.string.global_action_screenshot
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES
            )
        }
       @Provides
        @IntoMap
        @StringKey(VpnTile.TILE_SPEC)
        fun provideVpnConfig(uiEventLogger: QsEventLogger): QSTileConfig {
            return QSTileConfig(
                tileSpec = TileSpec.create(VpnTile.TILE_SPEC),
                uiConfig = QSTileUIConfig.Resource(
                    iconRes = R.drawable.ic_qs_vpn,
                    labelRes = R.string.quick_settings_vpn_label
                ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.CONNECTIVITY
            )
        }
    }
}
