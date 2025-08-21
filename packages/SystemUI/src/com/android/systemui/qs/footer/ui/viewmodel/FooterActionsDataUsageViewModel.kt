/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.footer.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.telephony.SubscriptionManager
import android.text.format.Formatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.android.settingslib.net.DataUsageController
import com.android.systemui.animation.Expandable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.connectivity.NetworkController
import com.android.systemui.statusbar.connectivity.SignalCallback
import com.android.systemui.statusbar.connectivity.WifiIndicators
import android.net.wifi.WifiInfo
import com.android.systemui.tuner.TunerService
import com.android.systemui.util.settings.GlobalSettings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject


/**
 * ViewModel for data usage display in QS footer
 */
class FooterActionsDataUsageViewModel @Inject constructor(
    private val context: Context,
    private val dataController: DataUsageController,
    private val subManager: SubscriptionManager,
    private val wifiManager: WifiManager?,
    private val networkController: NetworkController,
    private val tunerService: TunerService,
    private val globalSettings: GlobalSettings,
    private val activityStarter: ActivityStarter,
    private val userTracker: UserTracker
) : ViewModel() {
    
    private val _dataUsageText = MutableStateFlow<String?>(null)
    val dataUsageText: StateFlow<String?> = _dataUsageText.asStateFlow()
    
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()
    
    private val _showSuffix = MutableStateFlow(false)
    val showSuffix: StateFlow<Boolean> = _showSuffix.asStateFlow()
    
    private var hasNoSims = false
    private var isWifiConnected = false
    private var wifiSsid: String? = null
    private var subId = 0
    private var currentDataSubId = 0
    private var hideDataUsage = false
    private var cycleType = 0 // 0 = Daily, 1 = Weekly


    private val tunerCallback = object : TunerService.Tunable {
        override fun onTuningChanged(key: String?, newValue: String?) {
            when (key) {
                "qs_show_data_usage" -> {
                    hideDataUsage = !TunerService.parseIntegerSwitch(newValue, true)
                    updateDataUsage()
                }
                "qs_data_usage_cycle_type" -> {
                    cycleType = TunerService.parseInteger(newValue, 0)
                    updateDataUsage()
                }

            }
        }
    }
    
    private val dataSwitchObserver = object : android.database.ContentObserver(android.os.Handler()) {
        override fun onChange(selfChange: Boolean) {
            onDefaultDataSimChanged()
        }
    }
    
    private val signalCallback = object : SignalCallback {
        override fun setNoSims(show: Boolean, simDetected: Boolean) {
            hasNoSims = show
            updateDataUsage()
        }
        
        override fun setWifiIndicators(wifiIndicators: WifiIndicators) {
            isWifiConnected = wifiIndicators.enabled && wifiIndicators.qsIcon?.visible == true
            if (isWifiConnected) {
                onWifiStatusUpdated()
            }
            updateDataUsage()
        }
    }
    
    init {
        // Listen for tuner changes
        tunerService.addTunable(tunerCallback, "qs_show_data_usage", "qs_data_usage_cycle_type")
        
        // Listen for data subscription changes
        globalSettings.registerContentObserverSync(
            "multi_sim_data_call_subscription",
            dataSwitchObserver
        )
        
        // Listen for network changes
        networkController.addCallback(signalCallback)
        
        // Set initial values
        onDefaultDataSimChanged()
        
        // Initialize WiFi status
        val wifiInfo = wifiManager?.connectionInfo
        if (wifiInfo != null && wifiInfo.networkId != -1) {
            isWifiConnected = true
            wifiSsid = wifiInfo.ssid
        }
        
        // Initialize settings from tuner
        hideDataUsage = !TunerService.parseIntegerSwitch(tunerService.getValue("qs_show_data_usage"), true)
        cycleType = TunerService.parseInteger(tunerService.getValue("qs_data_usage_cycle_type"), 0)

        
        // Set default values if they don't exist
        if (tunerService.getValue("qs_data_usage_cycle_type") == null) {
            tunerService.setValue("qs_data_usage_cycle_type", "0")
        }

        
        // Update data usage with initial settings
        updateDataUsage()
    }

    private fun onWifiStatusUpdated() {
        // Update WiFi SSID when WiFi is connected
        if (isWifiConnected) {
            val wifiInfo = wifiManager?.connectionInfo
            wifiSsid = wifiInfo?.ssid
        }
        updateDataUsage()
    }

    private fun onDefaultDataSimChanged() {
        currentDataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (currentDataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            subId = currentDataSubId
        }
        updateDataUsage()
    }

    private fun updateDataUsage() {
        if (hideDataUsage) {
            _isVisible.value = false
            return
        }

        val info = when {
            isWifiConnected -> {
                if (cycleType == 1) {
                    // Weekly WiFi data usage
                    var wifiInfo = dataController.getWifiDataUsageInfo(true)
                    if (wifiInfo == null) {
                        wifiInfo = dataController.getWifiDataUsageInfo(false)
                    }
                    wifiInfo
                } else {
                    // Daily WiFi data usage
                    var wifiInfo = dataController.getWifiDailyDataUsageInfo(true)
                    if (wifiInfo == null) {
                        wifiInfo = dataController.getWifiDailyDataUsageInfo(false)
                    }
                    wifiInfo
                }
            }
            !hasNoSims && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID -> {
                dataController.setSubscriptionId(subId)
                if (cycleType == 1) {
                    // Weekly mobile data usage
                    dataController.getDataUsageInfo()
                } else {
                    // Daily mobile data usage
                    dataController.getDailyDataUsageInfo()
                }
            }
            else -> null
        }

        if (info == null) {
            _isVisible.value = false
            return
        }

        val suffix = when {
            isWifiConnected -> {
                if (wifiSsid != null) {
                    wifiSsid!!.replace("\"", "")
                } else {
                    context.getString(R.string.usage_wifi_default_suffix)
                }
            }
            !hasNoSims -> {
                val subInfo = subManager.getActiveSubscriptionInfo(subId)
                subInfo?.displayName?.toString() ?: context.getString(R.string.usage_data_default_suffix)
            }
            else -> context.getString(R.string.usage_data_default_suffix)
        }

        val usageText = formatDataUsage(info.usageLevel, suffix)
        _dataUsageText.value = usageText
        _isVisible.value = true
    }

    private fun formatDataUsage(byteValue: Long, suffix: String): String {
        val usage = StringBuilder(Formatter.formatFileSize(context, byteValue, Formatter.FLAG_IEC_UNITS))
            .append(" ")
            .append(if (cycleType == 1) {
                context.getString(R.string.usage_data_weekly)
            } else {
                context.getString(R.string.usage_data)
            })
        
        if (_showSuffix.value == true) {
            usage.append(" (")
                .append(suffix)
                .append(")")
        }
        
        return usage.toString()
    }

    fun onDataUsageClick() {
        if (!_showSuffix.value) {
            _showSuffix.value = true
        } else if (subManager.activeSubscriptionInfoCount > 1) {
            // Switch between SIM slots - find next available subscription
            val activeSubs = subManager.activeSubscriptionInfoList
            if (activeSubs != null && activeSubs.size > 1) {
                val currentIndex = activeSubs.indexOfFirst { it.subscriptionId == subId }
                val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % activeSubs.size else 0
                subId = activeSubs[nextIndex].subscriptionId
            }
        } else {
            // Hide suffix if only one SIM
            _showSuffix.value = false
        }
        updateDataUsage()
    }

    fun onDataUsageLongClick() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName("com.android.settings", "com.android.settings.Settings\$DataUsageSummaryActivity")
        }
        activityStarter.startActivity(intent, true)
    }

    override fun onCleared() {
        super.onCleared()
        networkController.removeCallback(signalCallback)
        tunerService.removeTunable(tunerCallback)
        globalSettings.unregisterContentObserverSync(dataSwitchObserver)
    }
} 
