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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;

import com.android.app.animation.Interpolators;

import java.util.ArrayList;
import java.util.List;

public class PieMenuView extends View {
    private List<PieItem> mItems = new ArrayList<>();
    private float mAnchorX;
    private float mAnchorY;
    private boolean mIsLeftEdge = true;
    private int mHoveredIndex = -1;
    private int mRadiusPx = 300;
    private int mIconSizePx = 110;
    private float mShowProgress;
    private float mHideProgress;
    private float mHoverFraction = 1f;
    private ValueAnimator mShowAnimator;
    private ValueAnimator mHideAnimator;
    private ValueAnimator mHoverAnimator;
    private final Paint mScrimPaint = new Paint();
    private final Paint mCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHoverCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlaceholderTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mIconBounds = new Rect();

    public PieMenuView(Context context) {
        super(context);
        mScrimPaint.setColor(Color.BLACK);
        mCirclePaint.setColor(Color.argb(235, 38, 40, 46));
        mHoverCirclePaint.setColor(Color.argb(255, 66, 70, 82));
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3,
                getResources().getDisplayMetrics()));
        mRingPaint.setColor(Color.WHITE);
        mTextPaint.setColor(Color.WHITE);
        mTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mPlaceholderTextPaint.setColor(Color.WHITE);
        mPlaceholderTextPaint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 20, getResources().getDisplayMetrics()));
        mPlaceholderTextPaint.setTextAlign(Paint.Align.CENTER);
        setAlpha(1f);
    }

    public void setItems(List<PieItem> items) {
        mItems = items == null ? new ArrayList<>() : new ArrayList<>(items);
        invalidate();
    }

    public void show(float anchorX, float anchorY, boolean isLeftEdge, int radiusPx,
            int iconSizePx) {
        mAnchorX = anchorX;
        mAnchorY = anchorY;
        mIsLeftEdge = isLeftEdge;
        mRadiusPx = radiusPx;
        mIconSizePx = iconSizePx;
        mHoveredIndex = -1;
        mHoverFraction = 1f;
        mHideProgress = 0f;
        cancelAnim(mHideAnimator);
        cancelAnim(mHoverAnimator);
        mShowProgress = 0f;
        cancelAnim(mShowAnimator);
        mShowAnimator = ValueAnimator.ofFloat(0f, 1f);
        mShowAnimator.setDuration(180);
        mShowAnimator.setInterpolator(Interpolators.EMPHASIZED_DECELERATE);
        mShowAnimator.addUpdateListener(a -> {
            mShowProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        mShowAnimator.start();
        invalidate();
    }

    public void setHoveredIndex(int index) {
        if (index == mHoveredIndex) {
            return;
        }
        mHoveredIndex = index;
        cancelAnim(mHoverAnimator);
        mHoverFraction = 0f;
        mHoverAnimator = ValueAnimator.ofFloat(0f, 1f);
        mHoverAnimator.setDuration(100);
        mHoverAnimator.setInterpolator(Interpolators.EMPHASIZED_DECELERATE);
        mHoverAnimator.addUpdateListener(a -> {
            mHoverFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        mHoverAnimator.start();
        invalidate();
    }

    public void hide(boolean launched, Runnable onDone) {
        cancelAnim(mShowAnimator);
        cancelAnim(mHoverAnimator);
        mHideAnimator = ValueAnimator.ofFloat(0f, 1f);
        mHideAnimator.setDuration(launched ? 120 : 150);
        mHideAnimator.setInterpolator(Interpolators.EMPHASIZED_DECELERATE);
        mHideAnimator.addUpdateListener(a -> {
            mHideProgress = (float) a.getAnimatedValue();
            invalidate();
        });
        mHideAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (onDone != null) {
                    onDone.run();
                }
            }
        });
        mHideAnimator.start();
    }

    private void cancelAnim(ValueAnimator anim) {
        if (anim != null) {
            anim.cancel();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = mItems.size();
        if (n == 0) {
            return;
        }
        float show = mShowProgress * (1f - mHideProgress);
        mScrimPaint.setAlpha((int) (102f * show));
        canvas.drawRect(0, 0, getWidth(), getHeight(), mScrimPaint);
        float staggerMs = 24f;
        float totalMs = 180f;
        for (int i = 0; i < n; i++) {
            float start = (i * staggerMs) / totalMs;
            float itemT = (show - start) / (1f - start);
            if (itemT < 0f) {
                itemT = 0f;
            }
            if (itemT > 1f) {
                itemT = 1f;
            }
            PointF c = PieMenuController.computeCenter(mAnchorX, mAnchorY, mIsLeftEdge,
                    mRadiusPx, PieMenuController.ARC_DEGREES, n, i);
            float scale = 0.6f + 0.4f * itemT;
            float alpha = itemT;
            boolean hovered = i == mHoveredIndex;
            if (hovered) {
                float hs = 1f + 0.15f * mHoverFraction;
                scale *= hs;
            }
            if (mHideProgress > 0f) {
                alpha *= (1f - mHideProgress);
                if (!hovered) {
                    scale *= (1f - 0.4f * mHideProgress);
                } else {
                    scale *= (1f + 0.15f * mHideProgress);
                }
            }
            float half = (mIconSizePx / 2f) * scale;
            if (alpha <= 0.01f) {
                continue;
            }
            Paint fill = hovered ? mHoverCirclePaint : mCirclePaint;
            int prevAlpha = fill.getAlpha();
            fill.setAlpha((int) (prevAlpha * alpha));
            canvas.drawCircle(c.x, c.y, half + 18f * scale, fill);
            fill.setAlpha(prevAlpha);
            if (hovered) {
                int prevRing = mRingPaint.getAlpha();
                mRingPaint.setAlpha((int) (255 * alpha));
                canvas.drawCircle(c.x, c.y, half + 18f * scale, mRingPaint);
                mRingPaint.setAlpha(prevRing);
            }
            PieItem item = mItems.get(i);
            Drawable icon = item.icon;
            if (icon != null) {
                mIconBounds.set((int) (c.x - half), (int) (c.y - half),
                        (int) (c.x + half), (int) (c.y + half));
                Drawable draw = icon.mutate();
                draw.setBounds(mIconBounds);
                draw.setAlpha((int) (255 * alpha));
                draw.draw(canvas);
            } else {
                int prevT = mPlaceholderTextPaint.getAlpha();
                mPlaceholderTextPaint.setAlpha((int) (255 * alpha));
                String s = item.label.isEmpty() ? "?" : item.label.substring(0, 1).toUpperCase();
                canvas.drawText(s, c.x, c.y - (mPlaceholderTextPaint.descent()
                        + mPlaceholderTextPaint.ascent()) / 2f, mPlaceholderTextPaint);
                mPlaceholderTextPaint.setAlpha(prevT);
            }
            if (hovered && !item.label.isEmpty()) {
                int prevTx = mTextPaint.getAlpha();
                mTextPaint.setAlpha((int) (255 * alpha));
                canvas.drawText(item.label, c.x, c.y + half + 52f, mTextPaint);
                mTextPaint.setAlpha(prevTx);
            }
        }
    }
}
