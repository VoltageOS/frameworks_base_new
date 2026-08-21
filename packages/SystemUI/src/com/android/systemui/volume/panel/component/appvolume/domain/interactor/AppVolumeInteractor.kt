/*
 * Copyright (C) 2026 Halcyon Project
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

package com.android.systemui.volume.panel.component.appvolume.domain.interactor

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.volume.panel.component.appvolume.domain.model.AppVolumeModel
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@SysUISingleton
class AppVolumeInteractor
@Inject
constructor(
    @Application private val context: Context,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundContext: CoroutineContext,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val refreshTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun getActiveAppVolumes(): List<AppVolumeModel> {
        val pm = context.packageManager
        val result = mutableListOf<AppVolumeModel>()
        for (vol in audioManager.listAppVolumes()) {
            if (vol.isActive) {
                val pkg = vol.packageName
                val label =
                    try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (e: PackageManager.NameNotFoundException) {
                        pkg
                    }
                val iconDrawable =
                    try {
                        pm.getApplicationIcon(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        null
                    }
                result.add(
                    AppVolumeModel(
                        packageName = pkg,
                        label = label,
                        icon = iconDrawable?.asIcon(),
                        volume = vol.volume,
                        isMuted = vol.isMuted,
                        isActive = vol.isActive,
                    )
                )
            }
        }
        return result
    }

    val activeAppVolumes: StateFlow<List<AppVolumeModel>> =
        callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val callback =
                object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(
                        configs: List<AudioPlaybackConfiguration>
                    ) {
                        trySend(getActiveAppVolumes())
                    }
                }
            audioManager.registerAudioPlaybackCallback(callback, handler)
            trySend(getActiveAppVolumes())
            awaitClose { audioManager.unregisterAudioPlaybackCallback(callback) }
        }
            .flowOn(backgroundContext)
            .stateIn(coroutineScope, SharingStarted.Eagerly, getActiveAppVolumes())

    fun refresh() {
        coroutineScope.launch(backgroundContext) {
            refreshTrigger.tryEmit(Unit)
        }
    }

    fun setAppVolume(packageName: String, volume: Float) {
        audioManager.setAppVolume(packageName, volume.coerceIn(0f, 1f))
    }
}
