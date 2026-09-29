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

package com.android.systemui.navigationbar.gestural.pie;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.android.systemui.res.R;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Executor;

public class PieMenuController {
    public static final float ARC_DEGREES = 160f;
    private static final String TAG = "PieMenuController";
    private static final long WATCHDOG_MS = 5000;
    private static final float HYSTERESIS_DEG = 8f;
    public interface PieLaunchHandler {
        void launchPieItem(PieItem item, boolean isVertical);
    }
    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mMainHandler;
    private final Handler mWatchdogHandler;
    private final Executor mBgExecutor;
    private final Vibrator mVibrator;
    private final PieItemRepository mRepository;
    private PieLaunchHandler mLaunchHandler;
    private PieMenuView mView;
    private boolean mShowing;
    private List<PieItem> mItems;
    private float mAnchorX;
    private float mAnchorY;
    private boolean mIsLeftEdge;
    private boolean mIsVertical;
    private int mDisplayId;
    private int mHoveredIndex = -1;
    private int mRadiusPx;
    private int mIconSizePx;
    private int mDeadZonePx;
    private int mEdgeInsetPx;
    private float mArcDeg = ARC_DEGREES;
    private boolean mHapticEnabled = true;
    private final Runnable mWatchdog = () -> {
        try {
            dismiss(false);
        } catch (Exception e) {
            Log.w(TAG, "watchdog dismiss failed", e);
        }
    };

    public PieMenuController(Context context, WindowManager windowManager, Handler mainHandler,
            Executor bgExecutor, PieItemRepository repository) {
        mContext = context;
        mWindowManager = windowManager;
        mMainHandler = mainHandler;
        mBgExecutor = bgExecutor;
        mRepository = repository;
        mVibrator = context.getSystemService(Vibrator.class);
        mWatchdogHandler = new Handler(Looper.getMainLooper());
    }

    public void setLaunchHandler(PieLaunchHandler handler) {
        mLaunchHandler = handler;
    }

    public boolean isShowing() {
        return mShowing;
    }

    public int getHoveredIndex() {
        return mHoveredIndex;
    }

    public PieItem getHoveredItem() {
        if (!mShowing || mItems == null || mHoveredIndex < 0 || mHoveredIndex >= mItems.size()) {
            return null;
        }
        return mItems.get(mHoveredIndex);
    }

