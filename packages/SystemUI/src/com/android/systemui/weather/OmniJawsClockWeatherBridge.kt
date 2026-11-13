package com.android.systemui.weather

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.core.text.util.LocalePreferences
import com.android.internal.util.crdroid.OmniJawsClient
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.plugins.keyguard.data.model.WeatherData
import com.android.systemui.plugins.keyguard.data.model.WeatherData.WeatherStateIcon
import com.android.systemui.util.concurrency.DelayableExecutor

class OmniJawsClockWeatherBridge(
    private val context: Context,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val mainExecutor: DelayableExecutor,
) : OmniJawsClient.OmniJawsObserver {

    private var started = false
    private var observingWeather = false
    private var bootRetries = 0

    private val settingsObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                updateWeatherObservation()
            }
        }

    fun start() {
        if (started) return
        started = true
        context.contentResolver.registerContentObserver(
            Settings.System.getUriFor(LOCKSCREEN_WEATHER_ENABLED),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        updateWeatherObservation()
    }

    fun stop() {
        if (!started) return
        started = false
        context.contentResolver.unregisterContentObserver(settingsObserver)
        if (observingWeather) {
            OmniJawsClient.get().removeObserver(context, this)
            observingWeather = false
        }
    }

    override fun weatherUpdated() {
        pushWeather()
    }

    override fun weatherError(errorReason: Int) {}

    private fun updateWeatherObservation() {
        val enabled =
            Settings.System.getIntForUser(
                context.contentResolver,
                LOCKSCREEN_WEATHER_ENABLED,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        if (enabled && !observingWeather) {
            observingWeather = true
            bootRetries = 0
            OmniJawsClient.get().addObserver(context, this)
            pushWeather()
        } else if (!enabled && observingWeather) {
            observingWeather = false
            OmniJawsClient.get().removeObserver(context, this)
        }
    }

    private fun pushWeather() {
        if (!started || !observingWeather) return
        OmniJawsClient.get().queryWeather(context)
        val info = OmniJawsClient.get().weatherInfo
        if (info == null) {
            if (bootRetries < MAX_BOOT_RETRIES) {
                bootRetries++
                mainExecutor.executeDelayed({ pushWeather() }, BOOT_RETRY_DELAY_MS)
            }
            return
        }
        bootRetries = 0
        val temperature = info.temp?.toIntOrNull() ?: return
        keyguardUpdateMonitor.sendWeatherData(
            WeatherData(
                description = info.condition ?: "",
                state = mapConditionCode(info.conditionCode),
                useCelsius =
                    info.tempUnits?.contains("C")
                        ?: (LocalePreferences.getTemperatureUnit() ==
                            LocalePreferences.TemperatureUnit.CELSIUS),
                temperature = temperature,
            )
        )
    }

    private fun mapConditionCode(code: Int): WeatherStateIcon {
        return when (code) {
            0 -> WeatherStateIcon.TORNADO
            1, 2 -> WeatherStateIcon.TROPICAL_STORM_HURRICANE
            3 -> WeatherStateIcon.STRONG_TSTORMS
            4, 37, 38, 39, 45, 47 -> WeatherStateIcon.ISOLATED_SCATTERED_TSTORMS_DAY
            5 -> WeatherStateIcon.WINTRY_MIX_RAIN_SNOW
            6, 35 -> WeatherStateIcon.MIXED_RAIN_HAIL_RAIN_SLEET
            7, 17, 18 -> WeatherStateIcon.SLEET_HAIL
            8, 9 -> WeatherStateIcon.DRIZZLE
            10, 11, 12 -> WeatherStateIcon.SHOWERS_RAIN
            13 -> WeatherStateIcon.FLURRIES
            14, 42 -> WeatherStateIcon.SCATTERED_SNOW_SHOWERS_DAY
            15 -> WeatherStateIcon.BLOWING_SNOW
            16, 41, 43 -> WeatherStateIcon.HEAVY_SNOW
            19, 20, 21, 22 -> WeatherStateIcon.HAZE_FOG_DUST_SMOKE
            23, 24 -> WeatherStateIcon.WINDY_BREEZY
            26 -> WeatherStateIcon.CLOUDY
            27 -> WeatherStateIcon.MOSTLY_CLOUDY_NIGHT
            28 -> WeatherStateIcon.MOSTLY_CLOUDY_DAY
            29 -> WeatherStateIcon.PARTLY_CLOUDY_NIGHT
            30, 44 -> WeatherStateIcon.PARTLY_CLOUDY
            31 -> WeatherStateIcon.CLEAR_NIGHT
            32, 36 -> WeatherStateIcon.SUNNY
            33 -> WeatherStateIcon.MOSTLY_CLEAR_NIGHT
            34 -> WeatherStateIcon.MOSTLY_SUNNY
            40 -> WeatherStateIcon.SCATTERED_SHOWERS_DAY
            46 -> WeatherStateIcon.SNOW_SHOWERS_SNOW
            else -> WeatherStateIcon.UNKNOWN_ICON
        }
    }

    companion object {
        private const val LOCKSCREEN_WEATHER_ENABLED = "lockscreen_weather_enabled"
        private const val MAX_BOOT_RETRIES = 6
        private const val BOOT_RETRY_DELAY_MS = 10000L
    }
}