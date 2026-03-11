/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.systemui.wallpapers;

import static android.app.WallpaperManager.FLAG_LOCK;
import static android.app.WallpaperManager.FLAG_SYSTEM;
import static android.app.WallpaperManager.SetWallpaperFlags;

import android.annotation.Nullable;
import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.Settings;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import androidx.annotation.NonNull;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.LongRunning;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.utils.windowmanager.WindowManagerProvider;
import com.android.systemui.wallpapers.haze.HazeRenderThread;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import javax.inject.Inject;

@SuppressWarnings({"UnusedDeclaration"})
public class ImageWallpaper extends WallpaperService {

  private static final String TAG = ImageWallpaper.class.getSimpleName();
  private static final boolean DEBUG = false;

  private volatile int mPages = 1;
  private boolean mPagesComputed = false;

  private final UserTracker mUserTracker;
  private final WindowManagerProvider mWindowManagerProvider;
  private HandlerThread mWorker;
  @LongRunning private final DelayableExecutor mLongExecutor;

  private static final int DELAY_UNLOAD_BITMAP = 2000;

  @Inject
  public ImageWallpaper(
      @LongRunning DelayableExecutor longExecutor,
      UserTracker userTracker,
      WindowManagerProvider windowManagerProvider) {
    super();
    mLongExecutor = longExecutor;
    mUserTracker = userTracker;
    mWindowManagerProvider = windowManagerProvider;
  }

  @Override
  public Looper onProvideEngineLooper() {
    return mWorker != null ? mWorker.getLooper() : super.onProvideEngineLooper();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    mWorker = new HandlerThread(TAG);
    mWorker.start();
  }

  @Override
  public Engine onCreateEngine() {
    return new CanvasEngine();
  }

  class CanvasEngine extends WallpaperService.Engine implements DisplayListener {
    private WallpaperManager mWallpaperManager;
    private final ImageWallpaperColorExtractor mColorExtractor;
    private SurfaceHolder mSurfaceHolder;
    private boolean mDrawn = false;
    @VisibleForTesting static final int MIN_SURFACE_WIDTH = 128;
    @VisibleForTesting static final int MIN_SURFACE_HEIGHT = 128;
    private Bitmap mBitmap;
    private boolean mWideColorGamut = false;
    private int mBitmapUsages = 0;

    private final Object mLock = new Object();
    private final Object mSurfaceLock = new Object();

    private static final String KEY_HAZE_ENABLED = "haze_enabled";
    private static final String KEY_HAZE_STYLE = "haze_style";
    private static final String KEY_HAZE_INTENSITY = "haze_intensity";

    private static final int STATE_SCREEN_OFF = 0;
    private static final int STATE_KEYGUARD = 1;
    private static final int STATE_UNLOCKED = 2;

    private HazeRenderThread mHazeThread;
    private boolean mIsHazeEnabled;

    private final ContentObserver mHazeObserver =
        new ContentObserver(new Handler(Looper.getMainLooper())) {
          @Override
          public void onChange(boolean selfChange) {
            updateHazeState();
          }
        };

