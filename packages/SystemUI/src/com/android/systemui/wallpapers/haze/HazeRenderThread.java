/*
 * Copyright 2026 (C) VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.wallpapers.haze;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceHolder;

public class HazeRenderThread extends HandlerThread {
  private static final String TAG = "HazeRenderThread";

  public interface Callbacks {
    void onContextLost();

    void onUnsupported();

    void onBitmapConsumed();
  }

  public static final int STATE_SCREEN_OFF = 0;
  public static final int STATE_KEYGUARD = 1;
  public static final int STATE_UNLOCKED = 2;

  private static final long AMBIENT_FRAME_INTERVAL_MS = 50L;
  private static final long TIME_WRAP_MS = 3600000L;

  private static final float[][] STYLE_TARGETS = {
    {0f, 0f, 1f},
    {1f, 1f, 0f},
    {0f, 0f, 1f},
    {1f, 1f, 0f},
    {1f, 0f, 1f},
  };
  private static final long[] STYLE_DURATIONS = {2500L, 1500L, 500L, 500L, 2000L};

  private final SurfaceHolder mHolder;
  private final Context mContext;
  private final Callbacks mCallbacks;
  private Bitmap mBitmap;
  private Bitmap mMiniBitmap;
  private volatile Handler mHandler;
  private Choreographer mChoreographer;

  private volatile boolean mIsRunning = false;
  private volatile boolean mVisible = true;
  private volatile boolean mRenderRequested = true;

  private EGLDisplay mEglDisplay;
  private EGLContext mEglContext;
  private EGLSurface mEglSurface;
  private HazeRenderer mRenderer;

  private volatile boolean mIsAnimating = false;
  private long mStartTime;
  private long mDuration;
  private volatile float mStartBlur;
  private volatile float mTargetBlur;

  private volatile float mCurrentBlur = 0f;
  private volatile int mStyle = 0;
  private volatile float mIntensity = 0.5f;
  private volatile int mCurrentState = -1;

  private boolean mNeedsReblur = false;
  private float mLastIntensity = -1f;
  private long mLastDrawTime = 0L;

  private final Choreographer.FrameCallback mFrameCallback =
      new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          if (!mIsRunning || !mVisible) return;
          renderFrame();
        }
      };

  public HazeRenderThread(
      Context ctx, SurfaceHolder holder, Bitmap bitmap, Bitmap miniBitmap, Callbacks callbacks) {
    super(TAG);
    mContext = ctx;
    mHolder = holder;
    mBitmap = bitmap;
    mMiniBitmap = miniBitmap;
    mCallbacks = callbacks;
    updateSettings();
  }

  @Override
  public synchronized void start() {
    mIsRunning = true;
    super.start();
  }

  @Override
  protected void onLooperPrepared() {
    if (!mIsRunning) {
      getLooper().quitSafely();
      return;
    }
    mHandler = new Handler(getLooper());
    mChoreographer = Choreographer.getInstance();
    try {
      if (!initEGL()) {
        Log.w(TAG, "Surface invalid during EGL init");
        quitSafely();
        mCallbacks.onContextLost();
        return;
      }
      mRenderer = new HazeRenderer();
      mRenderer.init(
          mHolder.getSurfaceFrame().width(),
          mHolder.getSurfaceFrame().height(),
          mBitmap,
          mMiniBitmap,
          mStyle,
          mIntensity);
      mBitmap = null;
      mMiniBitmap = null;
      mCallbacks.onBitmapConsumed();
      requestRender();
    } catch (RuntimeException e) {
      Log.e(TAG, "GL initialization failed, disabling haze", e);
      quitSafely();
      mCallbacks.onUnsupported();
    }
  }

  @Override
  public void run() {
    try {
      super.run();
    } finally {
      if (mBitmap != null) {
        mBitmap = null;
        mCallbacks.onBitmapConsumed();
      }
      if (mMiniBitmap != null) {
        mMiniBitmap.recycle();
        mMiniBitmap = null;
      }
      if (mRenderer != null) {
        mRenderer.destroy();
        mRenderer = null;
      }
      releaseEGL();
    }
  }

  public void setBitmap(Bitmap bitmap) {
    Handler handler = mHandler;
    boolean posted =
        handler != null
            && handler.post(
                () -> {
                  if (mRenderer != null) {
                    mRenderer.setBitmap(bitmap, mIntensity, mStyle);
                    requestRender();
                  }
                  mCallbacks.onBitmapConsumed();
                });
    if (!posted) {
      mCallbacks.onBitmapConsumed();
    }
  }

  public void setMiniBitmap(Bitmap miniBitmap) {
    if (miniBitmap == null) return;
    Handler handler = mHandler;
    boolean posted =
        handler != null
            && handler.post(
                () -> {
                  if (mRenderer != null) {
                    mRenderer.setMiniBitmap(miniBitmap);
                    requestRender();
                  } else {
                    miniBitmap.recycle();
                  }
                });
    if (!posted) {
      miniBitmap.recycle();
    }
  }

  public void onVisibilityChanged(boolean visible) {
    mVisible = visible;
    if (visible) {
      requestRender();
    }
  }

  public void updateSettings() {
    Handler handler = mHandler;
    if (handler != null) {
      handler.post(this::handleUpdateSettings);
    } else {
      handleUpdateSettings();
    }
  }

  private void handleUpdateSettings() {
    int oldStyle = mStyle;
    mStyle = Settings.System.getInt(mContext.getContentResolver(), HazeSettings.KEY_STYLE, 0);
    float rawIntensity =
        Settings.System.getInt(mContext.getContentResolver(), HazeSettings.KEY_INTENSITY, 50)
            / 100f;
    float newIntensity = rawIntensity * 0.55f;

    if (mLastIntensity != -1f
        && (oldStyle != mStyle || Math.abs(mLastIntensity - newIntensity) > 0.01f)) {
      mNeedsReblur = true;
    }
    mIntensity = newIntensity;
    mLastIntensity = mIntensity;
    requestRender();
  }

  public void triggerTransition(int state) {
    Handler handler = mHandler;
    if (handler != null) {
      handler.post(() -> handleTriggerTransition(state));
    } else {
      handleTriggerTransition(state);
    }
  }

  private void handleTriggerTransition(int state) {
    if (mCurrentState == state) return;
    if (state < 0 || state > STATE_UNLOCKED) return;

    int previousState = mCurrentState;
    boolean forceSnap = (previousState == -1);
    mCurrentState = state;

    int style = (mStyle >= 0 && mStyle < STYLE_TARGETS.length) ? mStyle : 0;
    float target = STYLE_TARGETS[style][state];
    long duration = STYLE_DURATIONS[style];

    if (style == 4) {
      if (state == STATE_SCREEN_OFF) {
        duration = 0L;
      } else {
        mCurrentBlur = 1f - target;
      }
    } else if (state == STATE_KEYGUARD
        && target > 0f
        && (previousState == STATE_SCREEN_OFF || previousState == -1)) {
      mCurrentBlur = 0f;
      forceSnap = false;
    }

    if (forceSnap || state == STATE_SCREEN_OFF || duration == 0L) {
      mCurrentBlur = target;
      mIsAnimating = false;
      requestRender();
      return;
    }

    mStartBlur = mCurrentBlur;
    mTargetBlur = target;
    float distance = Math.abs(mTargetBlur - mStartBlur);
    mDuration = (long) (duration * distance);

    if (mDuration <= 16L) {
      mCurrentBlur = target;
      mIsAnimating = false;
      requestRender();
      return;
    }
    if (mRenderer != null
        && isBlobStyle(style)
        && mStartBlur <= 0.1f
        && mTargetBlur > mStartBlur) {
      mRenderer.reRollTargets();
    }
    mStartTime = SystemClock.uptimeMillis();
    mIsAnimating = true;
    requestRender();
  }

  public void requestRender() {
    mRenderRequested = true;
    Handler handler = mHandler;
    if (handler != null && mChoreographer != null && mVisible) {
      if (Looper.myLooper() == handler.getLooper()) {
        mChoreographer.postFrameCallback(mFrameCallback);
      } else {
        handler.post(() -> mChoreographer.postFrameCallback(mFrameCallback));
      }
    }
  }

  public void resize(int width, int height) {
    Handler handler = mHandler;
    if (handler != null) {
      handler.post(
          () -> {
            if (mRenderer != null) {
              mRenderer.resize(width, height);
              mNeedsReblur = true;
              requestRender();
            }
          });
    }
  }

  @Override
  public boolean quitSafely() {
    mIsRunning = false;
    return super.quitSafely();
  }

  @Override
  public boolean quit() {
    return quitSafely();
  }

  private static boolean isBlobStyle(int style) {
    return style == 0 || style == 1 || style == 4;
  }

  private boolean isAmbientActive() {
    return mVisible && !mIsAnimating && isBlobStyle(mStyle) && mCurrentBlur > 0.15f;
  }

  private void renderFrame() {
    if (mEglDisplay == null || mEglSurface == null || mRenderer == null) return;

    boolean ambient = isAmbientActive();
    long now = SystemClock.uptimeMillis();

    if (!mIsAnimating && !mRenderRequested && !mNeedsReblur) {
      if (!ambient) return;
      long sinceLastDraw = now - mLastDrawTime;
      if (sinceLastDraw < AMBIENT_FRAME_INTERVAL_MS) {
        mChoreographer.postFrameCallbackDelayed(
            mFrameCallback, AMBIENT_FRAME_INTERVAL_MS - sinceLastDraw);
        return;
      }
    }

    boolean needsNextFrame = ambient;

    if (mIsAnimating) {
      float t = (float) (now - mStartTime) / mDuration;
      if (t >= 1f) {
        t = 1f;
        mIsAnimating = false;
      }

      float smoothT;
      if (mStyle == 4 && mCurrentState == STATE_UNLOCKED) {
        float invT = 1.0f - t;
        smoothT = 1.0f - (invT * invT * invT);
      } else {
        smoothT = t * t * (3.0f - 2.0f * t);
      }
      mCurrentBlur = mStartBlur + (mTargetBlur - mStartBlur) * smoothT;
      needsNextFrame = true;
    }

    if (mRenderRequested) {
      needsNextFrame = true;
      mRenderRequested = false;
    }

    if (mNeedsReblur) {
      mRenderer.updateBlur(mIntensity, mStyle);
      mNeedsReblur = false;
      needsNextFrame = true;
    }

    mRenderer.drawFrame(mCurrentBlur, mIntensity, mStyle, (now % TIME_WRAP_MS) / 1000f);
    mLastDrawTime = now;
    boolean swapSuccess = EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);

    if (!swapSuccess) {
      int error = EGL14.eglGetError();
      Log.w(TAG, "EGL swap failed: " + error);
      if (error == EGL14.EGL_CONTEXT_LOST
          || error == EGL14.EGL_BAD_SURFACE
          || error == EGL14.EGL_BAD_DISPLAY) {
        mCallbacks.onContextLost();
        return;
      }
    }

    if (needsNextFrame && mIsRunning && mVisible) {
      if (mIsAnimating || mRenderRequested || mNeedsReblur) {
        mChoreographer.postFrameCallback(mFrameCallback);
      } else {
        mChoreographer.postFrameCallbackDelayed(mFrameCallback, AMBIENT_FRAME_INTERVAL_MS);
      }
    }
  }

  private boolean initEGL() {
    if (mHolder == null || mHolder.getSurface() == null || !mHolder.getSurface().isValid()) {
      return false;
    }

    mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");

    int[] version = new int[2];
    if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
      throw new RuntimeException("eglInitialize failed");
    }

    int[] configAttribs = {
      EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_NONE
    };

    EGLConfig[] configs = new EGLConfig[1];
    int[] numConfigs = new int[1];
    if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        || numConfigs[0] <= 0
        || configs[0] == null) {
      throw new RuntimeException("No ES3 RGBA8888 window config");
    }

    int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
    mEglContext =
        EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
    if (mEglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");

    int[] surfaceAttribs = {EGL14.EGL_NONE};
    mEglSurface =
        EGL14.eglCreateWindowSurface(
            mEglDisplay, configs[0], mHolder.getSurface(), surfaceAttribs, 0);
    if (mEglSurface == EGL14.EGL_NO_SURFACE)
      throw new RuntimeException("eglCreateWindowSurface failed");

    if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
      throw new RuntimeException("eglMakeCurrent failed");
    }
    EGL14.eglSwapInterval(mEglDisplay, 1);
    return true;
  }

  private void releaseEGL() {
    if (mEglDisplay != null) {
      EGL14.eglMakeCurrent(
          mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (mEglSurface != null) {
        EGL14.eglDestroySurface(mEglDisplay, mEglSurface);
        mEglSurface = null;
      }
      if (mEglContext != null) {
        EGL14.eglDestroyContext(mEglDisplay, mEglContext);
        mEglContext = null;
      }
      EGL14.eglTerminate(mEglDisplay);
      mEglDisplay = null;
    }
  }
}