    public boolean show(int anchorX, int anchorY, boolean isLeftEdge, boolean isVertical,
            int displayId, boolean hapticEnabled) {
        if (mShowing) {
            dismiss(false);
        }
        mHapticEnabled = hapticEnabled;
        List<PieItem> items;
        try {
            items = mRepository.getItems(isLeftEdge, isVertical);
        } catch (Exception e) {
            Log.w(TAG, "show load failed", e);
            return false;
        }
        if (items == null || items.isEmpty()) {
            Log.w(TAG, "show with empty pie items");
            return false;
        }
        loadDims();
        Point size = new Point();
        try {
            mWindowManager.getDefaultDisplay().getRealSize(size);
        } catch (Exception e) {
            Log.w(TAG, "getRealSize failed", e);
            return false;
        }
        float iconR = mIconSizePx / 2f + 18f;
        float labelPad = 28f * mContext.getResources().getDisplayMetrics().density;
        float ax = anchorX;
        if (isLeftEdge) {
            if (ax < iconR) {
                ax = iconR;
            }
        } else {
            if (ax > size.x - iconR) {
                ax = size.x - iconR;
            }
        }
        float edgePad = mEdgeInsetPx + iconR + labelPad;
        float clampedY = anchorY;
        float minY = mRadiusPx + edgePad;
        float maxY = size.y - mRadiusPx - edgePad;
        if (maxY < minY) {
            clampedY = size.y / 2f;
        } else {
            if (clampedY < minY) {
                clampedY = minY;
            }
            if (clampedY > maxY) {
                clampedY = maxY;
            }
        }
        mItems = items;
        mAnchorX = ax;
        mAnchorY = clampedY;
        mIsLeftEdge = isLeftEdge;
        mIsVertical = isVertical;
        mDisplayId = displayId;
        mHoveredIndex = -1;
        try {
            mView = new PieMenuView(mContext);
            mView.setItems(mItems);
            WindowManager.LayoutParams lp = createLayoutParams();
            mWindowManager.addView(mView, lp);
            mView.show(mAnchorX, mAnchorY, mIsLeftEdge, mRadiusPx, mIconSizePx);
            mShowing = true;
            mWatchdogHandler.removeCallbacks(mWatchdog);
            mWatchdogHandler.postDelayed(mWatchdog, WATCHDOG_MS);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "show addView failed", e);
            mView = null;
            mShowing = false;
            return false;
        }
    }

    public void onMotionEvent(MotionEvent ev) {
        if (!mShowing || mView == null || mItems == null) {
            return;
        }
        mWatchdogHandler.removeCallbacks(mWatchdog);
        mWatchdogHandler.postDelayed(mWatchdog, WATCHDOG_MS);
        float fx = ev.getX();
        float fy = ev.getY();
        int candidate = selectIndex(fx, fy, mAnchorX, mAnchorY, mIsLeftEdge, mDeadZonePx,
                mArcDeg, mItems.size());
        int next = applyHysteresis(candidate, mHoveredIndex, fx, fy, mAnchorX, mAnchorY,
                mIsLeftEdge, mArcDeg, mItems.size());
        if (next != mHoveredIndex) {
            mHoveredIndex = next;
            try {
                mView.setHoveredIndex(next);
            } catch (Exception e) {
                Log.w(TAG, "setHoveredIndex failed", e);
            }
            if (next >= 0 && mHapticEnabled) {
                try {
                    mVibrator.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_TICK, true));
                } catch (Exception e) {
                    Log.w(TAG, "hover haptic failed", e);
                }
            }
        }
    }

    public PieItem dismiss(boolean commit) {
        mWatchdogHandler.removeCallbacks(mWatchdog);
        if (!mShowing) {
            return null;
        }
        PieItem result = null;
        if (commit) {
            result = getHoveredItem();
        }
        boolean launched = result != null;
        PieMenuView view = mView;
        mShowing = false;
        mHoveredIndex = -1;
        mView = null;
        mItems = null;
        if (view != null) {
            try {
                view.hide(launched, () -> {
                    try {
                        mWindowManager.removeView(view);
                    } catch (Exception e) {
                        Log.w(TAG, "removeView failed", e);
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "hide failed", e);
                try {
                    mWindowManager.removeView(view);
                } catch (Exception ex) {
                    Log.w(TAG, "removeView failed", ex);
                }
            }
        }
        return result;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "PieMenuController showing=" + mShowing
                + " hovered=" + mHoveredIndex
                + " anchor=[" + mAnchorX + "," + mAnchorY + "]"
                + " left=" + mIsLeftEdge + " vertical=" + mIsVertical
                + " display=" + mDisplayId);
        if (mItems != null) {
            for (int i = 0; i < mItems.size(); i++) {
                pw.println(prefix + "  [" + i + "] " + mItems.get(i).toString());
            }
        }
    }

    private void loadDims() {
        try {
            mRadiusPx = mContext.getResources().getDimensionPixelSize(R.dimen.pie_menu_radius);
        } catch (Exception e) {
            mRadiusPx = 300;
        }
        try {
            mIconSizePx = mContext.getResources().getDimensionPixelSize(R.dimen.pie_menu_icon_size);
        } catch (Exception e) {
            mIconSizePx = 110;
        }
        try {
            mDeadZonePx = mContext.getResources().getDimensionPixelSize(R.dimen.pie_menu_dead_zone);
        } catch (Exception e) {
            mDeadZonePx = 80;
        }
        try {
            mEdgeInsetPx = mContext.getResources().getDimensionPixelSize(R.dimen.pie_menu_edge_inset);
        } catch (Exception e) {
            mEdgeInsetPx = 54;
        }
        try {
            TypedValue v = new TypedValue();
            mContext.getResources().getValue(R.dimen.pie_menu_arc_degrees, v, true);
            mArcDeg = v.getFloat();
        } catch (Exception e) {
            mArcDeg = ARC_DEGREES;
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.windowAnimations = 0;
        lp.privateFlags |= (WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_EXCLUDE_FROM_SCREEN_MAGNIFICATION);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setTitle("PieMenu");
        lp.setFitInsetsTypes(0);
        lp.setTrustedOverlay();
        return lp;
    }

    public static PointF computeCenter(float anchorX, float anchorY, boolean isLeftEdge,
            int radiusPx, float arcDeg, int n, int index) {
        float offset;
        if (n <= 1) {
            offset = 0f;
        } else {
            offset = -arcDeg / 2f + arcDeg * index / (n - 1);
        }
        float angleDeg = isLeftEdge ? offset : 180f - offset;
        double rad = Math.toRadians(angleDeg);
        return new PointF(
                anchorX + (float) (radiusPx * Math.cos(rad)),
                anchorY + (float) (radiusPx * Math.sin(rad)));
    }

    public static float itemAngleDeg(boolean isLeftEdge, float arcDeg, int n, int index) {
        float offset;
        if (n <= 1) {
            offset = 0f;
        } else {
            offset = -arcDeg / 2f + arcDeg * index / (n - 1);
        }
        float angle = isLeftEdge ? offset : 180f - offset;
        return normalizeDeg(angle);
    }

    public static int selectIndex(float fx, float fy, float anchorX, float anchorY,
            boolean isLeftEdge, int deadZonePx, float arcDeg, int n) {
        if (n <= 0) {
            return -1;
        }
        float dx = fx - anchorX;
        float dy = fy - anchorY;
        if (isLeftEdge && dx < 0) {
            dx = 0;
        }
        if (!isLeftEdge && dx > 0) {
            dx = 0;
        }
        double dist = Math.hypot(dx, dy);
        if (dist < deadZonePx) {
            return -1;
        }
        float finger = normalizeDeg((float) Math.toDegrees(Math.atan2(dy, dx)));
        float bestDiff = Float.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < n; i++) {
            float itemAngle = itemAngleDeg(isLeftEdge, arcDeg, n, i);
            float diff = Math.abs(wrapDeg(finger - itemAngle));
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        float tol = n <= 1 ? 30f : (arcDeg / n) * 0.75f;
        if (bestDiff > tol) {
            return -1;
        }
        return best;
    }

    static int applyHysteresis(int candidate, int current, float fx, float fy, float anchorX,
            float anchorY, boolean isLeftEdge, float arcDeg, int n) {
        if (candidate == current) {
            return current;
        }
        if (current < 0 || current >= n) {
            return candidate;
        }
        if (candidate < 0 || candidate >= n) {
            return candidate;
        }
        float finger = normalizeDeg((float) Math.toDegrees(
                Math.atan2(fy - anchorY, fx - anchorX)));
        float candAngle = itemAngleDeg(isLeftEdge, arcDeg, n, candidate);
        float curAngle = itemAngleDeg(isLeftEdge, arcDeg, n, current);
        float candDiff = Math.abs(wrapDeg(finger - candAngle));
        float curDiff = Math.abs(wrapDeg(finger - curAngle));
        if (curDiff - candDiff < HYSTERESIS_DEG) {
            return current;
        }
        return candidate;
    }

    static float normalizeDeg(float d) {
        float r = d % 360f;
        if (r < 0) {
            r += 360f;
        }
        return r;
    }

    static float wrapDeg(float d) {
        float r = d % 360f;
        if (r > 180f) {
            r -= 360f;
        }
        if (r < -180f) {
            r += 360f;
        }
        return r;
    }
}