    private final BroadcastReceiver mStateReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(Context context, Intent intent) {
            if (mHazeThread != null) {
              String action = intent.getAction();
              if (Intent.ACTION_USER_PRESENT.equals(action)) {
                mHazeThread.triggerTransition(STATE_UNLOCKED);
              } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mHazeThread.triggerTransition(STATE_SCREEN_OFF);
              }
            }
          }
        };

    CanvasEngine() {
      super();
      setFixedSizeAllowed(true);
      setShowForAllUsers(true);
      mColorExtractor =
          new ImageWallpaperColorExtractor(
              mLongExecutor,
              mLock,
              new ImageWallpaperColorExtractor.ImageWallpaperColorExtractorCallback() {
                @Override
                public void onColorsProcessed() {
                  CanvasEngine.this.notifyColorsChanged();
                }

                @Override
                public void onColorsProcessed(List<RectF> regions, List<WallpaperColors> colors) {
                  CanvasEngine.this.onColorsProcessed(regions, colors);
                }

                @Override
                public void onMiniBitmapUpdated() {
                  CanvasEngine.this.onMiniBitmapUpdated();
                }

                @Override
                public void onActivated() {
                  setOffsetNotificationsEnabled(true);
                }

                @Override
                public void onDeactivated() {
                  setOffsetNotificationsEnabled(false);
                }
              });

      if (mPagesComputed) {
        mColorExtractor.onPageChanged(mPages);
      }
    }

    @Override
    public void onCreate(SurfaceHolder surfaceHolder) {
      Trace.beginSection("ImageWallpaper.CanvasEngine#onCreate");
      mWallpaperManager = getDisplayContext().getSystemService(WallpaperManager.class);
      mSurfaceHolder = surfaceHolder;
      Rect dimensions =
          mWallpaperManager.peekBitmapDimensionsAsUser(
              getSourceFlag(), true, mUserTracker.getUserId());
      int width = Math.max(MIN_SURFACE_WIDTH, dimensions.width());
      int height = Math.max(MIN_SURFACE_HEIGHT, dimensions.height());
      mSurfaceHolder.setFixedSize(width, height);

      getDisplayContext()
          .getSystemService(DisplayManager.class)
          .registerDisplayListener(this, null);
      getDisplaySizeAndUpdateColorExtractor();

      getDisplayContext()
          .getContentResolver()
          .registerContentObserver(
              Settings.System.getUriFor(KEY_HAZE_ENABLED), false, mHazeObserver);
      getDisplayContext()
          .getContentResolver()
          .registerContentObserver(Settings.System.getUriFor(KEY_HAZE_STYLE), false, mHazeObserver);
      getDisplayContext()
          .getContentResolver()
          .registerContentObserver(
              Settings.System.getUriFor(KEY_HAZE_INTENSITY), false, mHazeObserver);

      IntentFilter filter = new IntentFilter();
      filter.addAction(Intent.ACTION_USER_PRESENT);
      filter.addAction(Intent.ACTION_SCREEN_OFF);
      getDisplayContext().registerReceiver(mStateReceiver, filter);

      updateHazeState();
      Trace.endSection();
    }

    private void updateHazeState() {
      boolean wasHazeEnabled = mIsHazeEnabled;
      mIsHazeEnabled =
          Settings.System.getInt(getDisplayContext().getContentResolver(), KEY_HAZE_ENABLED, 0)
              == 1;

      if (wasHazeEnabled != mIsHazeEnabled) {
        synchronized (mLock) {
          mDrawn = false;
        }
        if (mSurfaceHolder != null && mSurfaceHolder.getSurface().isValid()) {
          drawFrame();
        }
      } else if (mIsHazeEnabled && mHazeThread != null) {
        mHazeThread.updateSettings();
        mHazeThread.requestRender();
      }
    }

    @Override
    public void onVisibilityChanged(boolean visible) {
      super.onVisibilityChanged(visible);
      if (mHazeThread != null) {
        mHazeThread.onVisibilityChanged(visible);
        if (mIsHazeEnabled && visible) {
          KeyguardManager km = getDisplayContext().getSystemService(KeyguardManager.class);
          boolean isLocked = km != null && km.isKeyguardLocked();
          mHazeThread.triggerTransition(isLocked ? STATE_KEYGUARD : STATE_UNLOCKED);
        }
      }
    }

    @Override
    public void onDestroy() {
      Context context = getDisplayContext();
      if (context != null) {
        DisplayManager displayManager = context.getSystemService(DisplayManager.class);
        if (displayManager != null) displayManager.unregisterDisplayListener(this);
      }
      mColorExtractor.cleanUp();
      getDisplayContext().getContentResolver().unregisterContentObserver(mHazeObserver);
      try {
        getDisplayContext().unregisterReceiver(mStateReceiver);
      } catch (IllegalArgumentException e) {
        Log.w(TAG, "Receiver not registered", e);
      }
      if (mHazeThread != null) {
        mHazeThread.quitSafely();
        try {
          mHazeThread.join(500);
        } catch (InterruptedException e) {
          Log.e(TAG, "Interrupted waiting for Haze thread to die");
        }
        mHazeThread = null;
      }
    }

    @Override
    public boolean shouldZoomOutWallpaper() {
      return true;
    }

    @Override
    public boolean shouldWaitForEngineShown() {
      return true;
    }

    @Override
    public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
      if (mHazeThread != null) {
        mHazeThread.resize(width, height);
      }
    }

    @Override
    public void onSurfaceDestroyed(SurfaceHolder holder) {
      synchronized (mSurfaceLock) {
        mSurfaceHolder = null;
      }
      if (mHazeThread != null) {
        mHazeThread.quitSafely();
        try {
          mHazeThread.join(250);
        } catch (InterruptedException e) {
          Log.e(TAG, "Interrupted waiting for Haze thread to die");
        }
        mHazeThread = null;
      }
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {}

    @Override
    public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
      drawFrame();
    }

    private void drawFrame() {
      mLongExecutor.execute(this::drawFrameSynchronized);
    }

    private void drawFrameSynchronized() {
      synchronized (mLock) {
        if (mDrawn) return;
        drawFrameInternal();
      }
    }

    private void drawFrameInternal() {
      if (!isBitmapLoaded()) {
        loadWallpaperAndDrawFrameInternal();
      } else {
        synchronized (mSurfaceLock) {
          if (mSurfaceHolder == null) return;
          mBitmapUsages++;
          drawFrameOnCanvas(mBitmap);
          reportEngineShown(false);
          unloadBitmapIfNotUsedInternal();
        }
      }
    }

    // EGL Context recovery flow
    private void handleContextLost() {
      mLongExecutor.execute(
          () -> {
            Log.i(TAG, "Re-initializing HazeRenderThread after Context Loss");
            if (mHazeThread != null) {
              mHazeThread.quitSafely();
              mHazeThread = null;
            }
            synchronized (mLock) {
              mDrawn = false;
            }
            drawFrame();
          });
    }

    @VisibleForTesting
    void drawFrameOnCanvas(Bitmap bitmap) {
      Trace.beginSection("ImageWallpaper.CanvasEngine#drawFrame");

      if (mIsHazeEnabled) {
        if (bitmap != null && mSurfaceHolder != null && mSurfaceHolder.getSurface().isValid()) {
          if (mHazeThread == null) {
            mHazeThread =
                new HazeRenderThread(
                    getDisplayContext(), mSurfaceHolder, bitmap, this::handleContextLost);
            mHazeThread.start();

            KeyguardManager km = getDisplayContext().getSystemService(KeyguardManager.class);
            boolean isLocked = km != null && km.isKeyguardLocked();
            mHazeThread.triggerTransition(isLocked ? STATE_KEYGUARD : STATE_UNLOCKED);
          } else {
            mHazeThread.setBitmap(bitmap);
          }
          mDrawn = true;
        }
        Trace.endSection();
        return;
      }

      if (mHazeThread != null) {
        mHazeThread.quitSafely();
        try {
          mHazeThread.join(250);
        } catch (InterruptedException e) {
          Log.e(TAG, "Interrupted waiting for Haze thread to die");
        }
        mHazeThread = null;
      }

      Surface surface = mSurfaceHolder.getSurface();
      Canvas canvas = null;
      try {
        canvas =
            mWideColorGamut
                ? surface.lockHardwareWideColorGamutCanvas()
                : surface.lockHardwareCanvas();
      } catch (IllegalStateException e) {
        Log.w(TAG, "Unable to lock canvas", e);
      }

      if (canvas != null) {
        Rect dest = mSurfaceHolder.getSurfaceFrame();
        try {
          int blurType = SystemProperties.getInt("persist.sys.wallpaper.blur_enabled", 0);
          if (blurType == 1
              || (blurType == 2 && isLockScreenWallpaper())
              || (blurType == 3 && !isLockScreenWallpaper())) {
            bitmap =
                WallpaperUtils.getBlurredBitmap(
                    bitmap, blurType == 1 ? 200 : 25, getDisplayContext());
          }
          int dimType = SystemProperties.getInt("persist.sys.wallpaper.dim_enabled", 0);
          if (dimType == 1
              || (dimType == 2 && isLockScreenWallpaper())
              || (dimType == 3 && !isLockScreenWallpaper())) {
            bitmap =
                WallpaperUtils.getDimmedBitmap(
                    bitmap, SystemProperties.getInt("persist.sys.wallpaper.dim_level", 10));
          }
          canvas.drawBitmap(bitmap, null, dest, null);
          mDrawn = true;
        } finally {
          surface.unlockCanvasAndPost(canvas);
        }
      }
      Trace.endSection();
    }

    private boolean isLockScreenWallpaper() {
      return (this.getWallpaperFlags() & FLAG_LOCK) == FLAG_LOCK;
    }

    @VisibleForTesting
    boolean isBitmapLoaded() {
      return mBitmap != null && !mBitmap.isRecycled();
    }

    private void unloadBitmapIfNotUsed() {
      mLongExecutor.execute(this::unloadBitmapIfNotUsedSynchronized);
    }

    private void unloadBitmapIfNotUsedSynchronized() {
      synchronized (mLock) {
        unloadBitmapIfNotUsedInternal();
      }
    }

    private void unloadBitmapIfNotUsedInternal() {
      mBitmapUsages -= 1;
      if (mBitmapUsages <= 0) {
        mBitmapUsages = 0;
        unloadBitmapInternal();
      }
    }

    private void unloadBitmapInternal() {
      if (mBitmap != null) mBitmap.recycle();
      mBitmap = null;
      synchronized (mSurfaceLock) {
        if (mSurfaceHolder != null) mSurfaceHolder.getSurface().hwuiDestroy();
      }
      mWallpaperManager.forgetLoadedWallpaper();
    }

    private void loadWallpaperAndDrawFrameInternal() {
      boolean loadSuccess = false;
      Bitmap bitmap;
      try {
        bitmap =
            mWallpaperManager.getBitmapAsUser(
                mUserTracker.getUserId(), false, getSourceFlag(), true);
        if (bitmap != null && bitmap.getByteCount() > RecordingCanvas.MAX_BITMAP_SIZE) {
          throw new RuntimeException("Wallpaper is too large to draw!");
        }
      } catch (RuntimeException | OutOfMemoryError exception) {
        mWallpaperManager.clearWallpaper(getWallpaperFlags(), mUserTracker.getUserId());
        try {
          bitmap =
              mWallpaperManager.getBitmapAsUser(
                  mUserTracker.getUserId(), false, getSourceFlag(), true);
        } catch (RuntimeException | OutOfMemoryError e) {
          bitmap = null;
        }
      }

      if (bitmap != null && !bitmap.isRecycled() && mBitmap != bitmap) {
        loadSuccess = true;
        if (mBitmap != null) mBitmap.recycle();
        mBitmap = bitmap;
        mWideColorGamut = mWallpaperManager.wallpaperSupportsWcg(getSourceFlag());
        mBitmapUsages += 2;
        recomputeColorExtractorMiniBitmap();
        drawFrameInternal();
        mLongExecutor.executeDelayed(this::unloadBitmapIfNotUsedSynchronized, DELAY_UNLOAD_BITMAP);
      }
      if (!loadSuccess) reportEngineShown(false);
    }

    private void onColorsProcessed(List<RectF> regions, List<WallpaperColors> colors) {
      try {
        notifyLocalColorsChanged(regions, colors);
      } catch (RuntimeException e) {
        Log.e(TAG, e.getMessage(), e);
      }
    }

    private @SetWallpaperFlags int getSourceFlag() {
      return getWallpaperFlags() == FLAG_LOCK ? FLAG_LOCK : FLAG_SYSTEM;
    }

    @VisibleForTesting
    void recomputeColorExtractorMiniBitmap() {
      mColorExtractor.onBitmapChanged(mBitmap);
    }

    @VisibleForTesting
    void onMiniBitmapUpdated() {
      unloadBitmapIfNotUsed();
    }

    @Override
    public @Nullable WallpaperColors onComputeColors() {
      return mColorExtractor.onComputeColors();
    }

    @Override
    public boolean supportsLocalColorExtraction() {
      return true;
    }

    @Override
    public void addLocalColorsAreas(@NonNull List<RectF> regions) {
      mColorExtractor.addLocalColorsAreas(regions);
    }

    @Override
    public void removeLocalColorsAreas(@NonNull List<RectF> regions) {
      mColorExtractor.removeLocalColorAreas(regions);
    }

    @Override
    public void onOffsetsChanged(
        float xOffset,
        float yOffset,
        float xOffsetStep,
        float yOffsetStep,
        int xPixelOffset,
        int yPixelOffset) {
      final int pages = (xOffsetStep > 0 && xOffsetStep <= 1) ? Math.round(1 / xOffsetStep) + 1 : 1;
      if (pages != mPages || !mPagesComputed) {
        mPages = pages;
        mPagesComputed = true;
        mColorExtractor.onPageChanged(mPages);
      }
    }

    @Override
    public void onDimAmountChanged(float dimAmount) {
      mColorExtractor.onDimAmountChanged(dimAmount);
    }

    @Override
    public void onDisplayAdded(int displayId) {}

    @Override
    public void onDisplayRemoved(int displayId) {}

    @Override
    public void onDisplayChanged(int displayId) {
      if (displayId == getDisplayContext().getDisplayId()) {
        getDisplaySizeAndUpdateColorExtractor();
      }
    }

    private void getDisplaySizeAndUpdateColorExtractor() {
      Rect window =
          mWindowManagerProvider
              .getWindowManager(getDisplayContext())
              .getCurrentWindowMetrics()
              .getBounds();
      mColorExtractor.setDisplayDimensions(window.width(), window.height());
    }

    @Override
    protected void dump(String prefix, FileDescriptor fd, PrintWriter out, String[] args) {
      super.dump(prefix, fd, out, args);
      mColorExtractor.dump(prefix, fd, out, args);
    }
  }
}
