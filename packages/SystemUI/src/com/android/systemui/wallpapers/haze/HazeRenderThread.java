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

  private final SurfaceHolder mHolder;
  private Bitmap mBitmap;
  private final Context mContext;
  private Handler mHandler;
  private Choreographer mChoreographer;
  private Runnable mOnContextLost;

  private boolean mIsRunning = false;
  private boolean mVisible = true;
  private boolean mRenderRequested = true;

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
  private int mStyle = 0;
  private float mIntensity = 0.5f;
  private volatile int mCurrentState = -1;

  private boolean mNeedsReblur = false;
  private float mLastIntensity = -1f;

  private final Choreographer.FrameCallback mFrameCallback =
      new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
          if (!mIsRunning || !mVisible) return;
          renderFrame();
        }
      };

  public HazeRenderThread(
      Context ctx, SurfaceHolder holder, Bitmap bitmap, Runnable onContextLost) {
    super(TAG);
    mContext = ctx;
    mHolder = holder;
    mBitmap = bitmap;
    mOnContextLost = onContextLost;
    updateSettings();
  }

  @Override
  public synchronized void start() {
    mIsRunning = true;
    super.start();
  }

  @Override
  protected void onLooperPrepared() {
    mHandler = new Handler(getLooper());
    mChoreographer = Choreographer.getInstance();

    try {
      if (!initEGL()) {
        Log.e(TAG, "EGL initialization failed, surface might be invalid");
        quitSafely();
        return;
      }
      mRenderer = new HazeRenderer();
      mRenderer.init(
          mHolder.getSurfaceFrame().width(), mHolder.getSurfaceFrame().height(), mBitmap, mStyle, mIntensity);
      mBitmap = null;
      requestRender();
    } catch (Exception e) {
      Log.e(TAG, "GL Initialization Exception", e);
      quitSafely();
    }
  }

  public void setBitmap(Bitmap bitmap) {
    if (mHandler != null) {
      mHandler.post(
          () -> {
            if (mRenderer != null) {
              mRenderer.setBitmap(bitmap, mIntensity, mStyle);
              requestRender();
            }
          });
    }
  }

  public void onVisibilityChanged(boolean visible) {
    if (mHandler != null) {
      mHandler.post(
          () -> {
            mVisible = visible;
            if (visible) {
              requestRender();
            }
          });
    }
  }

  public void updateSettings() {
    if (mHandler != null) {
      mHandler.post(this::handleUpdateSettings);
    } else {
      handleUpdateSettings();
    }
  }

  private void handleUpdateSettings() {
    int oldStyle = mStyle;
    mStyle = Settings.System.getInt(mContext.getContentResolver(), "haze_style", 0);
    float rawIntensity =
        Settings.System.getInt(mContext.getContentResolver(), "haze_intensity", 50) / 100f;
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
    if (mHandler != null) {
      mHandler.post(() -> handleTriggerTransition(state));
    } else {
      handleTriggerTransition(state);
    }
  }

  private void handleTriggerTransition(int state) {
    if (mCurrentState == state) return;

    boolean forceSnap = (mCurrentState == -1);
    mCurrentState = state;

    float target = 0f;
    long duration = 2500L;

    switch (mStyle) {
      case 0:
        target = (state == 2) ? 1f : 0f;
        duration = 2500L;
        break;
      case 1:
        target = (state == 2) ? 0f : 1f;
        duration = 1500L;
        break;
      case 2:
        target = (state == 2) ? 1f : 0f;
        duration = 500L;
        break;
      case 3:
        target = (state == 2) ? 0f : 1f;
        duration = 500L;
        break;
      case 4:
        if (state == 0) {
          target = 1f;
          duration = 0L;
        } else if (state == 1) {
          mCurrentBlur = 1f;
          target = 0f;
          duration = 2000L;
        } else if (state == 2) {
          mCurrentBlur = 0f;
          target = 1f;
          duration = 2000L;
        }
        break;
    }

    if (forceSnap || state == 0 || duration == 0L) {
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
    mStartTime = SystemClock.uptimeMillis();
    mIsAnimating = true;
    requestRender();
  }

  public void requestRender() {
    mRenderRequested = true;
    if (mHandler != null && mChoreographer != null && mVisible) {
      if (Looper.myLooper() == mHandler.getLooper()) {
        mChoreographer.postFrameCallback(mFrameCallback);
      } else {
        mHandler.post(() -> mChoreographer.postFrameCallback(mFrameCallback));
      }
    }
  }

  public void resize(int width, int height) {
    if (mHandler != null) {
      mHandler.post(
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
    if (mHandler != null) {
      mHandler.post(
          () -> {
            if (mRenderer != null) {
              mRenderer.destroy();
              mRenderer = null;
            }
            releaseEGL();
          });
    }
    return super.quitSafely();
  }

  @Override
  public boolean quit() {
    return quitSafely();
  }

  private void renderFrame() {
    if (mEglDisplay == null || mEglSurface == null || mRenderer == null) return;

    if (!mIsAnimating && !mRenderRequested && !mNeedsReblur) {
      return;
    }

    boolean needsNextFrame = false;

    if (mIsAnimating) {
      long now = SystemClock.uptimeMillis();
      float t = (float) (now - mStartTime) / mDuration;
      if (t >= 1f) {
        t = 1f;
        mIsAnimating = false;
        if (mCurrentState == 1 && (mStyle == 0 || mStyle == 1 || mStyle == 4)) {
          mRenderer.reRollTargets();
        }
      }

          float smoothT;
          if (mStyle == 4 && mCurrentState == 2) {
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

    mRenderer.drawFrame(mCurrentBlur, mIntensity, mStyle);
    boolean swapSuccess = EGL14.eglSwapBuffers(mEglDisplay, mEglSurface);

    if (!swapSuccess) {
      int error = EGL14.eglGetError();
      Log.w(TAG, "EGL swap failed: " + error);
      if (error == EGL14.EGL_CONTEXT_LOST
          || error == EGL14.EGL_BAD_SURFACE
          || error == EGL14.EGL_BAD_DISPLAY) {
        if (mOnContextLost != null) mOnContextLost.run();
        return;
      }
    }

    if (needsNextFrame && mIsRunning && mVisible) {
      mChoreographer.postFrameCallback(mFrameCallback);
    }
  }

  private boolean initEGL() {
    if (mHolder == null || mHolder.getSurface() == null || !mHolder.getSurface().isValid()) {
      return false;
    }

    mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");

    int[] version = new int[2];
    if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1))
      throw new RuntimeException("eglInitialize failed");

    int[] configAttribs = {
      EGL14.EGL_RENDERABLE_TYPE, 0x00000040,
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_NONE
    };

    EGLConfig[] configs = new EGLConfig[1];
    int[] numConfigs = new int[1];
    if (!EGL14.eglChooseConfig(mEglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
      throw new RuntimeException("eglChooseConfig failed");
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
