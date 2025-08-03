/*
 * Copyright (C) 2025 the AxionAOSP Project
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
package com.android.systemui.weather

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.android.internal.util.crdroid.OmniJawsClient
import com.android.systemui.Dependency
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.res.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class WeatherViewController(
    private val context: Context,
    private val weatherIcon: ImageView,
    private val weatherTemp: TextView,
    private val weatherInfoView: View,
) : OmniJawsClient.OmniJawsObserver {

    // 1. Use the new singleton getter instead of the old constructor
    private val weatherClient = OmniJawsClient.get()
    private var weatherInfo: OmniJawsClient.WeatherInfo? = null
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var mDozing = false
    private val statusBarStateController: StatusBarStateController = Dependency.get(StatusBarStateController::class.java)

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {}

        override fun onDozingChanged(dozing: Boolean) {
            if (mDozing == dozing) {
                return
            }
            mDozing = dozing
            val weatherEnabled = weatherSettingsFlow.value.weatherEnabled
            val visible = !mDozing && weatherEnabled
            scope.launch {
                updateViewVisibility(weatherInfoView, visible)
            }
        }
    }

    private val weatherSettingsFlow = flow {
        while (true) {
            emit(getWeatherSettings())
            delay(1000)
        }
    }.stateIn(scope, SharingStarted.Eagerly, getWeatherSettings())

    fun init() {
        scope.launch {
            weatherSettingsFlow.collectLatest { applyWeatherSettings(it) }
        }
        statusBarStateController.addCallback(statusBarStateListener)
        statusBarStateListener.onDozingChanged(statusBarStateController.isDozing())
    }

    private fun getConditionText(condition: String): String {
        for ((key, value) in WEATHER_CONDITIONS) {
            if (condition.contains(key)) {
                return context.resources.getString(value)
            }
        }
        return condition
    }

    private fun getWeatherSettings() = WeatherSettings(
        weatherEnabled = getSystemSetting(LOCKSCREEN_WEATHER_ENABLED),
        showWeatherLocation = getSystemSetting(LOCKSCREEN_WEATHER_LOCATION),
        showWeatherText = getSystemSetting(LOCKSCREEN_WEATHER_TEXT, defaultValue = 1),
        showWindInfo = getSystemSetting(LOCKSCREEN_WEATHER_WIND_INFO),
        showHumidityInfo = getSystemSetting(LOCKSCREEN_WEATHER_HUMIDITY_INFO)
    )

    private fun getSystemSetting(setting: String, defaultValue: Int = 0) =
        Settings.System.getIntForUser(context.contentResolver, setting, defaultValue, UserHandle.USER_CURRENT) != 0

    private fun applyWeatherSettings(settings: WeatherSettings) {
        scope.launch {
            if (!settings.weatherEnabled) {
                hideAllViews()
                // 2. Pass context to removeObserver
                weatherClient.removeObserver(context, this@WeatherViewController)
            } else {
                // 3. Pass context to addObserver
                weatherClient.addObserver(context, this@WeatherViewController)
                updateWeather()
            }
            updateViewVisibility(weatherInfoView, settings.weatherEnabled)
            updateViewVisibility(weatherIcon, settings.weatherEnabled)
            updateViewVisibility(weatherTemp, settings.weatherEnabled)
        }
    }

    override fun weatherUpdated() = updateWeather()

    private fun forceRefresh() {
        // 4. Pass context to isOmniJawsEnabled
        if (weatherClient.isOmniJawsEnabled(context)) {
            val values = ContentValues().apply {
                put("update", true)
            }
            // 5. Use the locally defined CONTROL_URI
            context.contentResolver.update(CONTROL_URI, values, "", null)
        }
    }

    private fun updateWeather() {
        if (!weatherSettingsFlow.value.weatherEnabled) {
            hideAllViews()
            return
        }

        scope.launch {
            try {
                val localWeatherInfo = withContext(Dispatchers.IO) {
                    // 6. Pass context to queryWeather
                    weatherClient.queryWeather(context)
                    weatherClient.weatherInfo
                }
                weatherInfo = localWeatherInfo

                localWeatherInfo?.let { info ->
                    updateViewVisibility(weatherIcon, true)
                    updateViewVisibility(weatherTemp, true)
                    // 7. Pass context to getWeatherConditionImage
                    weatherIcon.setImageDrawable(weatherClient.getWeatherConditionImage(context, info.conditionCode))
                    weatherTemp.text = buildWeatherText(info)
                    weatherTemp.isSelected = true
                } ?: run {
                    hideAllViews()
                    forceRefresh()
                }
            } catch (e: Exception) {
                Log.e("WeatherViewController", "Failed to update weather", e)
                hideAllViews()
            }
        }
    }

    private fun hideAllViews() {
        scope.launch {
            updateViewVisibility(weatherInfoView, false)
            updateViewVisibility(weatherIcon, false)
            updateViewVisibility(weatherTemp, false)
        }
    }

    private fun buildWeatherText(info: OmniJawsClient.WeatherInfo): String {
        val settings = weatherSettingsFlow.value
        val conditionText = getConditionText(info.condition.lowercase())

        val locationText = if (settings.showWeatherLocation) " • ${info.city}" else ""
        val conditionDisplay = if (settings.showWeatherText) " • $conditionText" else ""
        val windDisplay = if (settings.showWindInfo) " • ${info.windSpeed} ${info.windUnits} ${info.pinWheel}" else ""
        val humidityDisplay = if (settings.showHumidityInfo) " • ${info.humidity}" else ""

        return "${info.temp}${info.tempUnits}$locationText$conditionDisplay$windDisplay$humidityDisplay"
    }

    override fun weatherError(errorReason: Int) {
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            weatherInfo = null
            weatherIcon.setImageDrawable(null)
        }
    }

    fun removeObserver() {
        scope.cancel()
        // 8. Pass context to removeObserver
        weatherClient.removeObserver(context, this)
        statusBarStateController.removeCallback(statusBarStateListener)
    }

    private fun updateViewVisibility(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    data class WeatherSettings(
        val weatherEnabled: Boolean,
        val showWeatherLocation: Boolean,
        val showWeatherText: Boolean,
        val showWindInfo: Boolean,
        val showHumidityInfo: Boolean
    )

    companion object {
        // 9. Define CONTROL_URI here since it was removed from OmniJawsClient
        private val CONTROL_URI = Uri.parse("content://org.omnirom.omnijaws.provider/control")

        private const val LOCKSCREEN_WEATHER_ENABLED = "lockscreen_weather_enabled"
        private const val LOCKSCREEN_WEATHER_LOCATION = "lockscreen_weather_location"
        private const val LOCKSCREEN_WEATHER_TEXT = "lockscreen_weather_text"
        private const val LOCKSCREEN_WEATHER_WIND_INFO = "lockscreen_weather_wind_info"
        private const val LOCKSCREEN_WEATHER_HUMIDITY_INFO = "lockscreen_weather_humidity_info"

        private val WEATHER_CONDITIONS = mapOf(
            "clouds" to R.string.weather_condition_clouds,
            "rain" to R.string.weather_condition_rain,
            "clear" to R.string.weather_condition_clear,
            "storm" to R.string.weather_condition_storm,
            "snow" to R.string.weather_condition_snow,
            "wind" to R.string.weather_condition_wind,
            "mist" to R.string.weather_condition_mist
        )
    }
}
