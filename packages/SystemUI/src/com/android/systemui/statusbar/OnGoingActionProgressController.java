/**
 * Copyright (c) 2025-2026 VoltageOS
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar;

import android.app.AlarmManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.android.systemui.Dependency;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.CaffeineController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.NotificationSuppressController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.MediaSessionManagerHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OnGoingActionProgressController
    implements NotificationListener.NotificationHandler,
        KeyguardStateController.Callback,
        OnHeadsUpChangedListener,
        FlashlightController.FlashlightListener,
        HotspotController.Callback,
        ZenModeController.Callback,
        BatteryController.BatteryStateChangeCallback,
        NextAlarmController.NextAlarmChangeCallback {
  private static final String TAG = "OngoingActionProgressController";
  private static final String ONGOING_ACTION_CHIP_ENABLED = "ongoing_action_chip";
  private static final String SHOW_MEDIA_PROGRESS = "show_media_progress";
  private static final String PROGRESS_BAR_OPACITY = "progress_bar_opacity";
  private static final String ONGOING_SMART_ACTIONS_ENABLED = "ongoing_smart_actions";
  private static final String COMPACT_MODE_ENABLED = "compact_progress_mode";
  private static final String NIRVANA_MODE_ACTIVE = "nirvana_mode_manual_active";
  private static final String SHOW_VOLTAGE_LOGO = "show_voltage_logo";
  private static final int DEFAULT_OPACITY = 255;
  private static final int DEFAULT_OPACITY_PERCENTAGE = 100;
  private static final int MEDIA_UPDATE_INTERVAL_MS = 1000;
  private static final int DEBOUNCE_DELAY_MS = 150;
  private static final int STALE_PROGRESS_CHECK_INTERVAL_MS = 5000;
  private static final long STUCK_THRESHOLD_MS = 10 * 60 * 1000;
  private static final int PROGRESS_TIMEOUT_MS = 30000;

  public interface StateCallback {
    void onStateChanged(
        boolean isVisible,
        int progress,
        int maxProgress,
        Bitmap iconBitmap,
        boolean isIconAdaptive,
        String packageName,
        boolean isCompactMode,
        float opacity,
        boolean showMediaControls,
        int activeStateType,
        int batteryLevel,
        boolean isCharging,
        boolean isPowerSave,
        int iconTint);
  }

  private static class IconResult {
    boolean isAdaptive;
    Bitmap bitmap;

    IconResult(boolean isAdaptive, Bitmap bitmap) {
      this.isAdaptive = isAdaptive;
      this.bitmap = bitmap;
    }
  }

  private final Context mContext;
  private final ContentResolver mContentResolver;
  private final Handler mHandler;
  private final SettingsObserver mSettingsObserver;
  private final KeyguardStateController mKeyguardStateController;
  private final NotificationListener mNotificationListener;
  private final HeadsUpManager mHeadsUpManager;
  private final MediaSessionManagerHelper mMediaSessionHelper;
  private final ExecutorService mBackgroundExecutor;
  private final FlashlightController mFlashlightController;
  private final HotspotController mHotspotController;
  private final ZenModeController mZenModeController;
  private final BatteryController mBatteryController;
  private final NextAlarmController mNextAlarmController;
  private final AudioManager mAudioManager;
  private final Vibrator mVibrator;
  private final BroadcastDispatcher mBroadcastDispatcher;
  private DarkIconDispatcher mDarkIconDispatcher;
  private final DarkIconDispatcher.DarkReceiver mDarkReceiver;
  private StateCallback mStateCallback = null;

  private final LruCache<String, IconResult> mIconCache = new LruCache<>(15);
  private final Map<String, List<IconCallback>> mInFlightIconLoads = new HashMap<>();
  private final ConcurrentHashMap<String, StatusBarNotification> mActiveNotificationsCache =
      new ConcurrentHashMap<>();

  private boolean mShowMediaProgress = true;
  private boolean mIsTrackingProgress = false;
  private boolean mIsForceHidden = false;
  private boolean mHeadsUpPinned = false;
  private long mLastProgressUpdateTime = 0;
  private boolean mIsEnabled;
  private boolean mSmartActionsEnabled = false;
  private boolean mIsCompactModeEnabled = false;
  private int mCurrentProgress = 0;
  private int mCurrentProgressMax = 0;
  private Bitmap mCurrentIconBitmap = null;
  private boolean mCurrentIconIsAdaptive = false;
  private int mProgressBarOpacity = DEFAULT_OPACITY;
  private boolean mIsMenuVisible = false;
  private boolean mIsSystemChipVisible = false;
  private boolean mIsStuck = false;
  private long mLastProgressChangeTime = 0;
  private long mLastStateChangeTime = 0;

  private int mIconTint = android.graphics.Color.WHITE;

  private boolean mShowVoltageLogo = false;
  private int mBatteryLevel = 100;
  private boolean mIsCharging = false;
  private boolean mIsPowerSave = false;

  private String mTrackedNotificationKey;
  private String mTrackedPackageName;
  private boolean mNeedsFullUiUpdate = true;
  private boolean mIsViewAttached = false;
  private boolean mIsExpanded = false;

  private boolean mUpdatePending = false;
  private long mLastUpdateTime = 0;

  private boolean mIs5gConnected = false;
  public static final int TYPE_NONE = 0;
  public static final int TYPE_TRANSIENT = 1;
  public static final int TYPE_FLASHLIGHT = 2;
  public static final int TYPE_HOTSPOT = 3;
  public static final int TYPE_DND = 4;
  public static final int TYPE_SAVER = 5;
  public static final int TYPE_NIRVANA = 6;
  public static final int TYPE_ALARM = 7;
  public static final int TYPE_STUCK_NOTIF = 8;
  public static final int TYPE_SILENT = 9;
  public static final int TYPE_DONE_CHECKMARK = 10;
  public static final int TYPE_LOGO = 11;
  public static final int TYPE_CAFFEINE = 12;
  public static final int TYPE_NOTIF_SUPPRESS = 13;
  public static final int TYPE_FIVEG = 14;

  private int mCurrentDisplayState = TYPE_NONE;
  private final ArrayList<Integer> mActiveStatesHistory = new ArrayList<>();

  private boolean mHasTransient = false;
  private boolean mIsTransientGracePending = false;

  private long mFinishAnimationEndTime = 0;

  private boolean mIsLoadingDefaultMediaIcon = false;

  private long mLastTransientTime = 0;
  private final Runnable mTransientBufferRunnable = this::requestUiUpdate;
  private final Runnable mFinishAnimRunnable = this::requestUiUpdate;

  private int mOverrideState = TYPE_NONE;
  private long mOverrideEndTime = 0;
  private final Runnable mClearOverrideRunnable = () -> {
      mOverrideState = TYPE_NONE;
      requestUiUpdate();
  };

  private TelephonyManager mTelephonyManager;
  private NetworkTypeListener mNetworkTypeListener;
  private CaffeineController mCaffeineController;
  private NotificationSuppressController mNotifSuppressController;

  private CaffeineController.CaffeineStateListener mCaffeineListener;
  private NotificationSuppressController.StateListener mNotifSuppressListener;

  private final BroadcastReceiver mRingerReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          updateStateHistory(
              TYPE_SILENT,
              mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_SILENT);
        }
      };

  private final BroadcastReceiver mConfigurationReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
            mDefaultMediaBitmapFull = null;
            mDefaultMediaBitmapCompact = null;
          }
        }
      };

  private final Runnable mTransientGraceRunnable =
      () -> {
        mIsTransientGracePending = false;
        requestUiUpdate();
      };

  private AlarmManager.AlarmClockInfo mNextAlarm;
  private final Runnable mAlarmCheckRunnable = () -> checkAlarmState();

  private void checkAlarmState() {
    mHandler.removeCallbacks(mAlarmCheckRunnable);
    if (mNextAlarm != null) {
      long timeToAlarm = mNextAlarm.getTriggerTime() - System.currentTimeMillis();
      if (timeToAlarm > 0 && timeToAlarm <= 5 * 60 * 1000) {
        updateStateHistory(TYPE_ALARM, true);
        mHandler.postDelayed(mAlarmCheckRunnable, timeToAlarm + 1000);
      } else if (timeToAlarm > 5 * 60 * 1000) {
        updateStateHistory(TYPE_ALARM, false);
        mHandler.postDelayed(mAlarmCheckRunnable, timeToAlarm - 5 * 60 * 1000);
      } else {
        updateStateHistory(TYPE_ALARM, false);
      }
    } else {
      updateStateHistory(TYPE_ALARM, false);
    }
  }

  private final BroadcastReceiver mSimStateReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          update5gState();
        }
      };

  private void update5gState() {
    if (mTelephonyManager == null) return;
    try {
      boolean hasSim = mTelephonyManager.getSimState() != TelephonyManager.SIM_STATE_ABSENT;
      long supported = mTelephonyManager.getSupportedRadioAccessFamily();
      boolean hardwareSupports5g = (supported & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
      long allowed = mTelephonyManager.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
      boolean userAllows5g = (allowed & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;

      updateStateHistory(TYPE_FIVEG, hasSim && hardwareSupports5g && userAllows5g && mIs5gConnected);
    } catch (Exception e) {
      Log.w(TAG, "Failed to update 5G state", e);
      updateStateHistory(TYPE_FIVEG, false);
    }
  }

  private class NetworkTypeListener extends TelephonyCallback
      implements TelephonyCallback.AllowedNetworkTypesListener,
                 TelephonyCallback.DisplayInfoListener {
    @Override
    public void onAllowedNetworkTypesChanged(int reason, long allowedNetworkType) {
      if (reason == TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER) {
        update5gState();
      }
    }

    @Override
    public void onDisplayInfoChanged(TelephonyDisplayInfo displayInfo) {
      int networkType = displayInfo.getNetworkType();
      int overrideType = displayInfo.getOverrideNetworkType();

      mIs5gConnected = (networkType == TelephonyManager.NETWORK_TYPE_NR) ||
                       (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA) ||
                       (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED);
      update5gState();
    }

  }

  private final Runnable mMediaProgressRunnable =
      new Runnable() {
        @Override
        public void run() {
          if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying() && !mIsForceHidden) {
            updateMediaProgressOnly();
            mHandler.postDelayed(this, MEDIA_UPDATE_INTERVAL_MS);
          }
        }
      };

  private final Runnable mStaleProgressChecker =
      new Runnable() {
        @Override
        public void run() {
          checkForStaleProgress();
          if (mIsViewAttached && mIsTrackingProgress) {
            mHandler.postDelayed(this, STALE_PROGRESS_CHECK_INTERVAL_MS);
          }
        }
      };

  private final Runnable mCompactCollapseRunnable =
      () -> {
        if (mIsCompactModeEnabled && mIsExpanded) {
          mIsExpanded = false;
          requestUiUpdate();
        }
      };

  private final Runnable mMenuCollapseRunnable =
      () -> {
        mIsMenuVisible = false;
        notifyStateCallback();
      };

  private final MediaSessionManagerHelper.MediaMetadataListener mMediaMetadataListener =
      new MediaSessionManagerHelper.MediaMetadataListener() {
        @Override
        public void onMediaMetadataChanged() {
          mNeedsFullUiUpdate = true;
          requestUiUpdate();
        }

        @Override
        public void onPlaybackStateChanged() {
          mNeedsFullUiUpdate = true;
          requestUiUpdate();
        }
      };

  public OnGoingActionProgressController(
      Context context,
      NotificationListener notificationListener,
      KeyguardStateController keyguardStateController,
      HeadsUpManager headsUpManager,
      FlashlightController flashlightController,
      HotspotController hotspotController,
      ZenModeController zenModeController,
      BatteryController batteryController,
      NextAlarmController nextAlarmController,
      BroadcastDispatcher broadcastDispatcher,
      CaffeineController caffeineController,
      NotificationSuppressController notifSuppressController) {

    mDarkReceiver =
        (areas, darkIntensity, tint) -> {
          if (mIconTint != tint) {
            mIconTint = tint;
            notifyStateCallback();
          }
        };

    mNotificationListener = notificationListener;
    if (mNotificationListener == null) {
      Log.wtf(TAG, "mNotificationListener is null");
      throw new IllegalArgumentException("notificationListener cannot be null");
    }

    mKeyguardStateController = keyguardStateController;
    mHeadsUpManager = headsUpManager;
    mContext = context;
    mContentResolver = context.getContentResolver();
    mHandler = new Handler(Looper.getMainLooper());
    mSettingsObserver = new SettingsObserver(mHandler);
    mBackgroundExecutor = Executors.newSingleThreadExecutor();
    mBroadcastDispatcher = broadcastDispatcher;

    mDarkIconDispatcher = Dependency.get(DarkIconDispatcher.class);
    if (mDarkIconDispatcher != null) {
      mDarkIconDispatcher.addDarkReceiver(mDarkReceiver);
    }

    mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    mVibrator = mContext.getSystemService(Vibrator.class);

    mFlashlightController = flashlightController;
    if (mFlashlightController != null) {
      mFlashlightController.addCallback(this);
      updateStateHistory(TYPE_FLASHLIGHT, mFlashlightController.isEnabled());
    }

    mHotspotController = hotspotController;
    if (mHotspotController != null) {
      mHotspotController.addCallback(this);
      updateStateHistory(TYPE_HOTSPOT, mHotspotController.isHotspotEnabled());
    }

    mZenModeController = zenModeController;
    if (mZenModeController != null) {
      mZenModeController.addCallback(this);
      updateStateHistory(TYPE_DND, mZenModeController.getZen() != Settings.Global.ZEN_MODE_OFF);
    }

    mBatteryController = batteryController;
    if (mBatteryController != null) {
      mBatteryController.addCallback(this);
      updateStateHistory(TYPE_SAVER, mBatteryController.isPowerSave());
    }

    mNextAlarmController = nextAlarmController;
    if (mNextAlarmController != null) {
      mNextAlarmController.addCallback(this);
    }

    try {
      mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
      if (mTelephonyManager != null) {
        mNetworkTypeListener = new NetworkTypeListener();
        mTelephonyManager.registerTelephonyCallback(
            mContext.getMainExecutor(), mNetworkTypeListener);
        mBroadcastDispatcher.registerReceiver(
            mSimStateReceiver, new IntentFilter("android.intent.action.SIM_STATE_CHANGED"));
        update5gState();
      }
    } catch (Exception e) {
      Log.e(TAG, "Failed to register 5G TelephonyCallback", e);
    }

    mCaffeineController = caffeineController;
    if (mCaffeineController != null) {
      mCaffeineListener =
          new CaffeineController.CaffeineStateListener() {
            @Override
            public void onCaffeineStateChanged(boolean active, String label) {
              updateStateHistory(TYPE_CAFFEINE, active);
            }
          };
      mCaffeineController.addListener(mCaffeineListener);
      updateStateHistory(TYPE_CAFFEINE, mCaffeineController.isActive());
    }

    mNotifSuppressController = notifSuppressController;
    if (mNotifSuppressController != null) {
      mNotifSuppressListener =
          new NotificationSuppressController.StateListener() {
            @Override
            public void onStateChanged(boolean suppressed, String label) {
              updateStateHistory(TYPE_NOTIF_SUPPRESS, suppressed);
            }
          };
      mNotifSuppressController.addListener(mNotifSuppressListener);
      updateStateHistory(TYPE_NOTIF_SUPPRESS, mNotifSuppressController.isSuppressed());
    }

    mBroadcastDispatcher.registerReceiver(
        mRingerReceiver, new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION));
    mBroadcastDispatcher.registerReceiver(
        mConfigurationReceiver, new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));

    mMediaSessionHelper = MediaSessionManagerHelper.Companion.getInstance(context);

    mKeyguardStateController.addCallback(this);
    mHeadsUpManager.addListener(this);
    mNotificationListener.addNotificationHandler(this);
    mSettingsObserver.register();

    mMediaSessionHelper.addMediaMetadataListener(mMediaMetadataListener);

    mIsViewAttached = true;
    updateSettings();

    mHandler.removeCallbacks(mStaleProgressChecker);
    if (mIsTrackingProgress) {
      mHandler.postDelayed(mStaleProgressChecker, STALE_PROGRESS_CHECK_INTERVAL_MS);
    }

    mBackgroundExecutor.execute(
        () -> {
          try {
            if (mNotificationListener == null) return;
            StatusBarNotification[] sbns = mNotificationListener.getActiveNotifications();
            if (sbns != null) {
              for (StatusBarNotification sbn : sbns) {
                if (sbn != null) {
                  mActiveNotificationsCache.put(sbn.getKey(), sbn);
                  mActiveNotificationsCache.putIfAbsent(sbn.getKey(), sbn);
                }
              }
            }
          } catch (Exception e) {
            Log.e(TAG, "Failed to initialize notification cache", e);
          }
        });
  }

  private void triggerHaptic(int effectId) {
    if (mVibrator != null && mVibrator.hasVibrator()) {
      mVibrator.vibrate(VibrationEffect.createPredefined(effectId));
    }
  }

  public void setStateCallback(StateCallback callback) {
    mStateCallback = callback;
    notifyStateCallback();
  }

  public void expandCompactView() {
    mIsExpanded = true;

    mHandler.removeCallbacks(mCompactCollapseRunnable);
    mHandler.postDelayed(mCompactCollapseRunnable, 5000);

    notifyStateCallback();
  }

  private boolean isHardware(int type) {
      return type == TYPE_FLASHLIGHT;
  }

  private int getPriority(int type) {
      switch(type) {
          case TYPE_FLASHLIGHT: return 1;
          case TYPE_HOTSPOT: return 2;
          case TYPE_NIRVANA: return 3;
          case TYPE_DND: return 4;
          case TYPE_SILENT: return 5;
          case TYPE_SAVER: return 6;
          case TYPE_CAFFEINE: return 7;
          case TYPE_NOTIF_SUPPRESS: return 8;
          case TYPE_FIVEG: return 9;
          case TYPE_ALARM: return 10;
          case TYPE_STUCK_NOTIF: return 11;
          default: return 99;
      }
  }

  private void updateStateHistory(int type, boolean active) {
      boolean changed = false;
      if (active) {
          if (!mActiveStatesHistory.contains(type)) {
              mActiveStatesHistory.add(type);
              changed = true;
          }
          mOverrideState = type;
          long duration = isHardware(type) ? 2500 : 4500;
          mOverrideEndTime = System.currentTimeMillis() + duration;
          mHandler.removeCallbacks(mClearOverrideRunnable);
          mHandler.postDelayed(mClearOverrideRunnable, duration);
      } else {
          if (mActiveStatesHistory.contains((Integer) type)) {
              mActiveStatesHistory.remove((Integer) type);
              changed = true;
          }
          if (mOverrideState == type) {
              mOverrideState = TYPE_NONE;
              mHandler.removeCallbacks(mClearOverrideRunnable);
          }
      }

      if (changed) {
          mActiveStatesHistory.sort((a, b) -> Integer.compare(getPriority(a), getPriority(b)));
          mLastStateChangeTime = System.currentTimeMillis();
          requestUiUpdate();
      } else if (active) {
          requestUiUpdate();
      }
  }

  @Override
  public void onFlashlightChanged(boolean enabled) {
    updateStateHistory(TYPE_FLASHLIGHT, enabled);
  }

  @Override
  public void onFlashlightError() {
    updateStateHistory(TYPE_FLASHLIGHT, false);
  }

  @Override
  public void onFlashlightAvailabilityChanged(boolean available) {
    if (!available) updateStateHistory(TYPE_FLASHLIGHT, false);
  }

  @Override
  public void onFlashlightStrengthChanged(int level) {}

  @Override
  public void onHotspotChanged(boolean enabled, int numDevices) {
    updateStateHistory(TYPE_HOTSPOT, enabled);
  }

  @Override
  public void onZenChanged(int zen) {
    updateStateHistory(TYPE_DND, zen != Settings.Global.ZEN_MODE_OFF);
  }

  @Override
  public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
    mBatteryLevel = level;
    mIsCharging = charging;
    notifyStateCallback();
  }

  @Override
  public void onPowerSaveChanged(boolean isPowerSave) {
    updateStateHistory(TYPE_SAVER, isPowerSave);
  }

  @Override
  public void onNextAlarmChanged(AlarmManager.AlarmClockInfo nextAlarm) {
    mNextAlarm = nextAlarm;
    checkAlarmState();
  }

  private void requestUiUpdate() {
    long currentTime = System.currentTimeMillis();
    if (!mUpdatePending && (currentTime - mLastUpdateTime > DEBOUNCE_DELAY_MS)) {
      mUpdatePending = false;
      mLastUpdateTime = currentTime;
      updateViews();
    } else if (!mUpdatePending) {
      mUpdatePending = true;
      mHandler.postDelayed(
          () -> {
            mUpdatePending = false;
            mLastUpdateTime = System.currentTimeMillis();
            updateViews();
          },
          DEBOUNCE_DELAY_MS);
    }
  }

  private void notifyStateCallback() {
    if (mStateCallback == null) {
      return;
    }

    boolean isVisible =
        !mIsForceHidden
            && !mHeadsUpPinned
            && !mIsSystemChipVisible
            && mCurrentDisplayState != TYPE_NONE;

    boolean isSmartAction = mCurrentDisplayState != TYPE_TRANSIENT
        && mCurrentDisplayState != TYPE_DONE_CHECKMARK
        && mCurrentDisplayState != TYPE_LOGO
        && mCurrentDisplayState != TYPE_NONE;

    if (isSmartAction && !mSmartActionsEnabled) {
      isVisible = false;
    }

    if (isVisible) {
      float opacity = mProgressBarOpacity / 255f;
      boolean isCompact = mIsCompactModeEnabled && !mIsExpanded;
      mStateCallback.onStateChanged(
          true,
          mCurrentProgress,
          mCurrentProgressMax,
          mCurrentIconBitmap,
          mCurrentIconIsAdaptive,
          mTrackedPackageName,
          isCompact,
          opacity,
          mIsMenuVisible,
          mCurrentDisplayState,
          mBatteryLevel,
          mIsCharging,
          mIsPowerSave,
          mIconTint);
    } else {
      mStateCallback.onStateChanged(
          false,
          0,
          0,
          null,
          false,
          null,
          false,
          0f,
          false,
          TYPE_NONE,
          100,
          false,
          false,
          android.graphics.Color.WHITE);
    }
  }

  private void updateViews() {
    if (!mIsViewAttached) {
      notifyStateCallback();
      return;
    }

    boolean isDownloadNow = mIsEnabled && mIsTrackingProgress && !mIsStuck;
    boolean isMediaNow = mShowMediaProgress && mMediaSessionHelper.isMediaPlaying();
    long now = System.currentTimeMillis();

    boolean isTransientNow = isDownloadNow || isMediaNow;
    if (isTransientNow) {
      mLastTransientTime = now;
    }

    boolean isTransientBuffered = isTransientNow || (now - mLastTransientTime < 2000);

    if (!isTransientNow && isTransientBuffered) {
      mHandler.removeCallbacks(mTransientBufferRunnable);
      mHandler.postDelayed(mTransientBufferRunnable, 2000 - (now - mLastTransientTime) + 50);
    }

    if (isTransientBuffered && !mHasTransient) {
      mHasTransient = true;
      mIsTransientGracePending = true;
      mHandler.postDelayed(mTransientGraceRunnable, 300);
    } else if (!isTransientBuffered && mHasTransient) {
      mHasTransient = false;
      mIsTransientGracePending = false;
      mHandler.removeCallbacks(mTransientGraceRunnable);
    }

    boolean isOverrideActive = mOverrideState != TYPE_NONE && mActiveStatesHistory.contains(mOverrideState);
    List<Integer> hardwareStates = new ArrayList<>();
    for (int state : mActiveStatesHistory) {
        if (isHardware(state)) hardwareStates.add(state);
    }

    if (isDownloadNow) {
      mCurrentDisplayState = TYPE_TRANSIENT;
    } else if (isOverrideActive && now < mOverrideEndTime) {
        mCurrentDisplayState = mOverrideState;
        mHandler.removeCallbacks(mClearOverrideRunnable);
        mHandler.postDelayed(mClearOverrideRunnable, mOverrideEndTime - now + 50);
    } else if (!hardwareStates.isEmpty()) {
        mCurrentDisplayState = hardwareStates.get(0);
    } else if (isMediaNow) {
        mCurrentDisplayState = TYPE_TRANSIENT;
    } else if (now < mFinishAnimationEndTime) {
        mCurrentDisplayState = TYPE_DONE_CHECKMARK;
        mHandler.removeCallbacks(mFinishAnimRunnable);
        mHandler.postDelayed(mFinishAnimRunnable, mFinishAnimationEndTime - now + 50);
    } else if (isTransientBuffered && !mIsTransientGracePending) {
        mCurrentDisplayState = TYPE_TRANSIENT;
    } else if (!mActiveStatesHistory.isEmpty()) {
        mCurrentDisplayState = mActiveStatesHistory.get(0);
    } else {
        mCurrentDisplayState = mShowVoltageLogo ? TYPE_LOGO : TYPE_NONE;
    }

    if (mIsForceHidden || mHeadsUpPinned || mCurrentDisplayState == TYPE_NONE) {
      notifyStateCallback();
      return;
    }

    if (mCurrentDisplayState != TYPE_TRANSIENT && mCurrentDisplayState != TYPE_DONE_CHECKMARK) {
      notifyStateCallback();
      return;
    }

    if (mIsCompactModeEnabled && !mIsExpanded) {
      if (!mIsEnabled && !isMediaNow) {
        mHandler.removeCallbacks(mMediaProgressRunnable);
        notifyStateCallback();
        return;
      }

      if (isMediaNow) {
        updateMediaProgressCompact();
      } else {
        updateNotificationProgressCompact();
      }
    } else {
      if (isMediaNow) {
        if (mNeedsFullUiUpdate) {
          updateMediaProgressFull();
          mNeedsFullUiUpdate = false;
        } else {
          updateMediaProgressOnly();
        }
      } else {
        mHandler.removeCallbacks(mMediaProgressRunnable);
        updateNotificationProgress();
      }
    }
    notifyStateCallback();
  }

  private void updateMediaProgressOnly() {
    if (!mIsViewAttached) return;

    long totalDuration = mMediaSessionHelper.getTotalDuration();

    android.media.session.PlaybackState playbackState =
        mMediaSessionHelper.getMediaControllerPlaybackState();
    long currentProgress = 0;

    if (playbackState != null) {
      long reportedPosition = playbackState.getPosition();
      long lastUpdateTime = playbackState.getLastPositionUpdateTime();
      float speed = playbackState.getPlaybackSpeed();
      if (lastUpdateTime > 0 && speed > 0f) {
        long elapsed = SystemClock.elapsedRealtime() - lastUpdateTime;
        currentProgress = reportedPosition + (long) (elapsed * speed);
      } else {
        currentProgress = reportedPosition;
      }
      if (totalDuration > 0) {
        currentProgress = Math.min(currentProgress, totalDuration);
      }
    }

    mCurrentProgress = (int) currentProgress;
    mCurrentProgressMax = (int) totalDuration;
    if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

    notifyStateCallback();
  }

  private void updateMediaProgressFull() {
    if (!mIsViewAttached) return;

    mHandler.removeCallbacks(mMediaProgressRunnable);
    if (!mIsForceHidden) {
      mHandler.post(mMediaProgressRunnable);
    }

    long totalDuration = mMediaSessionHelper.getTotalDuration();
    android.media.session.PlaybackState earlyPs =
        mMediaSessionHelper.getMediaControllerPlaybackState();
    if (earlyPs != null) {
      long reportedPosition = earlyPs.getPosition();
      long lastUpdateTime = earlyPs.getLastPositionUpdateTime();
      float speed = earlyPs.getPlaybackSpeed();
      if (lastUpdateTime > 0 && speed > 0f) {
        long elapsed = SystemClock.elapsedRealtime() - lastUpdateTime;
        long extrapolated = reportedPosition + (long) (elapsed * speed);
        mCurrentProgress =
            (int) (totalDuration > 0 ? Math.min(extrapolated, totalDuration) : extrapolated);
      } else {
        mCurrentProgress = (int) reportedPosition;
      }
    } else {
      mCurrentProgress = 0;
    }
    mCurrentProgressMax = (int) totalDuration;
    if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

    Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();

    if (mediaAppIcon != null) {
      boolean isAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
      final int sizePx = drawableSizePx();
      mBackgroundExecutor.execute(
          () -> {
            Bitmap bmp = drawableToBitmap(mediaAppIcon, sizePx);
            mHandler.post(
                () -> {
                  mCurrentIconBitmap = bmp;
                  mCurrentIconIsAdaptive = isAdaptive;
                  updateMediaProgressOnly();
                });
          });
      return;
    }

    String packageName = null;
    android.media.session.PlaybackState playbackState =
        mMediaSessionHelper.getMediaControllerPlaybackState();
    if (playbackState != null && playbackState.getExtras() != null) {
      packageName = playbackState.getExtras().getString("package");
    }

    if (packageName != null) {
      loadIconInBackground(
          packageName,
          result -> {
            if (result != null && result.bitmap != null) {
              mCurrentIconBitmap = result.bitmap;
              mCurrentIconIsAdaptive = result.isAdaptive;
            } else {
              setDefaultMediaIcon();
            }
            notifyStateCallback();
          });
    } else {
      setDefaultMediaIcon();
    }

    updateMediaProgressOnly();
  }

  private Bitmap mDefaultMediaBitmapFull = null;
  private Bitmap mDefaultMediaBitmapCompact = null;

  private void setDefaultMediaIcon() {
    boolean isCompact = mIsCompactModeEnabled && !mIsExpanded;
    Bitmap cached = isCompact ? mDefaultMediaBitmapCompact : mDefaultMediaBitmapFull;
    if (cached == null) {
      if (mIsLoadingDefaultMediaIcon) return;
      mIsLoadingDefaultMediaIcon = true;
      final int sizePx = drawableSizePx();
      mBackgroundExecutor.execute(
          () -> {
            Drawable d =
                mContext
                    .getResources()
                    .getDrawable(R.drawable.ic_default_music_icon, mContext.getTheme());
            Bitmap bmp = drawableToBitmap(d, sizePx);
            mHandler.post(
                () -> {
                  mIsLoadingDefaultMediaIcon = false;
                  if (isCompact) mDefaultMediaBitmapCompact = bmp;
                  else mDefaultMediaBitmapFull = bmp;
                  applyDefaultMediaIcon();
                });
          });
    } else {
      applyDefaultMediaIcon();
    }
  }

  private void applyDefaultMediaIcon() {
    mCurrentIconBitmap =
        (mIsCompactModeEnabled && !mIsExpanded)
            ? mDefaultMediaBitmapCompact
            : mDefaultMediaBitmapFull;
    mCurrentIconIsAdaptive = false;
    notifyStateCallback();
  }

  private void updateMediaProgressCompact() {
    if (!mIsViewAttached) return;

    mHandler.removeCallbacks(mMediaProgressRunnable);
    if (!mIsForceHidden) {
      mHandler.post(mMediaProgressRunnable);
    }

    long totalDuration = mMediaSessionHelper.getTotalDuration();

    android.media.session.PlaybackState playbackState =
        mMediaSessionHelper.getMediaControllerPlaybackState();
    long currentProgress = 0;

    if (playbackState != null) {
      long reportedPosition = playbackState.getPosition();
      long lastUpdateTime = playbackState.getLastPositionUpdateTime();
      float speed = playbackState.getPlaybackSpeed();
      if (lastUpdateTime > 0 && speed > 0f) {
        long elapsed = SystemClock.elapsedRealtime() - lastUpdateTime;
        currentProgress = reportedPosition + (long) (elapsed * speed);
      } else {
        currentProgress = reportedPosition;
      }
      if (totalDuration > 0) {
        currentProgress = Math.min(currentProgress, totalDuration);
      }
    }

    mCurrentProgress = (int) currentProgress;
    mCurrentProgressMax = (int) totalDuration;
    if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

    Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();

    if (mediaAppIcon != null) {
      boolean isAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
      final int sizePx = drawableSizePx();
      mBackgroundExecutor.execute(
          () -> {
            Bitmap bmp = drawableToBitmap(mediaAppIcon, sizePx);
            mHandler.post(
                () -> {
                  mCurrentIconBitmap = bmp;
                  mCurrentIconIsAdaptive = isAdaptive;
                  notifyStateCallback();
                });
          });
      return;
    }

    String packageName = null;
    if (playbackState != null && playbackState.getExtras() != null) {
      packageName = playbackState.getExtras().getString("package");
    }

    if (packageName != null) {
      loadIconInBackground(
          packageName,
          result -> {
            if (result != null && result.bitmap != null) {
              mCurrentIconBitmap = result.bitmap;
              mCurrentIconIsAdaptive = result.isAdaptive;
            } else {
              setDefaultMediaIcon();
            }
            notifyStateCallback();
          });
    } else {
      setDefaultMediaIcon();
    }
  }

  private void updateNotificationProgress() {
    if (!mIsViewAttached) return;

    if (!mIsEnabled || !mIsTrackingProgress) {
      mHandler.removeCallbacks(mMediaProgressRunnable);
      return;
    }

    if (mCurrentProgressMax <= 0) {
      Log.w(TAG, "updateViews: invalid max progress " + mCurrentProgressMax + ", using 100");
      mCurrentProgressMax = 100;
    }

    if (mTrackedPackageName != null) {
      loadIconInBackground(
          mTrackedPackageName,
          result -> {
            if (result != null && result.bitmap != null) {
              mCurrentIconBitmap = result.bitmap;
              mCurrentIconIsAdaptive = result.isAdaptive;
            }
            notifyStateCallback();
          });
    }
  }

  private void updateNotificationProgressCompact() {
    updateNotificationProgress();
  }

  private int drawableSizePx() {
    int dp = (mIsCompactModeEnabled && !mIsExpanded) ? 18 : 20;
    return (int) (dp * mContext.getResources().getDisplayMetrics().density);
  }

  private Bitmap drawableToBitmap(Drawable drawable, int sizePx) {
    if (drawable == null || sizePx <= 0) return null;
    Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    drawable.setBounds(0, 0, sizePx, sizePx);
    drawable.draw(canvas);
    return bitmap;
  }

  private IconResult fetchPackageIcon(String packageName, int sizePx) {
    try {
      PackageManager packageManager = mContext.getPackageManager();
      Drawable icon = packageManager.getApplicationIcon(packageName);
      return new IconResult(icon instanceof AdaptiveIconDrawable, drawableToBitmap(icon, sizePx));
    } catch (PackageManager.NameNotFoundException e) {
      Log.w(TAG, "Failed to load icon for " + packageName, e);
      Drawable defaultIcon = mContext.getDrawable(android.R.drawable.sym_def_app_icon);
      return new IconResult(false, drawableToBitmap(defaultIcon, sizePx));
    }
  }

  private void loadIconInBackground(String packageName, IconCallback callback) {
    if (packageName == null) return;

    IconResult cachedResult = mIconCache.get(packageName);
    if (cachedResult != null) {
      callback.onIconLoaded(cachedResult);
      return;
    }

    if (mInFlightIconLoads.containsKey(packageName)) {
      mInFlightIconLoads.get(packageName).add(callback);
      return;
    }
    List<IconCallback> callbacks = new ArrayList<>();
    callbacks.add(callback);
    mInFlightIconLoads.put(packageName, callbacks);

    final int sizePx = drawableSizePx();
    mBackgroundExecutor.execute(
        () -> {
          IconResult fetchedResult = null;
          try {
            fetchedResult = fetchPackageIcon(packageName, sizePx);
          } catch (Exception e) {
            Log.e(TAG, "Failed to load icon in background for " + packageName, e);
          } finally {
            final IconResult finalResult = fetchedResult;

            mHandler.post(
                () -> {
                  if (finalResult != null && finalResult.bitmap != null) {
                    mIconCache.put(packageName, finalResult);
                  }

                  List<IconCallback> cbs = mInFlightIconLoads.remove(packageName);
                  if (cbs != null) {
                    IconResult resultToPass =
                        (finalResult != null && finalResult.bitmap != null) ? finalResult : null;
                    for (IconCallback cb : cbs) {
                      cb.onIconLoaded(resultToPass);
                    }
                  }
                });
          }
        });
  }

  private interface IconCallback {
    void onIconLoaded(@Nullable IconResult result);
  }

  private void extractProgress(Notification notification) {
    Bundle extras = notification.extras;
    int newProgress = extras.getInt(Notification.EXTRA_PROGRESS, 0);
    int newMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100);
    if (newProgress != mCurrentProgress || newMax != mCurrentProgressMax) {
      mLastProgressChangeTime = System.currentTimeMillis();
      if (mIsStuck) {
        mIsStuck = false;
        updateStateHistory(TYPE_STUCK_NOTIF, false);
      }
    }

    mCurrentProgressMax = newMax;
    mCurrentProgress = newProgress;
  }

  private void trackProgress(final StatusBarNotification sbn) {
    mIsTrackingProgress = true;
    mHandler.removeCallbacks(mStaleProgressChecker);
    mHandler.postDelayed(mStaleProgressChecker, STALE_PROGRESS_CHECK_INTERVAL_MS);
    mTrackedNotificationKey = sbn.getKey();
    mTrackedPackageName = sbn.getPackageName();
    mLastProgressChangeTime = System.currentTimeMillis();
    mIsStuck = false;
    extractProgress(sbn.getNotification());
    requestUiUpdate();
  }

  private void clearProgressTracking(boolean showSuccess) {
    mIsTrackingProgress = false;
    mTrackedNotificationKey = null;
    mTrackedPackageName = null;

    mLastProgressUpdateTime = 0;
    mIsStuck = false;
    updateStateHistory(TYPE_STUCK_NOTIF, false);

    if (showSuccess && mCurrentDisplayState == TYPE_TRANSIENT) {
      mFinishAnimationEndTime = System.currentTimeMillis() + 800;
      mLastTransientTime = 0;
    }
    requestUiUpdate();
  }

  private void checkForStaleProgress() {
    if (!mIsTrackingProgress || mTrackedNotificationKey == null) return;

    StatusBarNotification sbn = mActiveNotificationsCache.get(mTrackedNotificationKey);
    if (sbn == null) {
      clearProgressTracking(false);
      return;
    }

    if (!hasProgress(sbn.getNotification())) {
      clearProgressTracking(false);
      return;
    }

    if (System.currentTimeMillis() - mLastProgressUpdateTime > PROGRESS_TIMEOUT_MS) {
      clearProgressTracking(false);
    }

    if (System.currentTimeMillis() - mLastProgressChangeTime > STUCK_THRESHOLD_MS) {
      if (!mIsStuck) {
        mIsStuck = true;
        updateStateHistory(TYPE_STUCK_NOTIF, true);
      }
    }
  }

  private void updateProgressIfNeeded(final StatusBarNotification sbn) {
    if (!mIsTrackingProgress) return;

    if (sbn.getKey().equals(mTrackedNotificationKey)) {
      if (!hasProgress(sbn.getNotification())) {
        clearProgressTracking(false);
        return;
      }

      mLastProgressUpdateTime = System.currentTimeMillis();
      extractProgress(sbn.getNotification());
      requestUiUpdate();
    }
  }

  private static boolean hasProgress(@NonNull final Notification notification) {
    Bundle extras = notification.extras;
    if (extras == null) return false;

    boolean indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false);
    boolean maxProgressValid = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0;
    return extras.containsKey(Notification.EXTRA_PROGRESS)
        && extras.containsKey(Notification.EXTRA_PROGRESS_MAX)
        && !indeterminate
        && maxProgressValid;
  }

  private void cancelTrackedTask() {
    if (mTrackedNotificationKey != null && mNotificationListener != null) {
      try {
        for (StatusBarNotification sbn : mActiveNotificationsCache.values()) {
          if (sbn.getKey().equals(mTrackedNotificationKey)) {
            Notification n = sbn.getNotification();
            if (n.actions != null) {
              for (Notification.Action action : n.actions) {
                String title = String.valueOf(action.title).toLowerCase();
                if (title.contains("cancel") || title.contains("stop")) {
                  try {
                    action.actionIntent.send();
                    clearProgressTracking(true);
                    return;
                  } catch (Exception e) {
                  }
                }
              }
            }

            if (mNotificationListener instanceof NotificationListenerService) {
              ((NotificationListenerService) mNotificationListener)
                  .cancelNotification(mTrackedNotificationKey);
              clearProgressTracking(true);
              return;
            }
          }
        }
      } catch (Exception e) {
        Log.e(TAG, "Failed to cancel tracked task", e);
      }
    }
    clearProgressTracking(true);
  }

  private void cancelTrackedNotification() {
    try {
      if (mNotificationListener instanceof NotificationListenerService
          && mTrackedNotificationKey != null) {
        ((NotificationListenerService) mNotificationListener)
            .cancelNotification(mTrackedNotificationKey);
      }
    } catch (Exception e) {
      Log.e(TAG, "Failed to cancel notification", e);
    }
  }

  private void openPowerHub() {
    Intent intent = new Intent(Intent.ACTION_MAIN);
    intent.setComponent(
        new ComponentName("com.android.settings", "com.android.settings.SubSettings"));
    intent.putExtra(":settings:show_fragment", "com.power.hub.powerhub");
    intent.putExtra(":settings:show_fragment_title", "PowerHub");
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    mContext.startActivity(intent);
  }

  private void launchSettings(String action) {
    try {
      Intent intent = new Intent(action);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      mContext.startActivity(intent);
    } catch (Exception e) {
      try {
        Intent fallback = new Intent(Settings.ACTION_SETTINGS);
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivity(fallback);
      } catch (Exception ex) {
        Log.e(TAG, "Failed to launch settings fallback", ex);
      }
    }
  }

  private void dismissAlarm() {
    boolean dismissed = false;
    if (mNextAlarm != null && mNextAlarm.getShowIntent() != null) {
      String alarmPackage = mNextAlarm.getShowIntent().getCreatorPackage();
      if (mNotificationListener != null && alarmPackage != null) {
        for (StatusBarNotification sbn : mActiveNotificationsCache.values()) {
          if (alarmPackage.equals(sbn.getPackageName())) {
            Notification n = sbn.getNotification();
            if (n.actions != null) {
              for (Notification.Action action : n.actions) {
                String title = String.valueOf(action.title).toLowerCase();
                if (title.contains("dismiss")
                    || title.contains("cancel")
                    || title.contains("turn off")) {
                  try {
                    action.actionIntent.send();
                    dismissed = true;
                    break;
                  } catch (Exception e) {
                  }
                }
              }
            }
          }
          if (dismissed) break;
        }
      }
      if (!dismissed) {
        try {
          mNextAlarm.getShowIntent().send();
        } catch (Exception e) {
        }
      }
    }
    mNextAlarm = null;
    updateStateHistory(TYPE_ALARM, false);
    mFinishAnimationEndTime = System.currentTimeMillis() + 800;
    requestUiUpdate();
  }

  public void onInteraction() {
    if (mCurrentDisplayState != TYPE_TRANSIENT && mCurrentDisplayState != TYPE_NONE) {
      if (System.currentTimeMillis() - mLastStateChangeTime < 500) {
        return;
      }
    }

    switch (mCurrentDisplayState) {
      case TYPE_FLASHLIGHT:
        if (mFlashlightController != null) mFlashlightController.setFlashlight(false);
        break;
      case TYPE_HOTSPOT:
        if (mHotspotController != null) mHotspotController.setHotspotEnabled(false);
        break;
      case TYPE_DND:
        if (mZenModeController != null)
          mZenModeController.setZen(Settings.Global.ZEN_MODE_OFF, null, TAG);
        break;
      case TYPE_SAVER:
        if (mBatteryController != null) mBatteryController.setPowerSaveMode(false);
        break;
      case TYPE_NIRVANA:
        Settings.Secure.putInt(mContext.getContentResolver(), NIRVANA_MODE_ACTIVE, 0);
        break;
      case TYPE_ALARM:
        dismissAlarm();
        break;
      case TYPE_STUCK_NOTIF:
        cancelTrackedNotification();
        clearProgressTracking(true);
        break;
      case TYPE_SILENT:
        if (mAudioManager != null)
          mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        break;
      case TYPE_LOGO:
        openPowerHub();
        break;
      case TYPE_CAFFEINE:
        if (mCaffeineController != null) mCaffeineController.setDuration(0);
        break;
      case TYPE_NOTIF_SUPPRESS:
        if (mNotifSuppressController != null) mNotifSuppressController.expandDialog(null);
        break;
      case TYPE_FIVEG:
        if (mTelephonyManager != null) {
          long newType =
              mTelephonyManager.getAllowedNetworkTypesForReason(
                  TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
          newType &= ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
          mTelephonyManager.setAllowedNetworkTypesForReason(
              TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, newType);
        }
        break;
      default:
        break;
    }

    if (mCurrentDisplayState != TYPE_TRANSIENT) {
      triggerHaptic(VibrationEffect.EFFECT_CLICK);
      return;
    }

    if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
      openMediaApp();
    } else {
      openTrackedApp();
    }
    triggerHaptic(VibrationEffect.EFFECT_CLICK);
  }

  public void onLongPress() {
    switch (mCurrentDisplayState) {
      case TYPE_TRANSIENT:
        if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
          toggleMediaPlaybackState();
        } else {
          cancelTrackedTask();
        }
        break;
      case TYPE_FLASHLIGHT:
        launchSettings("com.android.settings.action.FLASHLIGHT_SETTINGS");
        break;
      case TYPE_HOTSPOT:
        launchSettings("android.settings.WIRELESS_SETTINGS");
        break;
      case TYPE_DND:
        launchSettings("android.settings.ZEN_MODE_SETTINGS");
        break;
      case TYPE_SAVER:
        launchSettings("android.settings.BATTERY_SAVER_SETTINGS");
        break;
      case TYPE_NIRVANA:
        break;
      case TYPE_ALARM:
        launchSettings("android.settings.ACTION_SHOW_ALARMS");
        break;
      case TYPE_SILENT:
        launchSettings("android.settings.SOUND_SETTINGS");
        break;
      case TYPE_LOGO:
        launchSettings("android.intent.action.POWER_USAGE_SUMMARY");
        break;
      case TYPE_CAFFEINE:
        if (mCaffeineController != null) mCaffeineController.expandDialog(null);
        break;
      case TYPE_NOTIF_SUPPRESS:
        if (mNotifSuppressController != null) mNotifSuppressController.expandDialog(null);
        break;
      case TYPE_FIVEG:
        launchSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
        break;
    }
    triggerHaptic(VibrationEffect.EFFECT_HEAVY_CLICK);
  }

  public void onDoubleTap() {
    if (mCurrentDisplayState == TYPE_TRANSIENT) {
      if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
        mIsMenuVisible = !mIsMenuVisible;
        notifyStateCallback();
        if (mIsMenuVisible) {
          mHandler.removeCallbacks(mMenuCollapseRunnable);
          mHandler.postDelayed(mMenuCollapseRunnable, 5000);
        }
        triggerHaptic(VibrationEffect.EFFECT_DOUBLE_CLICK);
      }
    }
  }

  public void onSwipe(boolean isNext) {
    if (isNext) skipToNextTrack();
    else skipToPreviousTrack();
  }

  public void onMediaAction(int action) {
    if (action == 0) skipToPreviousTrack();
    else if (action == 1) toggleMediaPlaybackState();
    else if (action == 2) skipToNextTrack();
    mHandler.removeCallbacks(mMenuCollapseRunnable);
    mHandler.postDelayed(mMenuCollapseRunnable, 5000);
  }

  public void onMediaMenuDismiss() {
    mIsMenuVisible = false;
    notifyStateCallback();
  }

  public void setSystemChipVisible(boolean visible) {
    if (mIsSystemChipVisible != visible) {
      mIsSystemChipVisible = visible;
      notifyStateCallback();
      requestUiUpdate();
    }
  }

  private void openTrackedApp() {
    if (mTrackedPackageName == null) {
      Log.w(TAG, "No tracked package available");
      return;
    }

    Intent launchIntent =
        mContext.getPackageManager().getLaunchIntentForPackage(mTrackedPackageName);
    if (launchIntent != null) {
      launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      mContext.startActivity(launchIntent);
    } else {
      Log.w(TAG, "No launch intent for package: " + mTrackedPackageName);
    }
  }

  private void onNotificationPosted(final StatusBarNotification sbn) {
    if (sbn == null || !mIsEnabled) return;

    Notification notification = sbn.getNotification();
    if (notification == null) return;

    mActiveNotificationsCache.put(sbn.getKey(), sbn);

    mHandler.post(
        () -> {
          boolean hasValidProgress = hasProgress(notification);
          String currentKey = mTrackedNotificationKey;

          if (!hasValidProgress) {
            if (currentKey != null && currentKey.equals(sbn.getKey())) {
              final String key = sbn.getKey();
              mHandler.postDelayed(
                  () -> {
                    if (mTrackedNotificationKey != null && mTrackedNotificationKey.equals(key)) {
                      StatusBarNotification currentSbn = mActiveNotificationsCache.get(key);
                      if (currentSbn == null || !hasProgress(currentSbn.getNotification())) {
                        clearProgressTracking(true);
                      }
                    }
                  },
                  150);
            }
            return;
          }

          if (!mIsTrackingProgress) {
            trackProgress(sbn);
          } else if (sbn.getKey().equals(currentKey)) {
            updateProgressIfNeeded(sbn);
          }
        });
  }

  private void onNotificationRemoved(final StatusBarNotification sbn) {
    if (sbn == null) return;

    mActiveNotificationsCache.remove(sbn.getKey());

    mHandler.post(
        () -> {
          if (!mIsTrackingProgress) return;

          if (sbn.getKey().equals(mTrackedNotificationKey)) {
            final String key = sbn.getKey();
            mHandler.postDelayed(
                () -> {
                  if (mTrackedNotificationKey != null && mTrackedNotificationKey.equals(key)) {
                    if (mActiveNotificationsCache.get(key) == null) {
                      clearProgressTracking(true);
                    }
                  }
                },
                150);
            return;
          }

          if (sbn.getPackageName().equals(mTrackedPackageName)) {
            final String key = mTrackedNotificationKey;
            mHandler.postDelayed(
                () -> {
                  if (mTrackedNotificationKey != null && mTrackedNotificationKey.equals(key)) {
                    StatusBarNotification currentSbn = mActiveNotificationsCache.get(key);
                    if (currentSbn == null || !hasProgress(currentSbn.getNotification())) {
                      clearProgressTracking(true);
                    }
                  }
                },
                150);
          }
        });
  }

  public void setForceHidden(final boolean forceHidden) {
    if (mIsForceHidden != forceHidden) {
      Log.d(TAG, "setForceHidden " + forceHidden);
      mIsForceHidden = forceHidden;
      notifyStateCallback();
      requestUiUpdate();

      if (!mIsForceHidden
          && mShowMediaProgress
          && mMediaSessionHelper != null
          && mMediaSessionHelper.isMediaPlaying()) {
        mHandler.removeCallbacks(mMediaProgressRunnable);
        mHandler.post(mMediaProgressRunnable);
      }
    }
  }

  private void toggleMediaPlaybackState() {
    if (mMediaSessionHelper != null) {
      mMediaSessionHelper.toggleMediaPlaybackState();
    }
  }

  private void skipToNextTrack() {
    if (mMediaSessionHelper != null) {
      mMediaSessionHelper.nextSong();
    }
  }

  private void skipToPreviousTrack() {
    if (mMediaSessionHelper != null) {
      mMediaSessionHelper.prevSong();
    }
  }

  private void openMediaApp() {
    if (mMediaSessionHelper != null) {
      mMediaSessionHelper.launchMediaApp();
    }
  }

  @Override
  public void onNotificationPosted(
      StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap) {
    onNotificationPosted(sbn);
  }

  @Override
  public void onNotificationRemoved(
      StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap) {
    onNotificationRemoved(sbn);
  }

  @Override
  public void onNotificationRemoved(
      StatusBarNotification sbn, NotificationListenerService.RankingMap _rankingMap, int _reason) {
    onNotificationRemoved(sbn);
  }

  @Override
  public void onHeadsUpPinnedModeChanged(boolean inPinnedMode) {
    mHeadsUpPinned = inPinnedMode;
    notifyStateCallback();
    requestUiUpdate();
  }

  @Override
  public void onNotificationRankingUpdate(NotificationListenerService.RankingMap _rankingMap) {}

  @Override
  public void onNotificationsInitialized() {}

  @Override
  public void onKeyguardShowingChanged() {
    setForceHidden(mKeyguardStateController.isShowing());
  }

  private class SettingsObserver extends ContentObserver {
    SettingsObserver(Handler handler) {
      super(handler);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
      super.onChange(selfChange, uri);
      if (uri.equals(Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED))
          || uri.equals(Settings.System.getUriFor(SHOW_MEDIA_PROGRESS))
          || uri.equals(Settings.System.getUriFor(PROGRESS_BAR_OPACITY))
          || uri.equals(Settings.System.getUriFor(ONGOING_SMART_ACTIONS_ENABLED))
          || uri.equals(Settings.System.getUriFor(COMPACT_MODE_ENABLED))) {
        updateSettings();
      } else if (uri.equals(Settings.System.getUriFor(SHOW_VOLTAGE_LOGO))) {
        updateSettings();
      } else if (uri.equals(Settings.Secure.getUriFor(NIRVANA_MODE_ACTIVE))) {
        boolean nirvanaActive =
            Settings.Secure.getInt(mContext.getContentResolver(), NIRVANA_MODE_ACTIVE, 0) == 1;
        updateStateHistory(TYPE_NIRVANA, nirvanaActive);
      }
    }

    public void register() {
      mContentResolver.registerContentObserver(
          Settings.System.getUriFor(ONGOING_ACTION_CHIP_ENABLED), false, this, UserHandle.USER_ALL);
      mContentResolver.registerContentObserver(
          Settings.System.getUriFor(SHOW_MEDIA_PROGRESS), false, this, UserHandle.USER_ALL);
      mContentResolver.registerContentObserver(
          Settings.System.getUriFor(PROGRESS_BAR_OPACITY), false, this, UserHandle.USER_ALL);
      mContentResolver.registerContentObserver(
          Settings.System.getUriFor(ONGOING_SMART_ACTIONS_ENABLED),
          false,
          this,
          UserHandle.USER_ALL);
      mContentResolver.registerContentObserver(
          Settings.System.getUriFor(COMPACT_MODE_ENABLED), false, this, UserHandle.USER_ALL);
      mContentResolver.registerContentObserver(
          Settings.Secure.getUriFor(NIRVANA_MODE_ACTIVE), false, this, UserHandle.USER_ALL);
      mContentResolver.registerContentObserver(
          Settings.System.getUriFor(SHOW_VOLTAGE_LOGO), false, this, UserHandle.USER_ALL);
      updateSettings();
    }

    public void unregister() {
      mContentResolver.unregisterContentObserver(this);
    }
  }

  private void updateSettings() {
    boolean wasEnabled = mIsEnabled;
    boolean wasShowingMedia = mShowMediaProgress;
    boolean wasCompactMode = mIsCompactModeEnabled;
    boolean wasSmartActions = mSmartActionsEnabled;

    mIsEnabled =
        Settings.System.getIntForUser(
                mContentResolver, ONGOING_ACTION_CHIP_ENABLED, 1, UserHandle.USER_CURRENT)
            == 1;
    mShowMediaProgress =
        Settings.System.getIntForUser(
                mContentResolver, SHOW_MEDIA_PROGRESS, 0, UserHandle.USER_CURRENT)
            == 1;
    mIsCompactModeEnabled =
        Settings.System.getIntForUser(
                mContentResolver, COMPACT_MODE_ENABLED, 0, UserHandle.USER_CURRENT)
            == 1;

    mSmartActionsEnabled =
        Settings.System.getIntForUser(
                mContentResolver, ONGOING_SMART_ACTIONS_ENABLED, 0, UserHandle.USER_CURRENT)
            == 1;

    int opacityPercentage =
        Settings.System.getIntForUser(
            mContentResolver,
            PROGRESS_BAR_OPACITY,
            DEFAULT_OPACITY_PERCENTAGE,
            UserHandle.USER_CURRENT);

    opacityPercentage = Math.max(0, Math.min(100, opacityPercentage));

    mProgressBarOpacity = (int) (opacityPercentage * 2.55f);

    mShowVoltageLogo =
        Settings.System.getIntForUser(
                mContentResolver, SHOW_VOLTAGE_LOGO, 0, UserHandle.USER_CURRENT)
            == 1;

    boolean nirvanaActive =
        Settings.Secure.getIntForUser(
                mContentResolver, NIRVANA_MODE_ACTIVE, 0, UserHandle.USER_CURRENT)
            == 1;
    updateStateHistory(TYPE_NIRVANA, nirvanaActive);

    if (wasEnabled != mIsEnabled
        || wasShowingMedia != mShowMediaProgress
        || wasCompactMode != mIsCompactModeEnabled
        || wasSmartActions != mSmartActionsEnabled) {
      mNeedsFullUiUpdate = true;
      mIsExpanded = false;
    }

    requestUiUpdate();
  }

  public void destroy() {
    mIsViewAttached = false;

    mHandler.removeCallbacks(mStaleProgressChecker);
    mHandler.removeCallbacks(mTransientGraceRunnable);
    mHandler.removeCallbacks(mAlarmCheckRunnable);
    mHandler.removeCallbacks(mTransientBufferRunnable);
    mHandler.removeCallbacks(mClearOverrideRunnable);
    mHandler.removeCallbacks(mFinishAnimRunnable);
    mHandler.removeCallbacks(mCompactCollapseRunnable);
    mHandler.removeCallbacks(mMenuCollapseRunnable);
    mHandler.removeCallbacks(mMediaProgressRunnable);

    mBroadcastDispatcher.unregisterReceiver(mRingerReceiver);
    mBroadcastDispatcher.unregisterReceiver(mConfigurationReceiver);
    mBroadcastDispatcher.unregisterReceiver(mSimStateReceiver);

    mSettingsObserver.unregister();

    if (mDarkIconDispatcher != null) {
      mDarkIconDispatcher.removeDarkReceiver(mDarkReceiver);
    }

    mKeyguardStateController.removeCallback(this);
    if (mFlashlightController != null) {
      mFlashlightController.removeCallback(this);
    }
    if (mHotspotController != null) {
      mHotspotController.removeCallback(this);
    }
    if (mZenModeController != null) {
      mZenModeController.removeCallback(this);
    }
    if (mBatteryController != null) {
      mBatteryController.removeCallback(this);
    }
    if (mNextAlarmController != null) {
      mNextAlarmController.removeCallback(this);
    }
    if (mTelephonyManager != null && mNetworkTypeListener != null) {
      mTelephonyManager.unregisterTelephonyCallback(mNetworkTypeListener);
    }
    if (mCaffeineController != null && mCaffeineListener != null) {
      mCaffeineController.removeListener(mCaffeineListener);
    }
    if (mNotifSuppressController != null && mNotifSuppressListener != null) {
      mNotifSuppressController.removeListener(mNotifSuppressListener);
    }

    if (mNotificationListener != null) {
      mNotificationListener.removeNotificationHandler(this);
    }

    if (mBackgroundExecutor != null) {
      mBackgroundExecutor.shutdownNow();
    }

    mHeadsUpManager.removeListener(this);
    mMediaSessionHelper.removeMediaMetadataListener(mMediaMetadataListener);

    mIsTrackingProgress = false;
    mTrackedNotificationKey = null;
    mTrackedPackageName = null;

    mInFlightIconLoads.clear();
    mActiveNotificationsCache.clear();

    mCurrentIconBitmap = null;

    mIconCache.evictAll();

    mDefaultMediaBitmapFull = null;
    mDefaultMediaBitmapCompact = null;
  }
}
