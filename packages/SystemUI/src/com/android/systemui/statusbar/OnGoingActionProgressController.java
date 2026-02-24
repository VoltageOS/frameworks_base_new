/**
 * Copyright (c) 2025-2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar;

import android.app.AlarmManager;
import android.app.Notification;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.database.ContentObserver;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.android.systemui.Dependency;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager;
import com.android.systemui.statusbar.notification.headsup.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.HotspotController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.NextAlarmController;
import com.android.systemui.statusbar.policy.CaffeineController;
import com.android.systemui.statusbar.policy.NotificationSuppressController;
import com.android.systemui.util.IconFetcher;
import com.android.systemui.util.MediaSessionManagerHelper;
import java.util.LinkedList;
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
  private static final int SWIPE_THRESHOLD = 100;
  private static final int SWIPE_VELOCITY_THRESHOLD = 100;
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
        Drawable icon,
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

  private final Context mContext;
  private final ContentResolver mContentResolver;
  private final Handler mHandler;
  private final SettingsObserver mSettingsObserver;
  private final KeyguardStateController mKeyguardStateController;
  private final NotificationListener mNotificationListener;
  private final HeadsUpManager mHeadsUpManager;
  private final IconFetcher mIconFetcher;
  private final MediaSessionManagerHelper mMediaSessionHelper;
  private final ExecutorService mBackgroundExecutor;
  private final FlashlightController mFlashlightController;
  private final HotspotController mHotspotController;
  private final ZenModeController mZenModeController;
  private final BatteryController mBatteryController;
  private final NextAlarmController mNextAlarmController;
  private final AudioManager mAudioManager;
  private final BroadcastDispatcher mBroadcastDispatcher;
  private DarkIconDispatcher mDarkIconDispatcher;
  private final DarkIconDispatcher.DarkReceiver mDarkReceiver;
  private StateCallback mStateCallback = null;
  private final boolean mIsComposeMode;

  private final ProgressBar mProgressBar;
  private final ProgressBar mCircularProgressBar;
  private final View mProgressRootView;
  private final View mCompactRootView;
  private final ImageView mIconView;
  private final ImageView mCompactIconView;

  private final LruCache<String, IconFetcher.AdaptiveDrawableResult> mIconCache = new LruCache<>(15);

  private boolean mShowMediaProgress = true;
  private boolean mIsTrackingProgress = false;
  private boolean mIsForceHidden = false;
  private boolean mHeadsUpPinned = false;
  private long mLastProgressUpdateTime = 0;
  private boolean mIsEnabled;
  private boolean mSmartActionsEnabled = true;
  private boolean mIsCompactModeEnabled = false;
  private int mCurrentProgress = 0;
  private int mCurrentProgressMax = 0;
  private Drawable mCurrentIcon = null;
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
  private PopupWindow mMediaPopup;
  private boolean mIsPopupActive = false;
  private boolean mNeedsFullUiUpdate = true;
  private boolean mIsViewAttached = false;
  private boolean mIsExpanded = false;

  private boolean mUpdatePending = false;
  private long mLastUpdateTime = 0;

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
  private final LinkedList<Integer> mActiveStatesHistory = new LinkedList<>();

  private boolean mHasTransient = false;
  private boolean mIsTransientGracePending = false;
  private long mFinishAnimationEndTime = 0;

  private long mLastTransientTime = 0;
  private final Runnable mTransientBufferRunnable = this::requestUiUpdate;
  private final Runnable mFinishAnimRunnable = this::requestUiUpdate;

  private TelephonyManager mTelephonyManager;
  private NetworkTypeListener mNetworkTypeListener;
  private CaffeineController mCaffeineController;
  private NotificationSuppressController mNotifSuppressController;

  private CaffeineController.CaffeineStateListener mCaffeineListener;
  private NotificationSuppressController.StateListener mNotifSuppressListener;

  private final BroadcastReceiver mRingerReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
          updateStateHistory(TYPE_SILENT, mAudioManager.getRingerModeInternal() == AudioManager.RINGER_MODE_SILENT);
      }
  };

  private final Runnable mTransientGraceRunnable = () -> {
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

  private class NetworkTypeListener extends TelephonyCallback implements TelephonyCallback.AllowedNetworkTypesListener {
      @Override
      public void onAllowedNetworkTypesChanged(int reason, long allowedNetworkType) {
          if (reason == TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER) {
              boolean is5g = (allowedNetworkType & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
              updateStateHistory(TYPE_FIVEG, is5g);
          }
      }
  }

  private final GestureDetector mGestureDetector;
  private final Handler mMediaProgressHandler = new Handler(Looper.getMainLooper());
  private final Runnable mMediaProgressRunnable =
      new Runnable() {
        @Override
        public void run() {
          if (mShowMediaProgress && mMediaSessionHelper.isMediaPlaying()) {
            updateMediaProgressOnly();
            mMediaProgressHandler.postDelayed(this, MEDIA_UPDATE_INTERVAL_MS);
          }
        }
      };

  private final Runnable mStaleProgressChecker =
      new Runnable() {
        @Override
        public void run() {
          synchronized (OnGoingActionProgressController.this) {
            checkForStaleProgress();
          }
          if (mIsViewAttached) {
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
      OnGoingActionProgressGroup progressGroup,
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

    mIsComposeMode = (progressGroup.rootView == null && progressGroup.compactRootView == null);

    mDarkReceiver = (areas, darkIntensity, tint) -> {
        if (mIconTint != tint) {
            mIconTint = tint;
            if (mIsComposeMode) notifyStateCallback();
        }
    };

    if (progressGroup == null) {
      Log.wtf(TAG, "progressGroup is null");
      throw new IllegalArgumentException("progressGroup cannot be null");
    }

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
            mTelephonyManager.registerTelephonyCallback(mContext.getMainExecutor(), mNetworkTypeListener);
            long allowed = mTelephonyManager.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
            boolean is5g = (allowed & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
            updateStateHistory(TYPE_FIVEG, is5g);
        }
    } catch (Exception e) {
        Log.e(TAG, "Failed to register 5G TelephonyCallback", e);
    }

    mCaffeineController = caffeineController;
    if (mCaffeineController != null) {
        mCaffeineListener = new CaffeineController.CaffeineStateListener() {
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
        mNotifSuppressListener = new NotificationSuppressController.StateListener() {
            @Override
            public void onStateChanged(boolean suppressed, String label) {
                updateStateHistory(TYPE_NOTIF_SUPPRESS, suppressed);
            }
        };
        mNotifSuppressController.addListener(mNotifSuppressListener);
        updateStateHistory(TYPE_NOTIF_SUPPRESS, mNotifSuppressController.isSuppressed());
    }

    mBroadcastDispatcher.registerReceiver(mRingerReceiver, new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION));

    mProgressBar = progressGroup.progressBarView;
    mCircularProgressBar = progressGroup.circularProgressBarView;
    mProgressRootView = progressGroup.rootView;
    mCompactRootView = progressGroup.compactRootView;
    mIconView = progressGroup.iconView;
    mCompactIconView = progressGroup.compactIconView;

    mIconFetcher = new IconFetcher(context);
    mMediaSessionHelper = MediaSessionManagerHelper.Companion.getInstance(context);

    mGestureDetector =
        mIsComposeMode ? null : new GestureDetector(mContext, new MediaGestureListener());
    mKeyguardStateController.addCallback(this);
    mHeadsUpManager.addListener(this);
    mNotificationListener.addNotificationHandler(this);
    mSettingsObserver.register();

    if (!mIsComposeMode) {
      if (mProgressRootView != null && mGestureDetector != null) {
        mProgressRootView.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));
      }

      if (mCompactRootView != null && mGestureDetector != null) {
        mCompactRootView.setOnTouchListener((v, event) -> mGestureDetector.onTouchEvent(event));

        mCompactRootView.setOnClickListener(
            v -> {
              onInteraction();
            });
      }
    }

    mMediaSessionHelper.addMediaMetadataListener(mMediaMetadataListener);

    mIsViewAttached = true;
    updateSettings();

    mHandler.postDelayed(mStaleProgressChecker, STALE_PROGRESS_CHECK_INTERVAL_MS);
  }

  private void triggerHaptic(int effectId) {
      Vibrator vibrator = mContext.getSystemService(Vibrator.class);
      if (vibrator != null && vibrator.hasVibrator()) {
          vibrator.vibrate(VibrationEffect.createPredefined(effectId));
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

    if (mIsComposeMode) {
      notifyStateCallback();
      return;
    }

    if (mCompactRootView != null) mCompactRootView.setVisibility(View.GONE);
    if (mProgressRootView != null) mProgressRootView.setVisibility(View.VISIBLE);

    requestUiUpdate();
  }

  private class MediaGestureListener extends GestureDetector.SimpleOnGestureListener {
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
      onInteraction();
      return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
      OnGoingActionProgressController.this.onDoubleTap();
      return true;
    }

    @Override
    public void onLongPress(MotionEvent e) {
      OnGoingActionProgressController.this.onLongPress();
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
      if (!(mShowMediaProgress && mMediaSessionHelper.isMediaPlaying())) {
        return false;
      }
      float diffX = e2.getX() - e1.getX();
      if (Math.abs(diffX) > Math.abs(e2.getY() - e1.getY())
          && Math.abs(diffX) > SWIPE_THRESHOLD
          && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
        if (diffX > 0) {
          skipToNextTrack();
        } else {
          skipToPreviousTrack();
        }
        return true;
      }
      return false;
    }
  }

  private void updateStateHistory(int type, boolean active) {
      if (active && !mActiveStatesHistory.contains(type)) {
          mLastStateChangeTime = System.currentTimeMillis();
      }

      mActiveStatesHistory.remove((Integer) type);
      if (active) {
          mActiveStatesHistory.addLast(type);
      }
      requestUiUpdate();
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
  public void onFlashlightStrengthChanged(int level) {
  }

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
    requestUiUpdate();
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

    boolean isVisible = !mIsForceHidden && !mHeadsUpPinned && !mIsSystemChipVisible && mCurrentDisplayState != TYPE_NONE;

    if (isVisible) {
      float opacity = mProgressBarOpacity / 255f;
      boolean isCompact = mIsCompactModeEnabled && !mIsExpanded;
      mStateCallback.onStateChanged(
          true,
          mCurrentProgress,
          mCurrentProgressMax,
          mCurrentIcon,
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
      mStateCallback.onStateChanged(false, 0, 0, null, false, null, false, 0f, false, TYPE_NONE, 100, false, false, android.graphics.Color.WHITE);
    }
  }

  private void updateViews() {
    if (!mIsViewAttached) {
      if (mIsComposeMode) {
        notifyStateCallback();
      }
      return;
    }

    boolean isMediaPlaying = mShowMediaProgress && mMediaSessionHelper.isMediaPlaying();
    boolean isTransientNow = isMediaPlaying || (mIsEnabled && mIsTrackingProgress && !mIsStuck);
    long now = System.currentTimeMillis();

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

    if (now < mFinishAnimationEndTime) {
        mCurrentDisplayState = TYPE_DONE_CHECKMARK;
        mHandler.removeCallbacks(mFinishAnimRunnable);
        mHandler.postDelayed(mFinishAnimRunnable, mFinishAnimationEndTime - now + 50);
    } else if (isTransientBuffered && !mIsTransientGracePending) {
        mCurrentDisplayState = TYPE_TRANSIENT;
    } else {
        if (mSmartActionsEnabled && !mActiveStatesHistory.isEmpty()) {
            mCurrentDisplayState = mActiveStatesHistory.getLast();
        } else {
            mCurrentDisplayState = mShowVoltageLogo ? TYPE_LOGO : TYPE_NONE;
        }
    }

    if (!mIsComposeMode && mProgressRootView != null && mCompactRootView != null) {
      float opacity = mProgressBarOpacity / 255f;
      mProgressRootView.setAlpha(opacity);
      mCompactRootView.setAlpha(opacity);
    }

    if (mIsForceHidden || mHeadsUpPinned || mCurrentDisplayState == TYPE_NONE) {
      if (!mIsComposeMode) {
        if (mProgressRootView != null) mProgressRootView.setVisibility(View.GONE);
        if (mCompactRootView != null) mCompactRootView.setVisibility(View.GONE);
      }
      notifyStateCallback();
      return;
    }

    if (mCurrentDisplayState != TYPE_TRANSIENT && mCurrentDisplayState != TYPE_DONE_CHECKMARK) {
      if (!mIsComposeMode) {
        if (mProgressRootView != null) mProgressRootView.setVisibility(View.GONE);
        if (mCompactRootView != null) {
          mCompactRootView.setVisibility(View.VISIBLE);
          if (mCircularProgressBar != null) {
            mCircularProgressBar.setProgress(100);
            mCircularProgressBar.setMax(100);
          }
        }
      }
      notifyStateCallback();
      return;
    }

    if (mIsCompactModeEnabled && !mIsExpanded) {
      if (!mIsComposeMode && mProgressRootView != null) {
        mProgressRootView.setVisibility(View.GONE);
      }

      if (!mIsEnabled && !isMediaPlaying) {
        if (!mIsComposeMode && mCompactRootView != null) {
          mCompactRootView.setVisibility(View.GONE);
        }
        notifyStateCallback();
        return;
      }

      if (!mIsComposeMode && mCompactRootView != null) {
        mCompactRootView.setVisibility(View.VISIBLE);
      }

      if (isMediaPlaying) {
        updateMediaProgressCompact();
      } else {
        updateNotificationProgressCompact();
      }
    } else {
      if (!mIsComposeMode && mCompactRootView != null) {
        mCompactRootView.setVisibility(View.GONE);
      }

      if (isMediaPlaying) {
        if (!mIsComposeMode && mProgressRootView != null) {
          mProgressRootView.setVisibility(View.VISIBLE);
        }

        if (mNeedsFullUiUpdate) {
          updateMediaProgressFull();
          mNeedsFullUiUpdate = false;
        } else {
          updateMediaProgressOnly();
        }
      } else {
        updateNotificationProgress();
      }
    }
    notifyStateCallback();
  }

  private void updateMediaProgressOnly() {
    if (!mIsViewAttached && !mIsComposeMode) {
      return;
    }

    long totalDuration = mMediaSessionHelper.getTotalDuration();

    android.media.session.PlaybackState playbackState =
        mMediaSessionHelper.getMediaControllerPlaybackState();
    long currentProgress = 0;

    if (playbackState != null) {
      currentProgress = playbackState.getPosition();
    }

    mCurrentProgress = (int) currentProgress;
    mCurrentProgressMax = (int) totalDuration;
    if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

    if (!mIsComposeMode
        && mProgressRootView != null
        && mProgressRootView.getVisibility() == View.VISIBLE
        && mProgressBar != null
        && totalDuration > 0) {
      mProgressBar.setMax((int) totalDuration);
      mProgressBar.setProgress((int) currentProgress);
    }

    if (!mIsComposeMode
        && mCompactRootView != null
        && mCompactRootView.getVisibility() == View.VISIBLE
        && mCircularProgressBar != null
        && totalDuration > 0) {
      mCircularProgressBar.setMax((int) totalDuration);
      mCircularProgressBar.setProgress((int) currentProgress);
    }

    if (mIsComposeMode) {
      notifyStateCallback();
    }
  }

  private void updateMediaProgressFull() {
    if (!mIsViewAttached && !mIsComposeMode) return;

    if (!mIsComposeMode && mProgressRootView != null) {
      mProgressRootView.setVisibility(View.VISIBLE);
    }

    mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
    mMediaProgressHandler.post(mMediaProgressRunnable);

    Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();

    if (mediaAppIcon != null) {
      mCurrentIcon = mediaAppIcon;
      mCurrentIconIsAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
      if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(mediaAppIcon);
    } else {
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
              Drawable drawable = result != null ? result.drawable : null;
              boolean isAdaptive = result != null ? result.isAdaptive : false;

              if (drawable != null) {
                mCurrentIcon = drawable;
                mCurrentIconIsAdaptive = isAdaptive;
                if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(drawable);
              } else {
                setDefaultMediaIcon();
              }
              if (mIsComposeMode) notifyStateCallback();
            });
      } else {
        setDefaultMediaIcon();
      }
    }

    updateMediaProgressOnly();
  }

  private void setDefaultMediaIcon() {
    mCurrentIcon = mContext.getResources().getDrawable(R.drawable.ic_default_music_icon);
    mCurrentIconIsAdaptive = false;
    if (!mIsComposeMode && mIconView != null) mIconView.setImageDrawable(mCurrentIcon);
  }

  private void updateMediaProgressCompact() {
    if (!mIsViewAttached && !mIsComposeMode) return;

    if (!mIsComposeMode && mCompactRootView != null) {
      mCompactRootView.setVisibility(View.VISIBLE);
    }

    mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
    mMediaProgressHandler.post(mMediaProgressRunnable);

    long totalDuration = mMediaSessionHelper.getTotalDuration();

    android.media.session.PlaybackState playbackState =
        mMediaSessionHelper.getMediaControllerPlaybackState();
    long currentProgress = 0;

    if (playbackState != null) {
      currentProgress = playbackState.getPosition();
    }

    mCurrentProgress = (int) currentProgress;
    mCurrentProgressMax = (int) totalDuration;
    if (mCurrentProgressMax <= 0) mCurrentProgressMax = 100;

    if (!mIsComposeMode && totalDuration > 0 && mCircularProgressBar != null) {
      mCircularProgressBar.setMax((int) totalDuration);
      mCircularProgressBar.setProgress((int) currentProgress);
    }

    Drawable mediaAppIcon = mMediaSessionHelper.getMediaAppIcon();

    if (mediaAppIcon != null) {
      mCurrentIcon = mediaAppIcon;
      mCurrentIconIsAdaptive = mediaAppIcon instanceof AdaptiveIconDrawable;
      if (!mIsComposeMode && mCompactIconView != null) {
        mCompactIconView.setImageDrawable(mediaAppIcon);
      }
    } else {
      String packageName = null;
      if (playbackState != null && playbackState.getExtras() != null) {
        packageName = playbackState.getExtras().getString("package");
      }

      if (packageName != null) {
        loadIconInBackground(
            packageName,
            result -> {
              Drawable drawable = result != null ? result.drawable : null;
              boolean isAdaptive = result != null ? result.isAdaptive : false;

              if (drawable != null) {
                mCurrentIcon = drawable;
                mCurrentIconIsAdaptive = isAdaptive;
                if (!mIsComposeMode && mCompactIconView != null)
                  mCompactIconView.setImageDrawable(drawable);
              } else {
                setDefaultMediaIconCompact();
              }
              if (mIsComposeMode) notifyStateCallback();
            });
      } else {
        setDefaultMediaIconCompact();
      }
    }
  }

  private void setDefaultMediaIconCompact() {
    mCurrentIcon = mContext.getResources().getDrawable(R.drawable.ic_default_music_icon);
    mCurrentIconIsAdaptive = false;
    if (!mIsComposeMode && mCompactIconView != null)
      mCompactIconView.setImageDrawable(mCurrentIcon);
  }

  private void updateNotificationProgress() {
    if (!mIsViewAttached && !mIsComposeMode) return;

    if (!mIsEnabled || !mIsTrackingProgress) {
      if (!mIsComposeMode && mProgressRootView != null) {
        mProgressRootView.setVisibility(View.GONE);
      }
      mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
      return;
    }

    if (!mIsComposeMode && mProgressRootView != null) {
      mProgressRootView.setVisibility(View.VISIBLE);
    }
    if (mCurrentProgressMax <= 0) {
      Log.w(TAG, "updateViews: invalid max progress " + mCurrentProgressMax + ", using 100");
      mCurrentProgressMax = 100;
    }

    if (!mIsComposeMode && mProgressBar != null) {
      mProgressBar.setMax(mCurrentProgressMax);
      mProgressBar.setProgress(mCurrentProgress);
    }

    if (mTrackedPackageName != null) {
      loadIconInBackground(
          mTrackedPackageName,
          result -> {
            Drawable drawable = result != null ? result.drawable : null;
            boolean isAdaptive = result != null ? result.isAdaptive : false;

            mCurrentIcon = drawable;
            mCurrentIconIsAdaptive = isAdaptive;
            if (!mIsComposeMode && mIconView != null && drawable != null) {
              mIconView.setImageDrawable(drawable);
            }
            if (mIsComposeMode) notifyStateCallback();
          });
    }
  }

  private void updateNotificationProgressCompact() {
    if (!mIsViewAttached && !mIsComposeMode) return;

    if (!mIsEnabled || !mIsTrackingProgress) {
      if (!mIsComposeMode && mCompactRootView != null) {
        mCompactRootView.setVisibility(View.GONE);
      }
      mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);
      return;
    }

    if (!mIsComposeMode && mCompactRootView != null) {
      mCompactRootView.setVisibility(View.VISIBLE);
    }
    if (mCurrentProgressMax <= 0) {
      Log.w(TAG, "updateViews: invalid max progress " + mCurrentProgressMax + ", using 100");
      mCurrentProgressMax = 100;
    }

    if (!mIsComposeMode && mCircularProgressBar != null) {
      mCircularProgressBar.setMax(mCurrentProgressMax);
      mCircularProgressBar.setProgress(mCurrentProgress);
    }

    if (mTrackedPackageName != null) {
      loadIconInBackground(
          mTrackedPackageName,
          result -> {
            Drawable drawable = result != null ? result.drawable : null;
            boolean isAdaptive = result != null ? result.isAdaptive : false;

            mCurrentIcon = drawable;
            mCurrentIconIsAdaptive = isAdaptive;
            if (!mIsComposeMode && mCompactIconView != null && drawable != null) {
              mCompactIconView.setImageDrawable(drawable);
            }
            if (mIsComposeMode) notifyStateCallback();
          });
    }
  }

  private void loadIconInBackground(String packageName, IconCallback callback) {
    if (packageName == null) return;

    if (mIconCache.get(packageName) != null) {
      IconFetcher.AdaptiveDrawableResult cachedResult = mIconCache.get(packageName);
      if (cachedResult != null) {
        callback.onIconLoaded(cachedResult);
        return;
      }
    }

    mBackgroundExecutor.execute(
        () -> {
          final IconFetcher.AdaptiveDrawableResult iconResult =
              mIconFetcher.getMonotonicPackageIcon(packageName);

          if (iconResult != null && iconResult.drawable != null) {
            if (mIsComposeMode) {
              int sizePx = (int) (24 * mContext.getResources().getDisplayMetrics().density);
              iconResult.drawable.setBounds(0, 0, sizePx, sizePx);
            }

            mIconCache.put(packageName, iconResult);

            mHandler.post(
                () -> {
                  callback.onIconLoaded(iconResult);
                });
          }
        });
  }

  private interface IconCallback {
    void onIconLoaded(@Nullable IconFetcher.AdaptiveDrawableResult result);
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

    StatusBarNotification sbn = findNotificationByKey(mTrackedNotificationKey);
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

  @Nullable
  private StatusBarNotification findNotificationByKey(String key) {
    if (key == null || mNotificationListener == null) return null;

    for (StatusBarNotification notification : mNotificationListener.getActiveNotifications()) {
      if (notification.getKey().equals(key)) {
        return notification;
      }
    }
    return null;
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
              for (StatusBarNotification sbn : mNotificationListener.getActiveNotifications()) {
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
                                  } catch (Exception e) {}
                              }
                          }
                      }
                      
                      if (mNotificationListener instanceof NotificationListenerService) {
                          ((NotificationListenerService) mNotificationListener).cancelNotification(mTrackedNotificationKey);
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
          if (mNotificationListener instanceof NotificationListenerService && mTrackedNotificationKey != null) {
              ((NotificationListenerService) mNotificationListener).cancelNotification(mTrackedNotificationKey);
          }
      } catch (Exception e) {
          Log.e(TAG, "Failed to cancel notification", e);
      }
  }

  private void openPowerHub() {
      Intent intent = new Intent(Intent.ACTION_MAIN);
      intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.SubSettings"));
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
              for (StatusBarNotification sbn : mNotificationListener.getActiveNotifications()) {
                  if (alarmPackage.equals(sbn.getPackageName())) {
                      Notification n = sbn.getNotification();
                      if (n.actions != null) {
                          for (Notification.Action action : n.actions) {
                              String title = String.valueOf(action.title).toLowerCase();
                              if (title.contains("dismiss") || title.contains("cancel") || title.contains("turn off")) {
                                  try {
                                      action.actionIntent.send();
                                      dismissed = true;
                                      break;
                                  } catch (Exception e) {}
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
              } catch (Exception e) { }
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

    switch(mCurrentDisplayState) {
      case TYPE_FLASHLIGHT:
        if (mFlashlightController != null) mFlashlightController.setFlashlight(false);
        break;
      case TYPE_HOTSPOT:
        if (mHotspotController != null) mHotspotController.setHotspotEnabled(false);
        break;
      case TYPE_DND:
        if (mZenModeController != null) mZenModeController.setZen(Settings.Global.ZEN_MODE_OFF, null, TAG);
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
        if (mAudioManager != null) mAudioManager.setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL);
        break;
      case TYPE_LOGO:
        openPowerHub();
        break;
      case TYPE_CAFFEINE:
        if (mCaffeineController != null) mCaffeineController.setDuration(0);
        break;
      case TYPE_NOTIF_SUPPRESS:
        if (mNotifSuppressController != null) mNotifSuppressController.setDuration(0);
        break;
      case TYPE_FIVEG:
        if (mTelephonyManager != null) {
            long newType = mTelephonyManager.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
            newType &= ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
            mTelephonyManager.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, newType);
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
    switch(mCurrentDisplayState) {
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
            if (mIsComposeMode) {
                mIsMenuVisible = !mIsMenuVisible;
                notifyStateCallback();
                if (mIsMenuVisible) {
                    mHandler.removeCallbacks(mMenuCollapseRunnable);
                    mHandler.postDelayed(mMenuCollapseRunnable, 5000);
                }
            } else {
                showMediaPopup(mProgressRootView);
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

  private void showMediaPopup(View anchorView) {
    if (mIsComposeMode || anchorView == null) {
      return;
    }

    if (mIsPopupActive) {
      if (mMediaPopup != null) {
        mMediaPopup.dismiss();
      }
      mIsPopupActive = false;
      return;
    }

    Context context = anchorView.getContext();
    View popupView = LayoutInflater.from(context).inflate(R.layout.media_control_popup, null);

    if (mMediaPopup != null && mMediaPopup.isShowing()) {
      mMediaPopup.dismiss();
    }

    mMediaPopup =
        new PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true);
    mMediaPopup.setOutsideTouchable(true);
    mMediaPopup.setFocusable(true);
    mMediaPopup.setOnDismissListener(() -> mIsPopupActive = false);

    ImageButton btnPrevious = popupView.findViewById(R.id.btn_previous);
    ImageButton btnNext = popupView.findViewById(R.id.btn_next);

    if (btnPrevious != null) {
      btnPrevious.setOnClickListener(
          v -> {
            skipToPreviousTrack();
            mMediaPopup.dismiss();
          });
    }

    if (btnNext != null) {
      btnNext.setOnClickListener(
          v -> {
            skipToNextTrack();
            mMediaPopup.dismiss();
          });
    }

    anchorView.post(
        () -> {
          if (!mIsViewAttached) return;

          int offsetX = -popupView.getWidth() / 3;
          int offsetY = -anchorView.getHeight();
          mMediaPopup.showAsDropDown(anchorView, offsetX, offsetY);
          mIsPopupActive = true;
        });
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

    synchronized (this) {
      boolean hasValidProgress = hasProgress(notification);
      String currentKey = mTrackedNotificationKey;

      if (!hasValidProgress) {
        if (currentKey != null && currentKey.equals(sbn.getKey())) {
                final String key = sbn.getKey();
                mHandler.postDelayed(() -> {
                    synchronized (OnGoingActionProgressController.this) {
                        if (mTrackedNotificationKey != null && mTrackedNotificationKey.equals(key)) {
                            StatusBarNotification currentSbn = findNotificationByKey(key);
                            if (currentSbn == null || !hasProgress(currentSbn.getNotification())) {
                                clearProgressTracking(true);
                            }
                        }
                    }
                }, 500);
        }
        return;
      }

      if (!mIsTrackingProgress) {
        trackProgress(sbn);
      } else if (sbn.getKey().equals(currentKey)) {
        updateProgressIfNeeded(sbn);
      }
    }
  }

  private void onNotificationRemoved(final StatusBarNotification sbn) {
    if (sbn == null) return;

    synchronized (this) {
      if (!mIsTrackingProgress) return;

      if (sbn.getKey().equals(mTrackedNotificationKey)) {
              final String key = sbn.getKey();
              mHandler.postDelayed(() -> {
                  synchronized (OnGoingActionProgressController.this) {
                      if (mTrackedNotificationKey != null && mTrackedNotificationKey.equals(key)) {
                          if (findNotificationByKey(key) == null) {
                              clearProgressTracking(true);
                          }
                      }
                  }
              }, 500);
        return;
      }

      if (sbn.getPackageName().equals(mTrackedPackageName)) {
              final String key = mTrackedNotificationKey;
              mHandler.postDelayed(() -> {
                  synchronized (OnGoingActionProgressController.this) {
                      if (mTrackedNotificationKey != null && mTrackedNotificationKey.equals(key)) {
                          StatusBarNotification currentSbn = findNotificationByKey(key);
                          if (currentSbn == null || !hasProgress(currentSbn.getNotification())) {
                              clearProgressTracking(true);
                          }
                      }
                  }
              }, 500);
      }
    }
  }

  public void setForceHidden(final boolean forceHidden) {
    if (mIsForceHidden != forceHidden) {
      Log.d(TAG, "setForceHidden " + forceHidden);
      mIsForceHidden = forceHidden;
      notifyStateCallback();
      requestUiUpdate();
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
          boolean nirvanaActive = Settings.Secure.getInt(mContext.getContentResolver(), NIRVANA_MODE_ACTIVE, 0) == 1;
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
          Settings.System.getUriFor(ONGOING_SMART_ACTIONS_ENABLED), false, this, UserHandle.USER_ALL);
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
                mContentResolver, ONGOING_SMART_ACTIONS_ENABLED, 1, UserHandle.USER_CURRENT)
            == 1;

    int opacityPercentage =
        Settings.System.getIntForUser(
            mContentResolver,
            PROGRESS_BAR_OPACITY,
            DEFAULT_OPACITY_PERCENTAGE,
            UserHandle.USER_CURRENT);

    opacityPercentage = Math.max(0, Math.min(100, opacityPercentage));

    mProgressBarOpacity = (int) (opacityPercentage * 2.55f);

    mShowVoltageLogo = Settings.System.getIntForUser(
            mContentResolver, SHOW_VOLTAGE_LOGO, 0, UserHandle.USER_CURRENT) == 1;

    boolean nirvanaActive = Settings.Secure.getIntForUser(
            mContentResolver, NIRVANA_MODE_ACTIVE, 0, UserHandle.USER_CURRENT) == 1;
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
    mHandler.removeCallbacks(mFinishAnimRunnable);
    mHandler.removeCallbacks(mCompactCollapseRunnable);
    mHandler.removeCallbacks(mMenuCollapseRunnable);

    mBroadcastDispatcher.unregisterReceiver(mRingerReceiver);

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

    mMediaProgressHandler.removeCallbacks(mMediaProgressRunnable);

    if (mMediaPopup != null && mMediaPopup.isShowing()) {
      mMediaPopup.dismiss();
    }

    mIsTrackingProgress = false;
    mTrackedNotificationKey = null;
    mTrackedPackageName = null;

    mIconCache.evictAll();

    if (!mIsComposeMode && mIconView != null) {
      mIconView.setImageDrawable(null);
    }
    if (!mIsComposeMode && mCompactIconView != null) {
      mCompactIconView.setImageDrawable(null);
    }
    mCurrentIcon = null;
  }

  private static int getThemeColor(Context context, int attrResId) {
    TypedValue typedValue = new TypedValue();
    context.getTheme().resolveAttribute(attrResId, typedValue, true);
    return typedValue.data;
  }
}
