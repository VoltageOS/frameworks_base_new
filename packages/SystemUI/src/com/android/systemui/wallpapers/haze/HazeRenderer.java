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

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class HazeRenderer {
  private static final String TAG = "HazeRenderer";

  private static class BlobPhysics {
    float startX, startY, p1x, p1y, endX, endY, startSize, endSize, massScale;

    BlobPhysics(float sx, float sy, float ms) {
      startX = sx;
      startY = sy;
      massScale = ms;
    }
  }

  private int mProgramId;
  private int mKawaseDownProgramId;
  private int mKawaseUpProgramId;

  private int mSharpTextureId;
  private int mBlurTextureId;

  private int mVaoScreenId;
  private int mVaoFboId;

  private static final int MAX_KAWASE_PASSES = 6;
  private int[] mKawaseFbos = new int[MAX_KAWASE_PASSES];
  private int[] mKawaseTextures = new int[MAX_KAWASE_PASSES];

  private float mAspectRatio = 1.0f;

  private FloatBuffer mVertexBufferScreen;
  private FloatBuffer mVertexBufferFbo;

  private int mWidth;
  private int mHeight;

  private List<BlobPhysics> mBlobs = new ArrayList<>();
  private Random mRandom = new Random();

  private float[] mBlobColorsBuffer = new float[16 * 3];
  private float[] mBlobPosBuffer = new float[16 * 2];
  private float[] mBlobSizesBuffer = new float[16];
  private int mBlobCount = 0;

  private boolean mBlobsDirty = true;
  private float mLastPhysicsT = -1f;
  private int mLastStyle = -1;

  private int uBlobColorsLoc, uBlobPositionsLoc, uBlobSizesLoc, uBlobCountLoc;
  private int uAspectRatioLoc, uBlurStrengthLoc, uDimLevelLoc, uStyleLoc;
  private int uTextureSharpLoc, uTextureBlurLoc;
  private int uDownTextureLoc, uDownResolutionLoc, uDownOffsetLoc;
  private int uUpTextureLoc, uUpResolutionLoc, uUpOffsetLoc;

  public void init(int width, int height, Bitmap originalBitmap, int style, float intensity) {
    mWidth = width;
    mHeight = height;
    mAspectRatio = (float) width / height;

    float[] screenVerts = {-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f};
    mVertexBufferScreen =
        ByteBuffer.allocateDirect(screenVerts.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
    mVertexBufferScreen.put(screenVerts).position(0);

    float[] fboVerts = {-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f};
    mVertexBufferFbo =
        ByteBuffer.allocateDirect(fboVerts.length * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
    mVertexBufferFbo.put(fboVerts).position(0);

    mProgramId = createProgram(HazeShaders.VERTEX_SHADER, HazeShaders.HAZE_FRAGMENT_SHADER);
    mKawaseDownProgramId =
        createProgram(HazeShaders.VERTEX_SHADER, HazeShaders.KAWASE_DOWN_FRAGMENT_SHADER);
    mKawaseUpProgramId =
        createProgram(HazeShaders.VERTEX_SHADER, HazeShaders.KAWASE_UP_FRAGMENT_SHADER);

    cacheUniforms();
    setupVAOs();

    GLES30.glGenFramebuffers(MAX_KAWASE_PASSES, mKawaseFbos, 0);
    allocateKawaseBuffers(mWidth, mHeight);

    mSharpTextureId = uploadTexture(originalBitmap);
    updateBlur(intensity, style);

    if (originalBitmap != null && !originalBitmap.isRecycled()) {
      initBaseBlobs(originalBitmap);
    }

    GLES30.glViewport(0, 0, width, height);
  }

  public void setBitmap(Bitmap originalBitmap, float intensity, int style) {
    if (mSharpTextureId != 0) {
      GLES30.glDeleteTextures(1, new int[] {mSharpTextureId}, 0);
    }
    mSharpTextureId = uploadTexture(originalBitmap);
    updateBlur(intensity, style);

    if (originalBitmap != null && !originalBitmap.isRecycled()) {
      initBaseBlobs(originalBitmap);
    }
  }

  private void allocateKawaseBuffers(int w, int h) {
    if (mKawaseTextures[0] != 0) {
      GLES30.glDeleteTextures(MAX_KAWASE_PASSES, mKawaseTextures, 0);
    }
    for (int i = 0; i < MAX_KAWASE_PASSES; i++) {
      int downW = Math.max(32, w >> (i + 1));
      int downH = Math.max(32, h >> (i + 1));
      mKawaseTextures[i] = createEmptyTexture(downW, downH);
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mKawaseFbos[i]);
      GLES30.glFramebufferTexture2D(
          GLES30.GL_FRAMEBUFFER,
          GLES30.GL_COLOR_ATTACHMENT0,
          GLES30.GL_TEXTURE_2D,
          mKawaseTextures[i],
          0);
    }
    mBlurTextureId = mKawaseTextures[0];
  }

  private void setupVAOs() {
    int[] vaos = new int[2];
    GLES30.glGenVertexArrays(2, vaos, 0);
    mVaoScreenId = vaos[0];
    mVaoFboId = vaos[1];

    int[] vbos = new int[2];
    GLES30.glGenBuffers(2, vbos, 0);

    GLES30.glBindVertexArray(mVaoScreenId);
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0]);
    GLES30.glBufferData(
        GLES30.GL_ARRAY_BUFFER,
        mVertexBufferScreen.capacity() * 4,
        mVertexBufferScreen,
        GLES30.GL_STATIC_DRAW);
    int aPosLoc = GLES30.glGetAttribLocation(mProgramId, "aPosition");
    GLES30.glVertexAttribPointer(aPosLoc, 2, GLES30.GL_FLOAT, false, 16, 0);
    GLES30.glEnableVertexAttribArray(aPosLoc);
    int aTexLoc = GLES30.glGetAttribLocation(mProgramId, "aTexCoord");
    GLES30.glVertexAttribPointer(aTexLoc, 2, GLES30.GL_FLOAT, false, 16, 8);
    GLES30.glEnableVertexAttribArray(aTexLoc);

    GLES30.glBindVertexArray(mVaoFboId);
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[1]);
    GLES30.glBufferData(
        GLES30.GL_ARRAY_BUFFER,
        mVertexBufferFbo.capacity() * 4,
        mVertexBufferFbo,
        GLES30.GL_STATIC_DRAW);
    int aPosLocFbo = GLES30.glGetAttribLocation(mKawaseDownProgramId, "aPosition");
    GLES30.glVertexAttribPointer(aPosLocFbo, 2, GLES30.GL_FLOAT, false, 16, 0);
    GLES30.glEnableVertexAttribArray(aPosLocFbo);
    int aTexLocFbo = GLES30.glGetAttribLocation(mKawaseDownProgramId, "aTexCoord");
    GLES30.glVertexAttribPointer(aTexLocFbo, 2, GLES30.GL_FLOAT, false, 16, 8);
    GLES30.glEnableVertexAttribArray(aTexLocFbo);

    GLES30.glBindVertexArray(0);
  }

  private void cacheUniforms() {
    uBlobColorsLoc = GLES30.glGetUniformLocation(mProgramId, "uBlobColors");
    uBlobPositionsLoc = GLES30.glGetUniformLocation(mProgramId, "uBlobPositions");
    uBlobSizesLoc = GLES30.glGetUniformLocation(mProgramId, "uBlobSizes");
    uBlobCountLoc = GLES30.glGetUniformLocation(mProgramId, "uBlobCount");
    uAspectRatioLoc = GLES30.glGetUniformLocation(mProgramId, "uAspectRatio");
    uBlurStrengthLoc = GLES30.glGetUniformLocation(mProgramId, "uBlurStrength");
    uDimLevelLoc = GLES30.glGetUniformLocation(mProgramId, "uDimLevel");
    uStyleLoc = GLES30.glGetUniformLocation(mProgramId, "uStyle");
    uTextureSharpLoc = GLES30.glGetUniformLocation(mProgramId, "uTextureSharp");
    uTextureBlurLoc = GLES30.glGetUniformLocation(mProgramId, "uTextureBlur");

    uDownTextureLoc = GLES30.glGetUniformLocation(mKawaseDownProgramId, "uTexture");
    uDownResolutionLoc = GLES30.glGetUniformLocation(mKawaseDownProgramId, "uResolution");
    uDownOffsetLoc = GLES30.glGetUniformLocation(mKawaseDownProgramId, "uOffset");

    uUpTextureLoc = GLES30.glGetUniformLocation(mKawaseUpProgramId, "uTexture");
    uUpResolutionLoc = GLES30.glGetUniformLocation(mKawaseUpProgramId, "uResolution");
    uUpOffsetLoc = GLES30.glGetUniformLocation(mKawaseUpProgramId, "uOffset");
  }

  public void updateBlur(float intensity, int style) {
    boolean isBlob = (style == 0 || style == 1 || style == 4);
    float blurOffset;
    int passes;

    if (isBlob) {
        blurOffset = 0.5f + (intensity * 2.0f);
        passes = 3;
    } else {
        float normalizedIntensity = Math.max(0f, Math.min(1f, intensity / 0.55f));
        passes = 2 + Math.round(normalizedIntensity * 4.0f);
        blurOffset = 0.5f + (normalizedIntensity * 3.0f);
    }
    gpuKawaseBlur(mSharpTextureId, blurOffset, passes);
  }

  private void gpuKawaseBlur(int inputTex, float offset, int passes) {
    GLES30.glBindVertexArray(mVaoFboId);

    GLES30.glUseProgram(mKawaseDownProgramId);
    GLES30.glUniform1f(uDownOffsetLoc, offset);

    for (int i = 0; i < passes; i++) {
      int currentW = Math.max(32, mWidth >> (i + 1));
      int currentH = Math.max(32, mHeight >> (i + 1));
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mKawaseFbos[i]);
      GLES30.glViewport(0, 0, currentW, currentH);

      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, i == 0 ? inputTex : mKawaseTextures[i - 1]);
      GLES30.glUniform1i(uDownTextureLoc, 0);
      GLES30.glUniform2f(uDownResolutionLoc, currentW, currentH);

      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    GLES30.glUseProgram(mKawaseUpProgramId);
    GLES30.glUniform1f(uUpOffsetLoc, offset);

    for (int i = passes - 2; i >= 0; i--) {
      int currentW = Math.max(1, mWidth >> (i + 1));
      int currentH = Math.max(1, mHeight >> (i + 1));
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mKawaseFbos[i]);
      GLES30.glViewport(0, 0, currentW, currentH);

      GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mKawaseTextures[i + 1]);
      GLES30.glUniform1i(uUpTextureLoc, 0);
      GLES30.glUniform2f(uUpResolutionLoc, currentW, currentH);

      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    }

    GLES30.glBindVertexArray(0);
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    GLES30.glViewport(0, 0, mWidth, mHeight);
  }

  public void resize(int width, int height) {
    if (width == mWidth && height == mHeight) return;
    mWidth = width;
    mHeight = height;
    mAspectRatio = (float) width / height;
    GLES30.glViewport(0, 0, width, height);
    allocateKawaseBuffers(width, height);
  }

  private void initBaseBlobs(Bitmap bitmap) {
    int targetBlobs = Math.min(16, Math.max(4, mWidth / 80));
    List<HazeColorExtractor.ColorCluster> clusters =
        HazeColorExtractor.extractColors(bitmap, targetBlobs);

    mBlobs.clear();
    int idx = 0;
    for (HazeColorExtractor.ColorCluster c : clusters) {
      mBlobColorsBuffer[idx * 3] = Color.red(c.color) / 255f;
      mBlobColorsBuffer[idx * 3 + 1] = Color.green(c.color) / 255f;
      mBlobColorsBuffer[idx * 3 + 2] = Color.blue(c.color) / 255f;

      mBlobs.add(new BlobPhysics(c.centerX, c.centerY, 1.0f + (mRandom.nextFloat() * 0.4f)));
      idx++;
    }
    mBlobCount = mBlobs.size();
    reRollTargets();
  }

  public void reRollTargets() {
    for (BlobPhysics b : mBlobs) {
      b.endX = 0.05f + mRandom.nextFloat() * 0.9f;
      b.endY = 0.05f + mRandom.nextFloat() * 0.9f;
      float midX = (b.startX + b.endX) / 2f;
      float midY = (b.startY + b.endY) / 2f;
      b.p1x = midX + (mRandom.nextFloat() - 0.5f) * 0.5f;
      b.p1y = midY + (mRandom.nextFloat() - 0.5f) * 0.5f;
      b.startSize = 0.05f;
      b.endSize = (0.12f + mRandom.nextFloat() * 0.08f) * b.massScale;
    }
    mBlobsDirty = true;
    mLastPhysicsT = -1f;
  }

  public void drawFrame(float blurStrength, float dimLevel, int style) {
    GLES30.glUseProgram(mProgramId);

    if (mBlobsDirty && mBlobCount > 0) {
      GLES30.glUniform3fv(uBlobColorsLoc, mBlobCount, mBlobColorsBuffer, 0);
      GLES30.glUniform1i(uBlobCountLoc, mBlobCount);
      mBlobsDirty = false;
    }

    float t = Math.max(0f, Math.min(1f, blurStrength));

    if (style == 0 || style == 1 || style == 4) {
      float physicsT = Math.max(0f, Math.min(1f, (t - 0.1f) / 0.9f));

      if (Math.abs(physicsT - mLastPhysicsT) > 0.001f || mLastStyle != style) {
        mLastPhysicsT = physicsT;
        float progress = 1.0f - (float) Math.pow(1.0f - physicsT, 3);

        int idx = 0;
        for (BlobPhysics b : mBlobs) {
          float u = 1.0f - progress;
          float bx =
              (u * u * b.startX) + (2 * u * progress * b.p1x) + (progress * progress * b.endX);
          float by =
              (u * u * b.startY) + (2 * u * progress * b.p1y) + (progress * progress * b.endY);
          mBlobPosBuffer[idx * 2] = bx;
          mBlobPosBuffer[idx * 2 + 1] = by;
          mBlobSizesBuffer[idx] = b.startSize + (b.endSize - b.startSize) * progress;
          idx++;
        }
        if (idx > 0) {
          GLES30.glUniform2fv(uBlobPositionsLoc, idx, mBlobPosBuffer, 0);
          GLES30.glUniform1fv(uBlobSizesLoc, idx, mBlobSizesBuffer, 0);
        }
      }
    }
    mLastStyle = style;

    GLES30.glUniform1f(uAspectRatioLoc, mAspectRatio);
    GLES30.glUniform1f(uBlurStrengthLoc, blurStrength);
    GLES30.glUniform1f(uDimLevelLoc, dimLevel);
    GLES30.glUniform1i(uStyleLoc, style);

    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mSharpTextureId);
    GLES30.glUniform1i(uTextureSharpLoc, 0);

    GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mBlurTextureId);
    GLES30.glUniform1i(uTextureBlurLoc, 1);

    GLES30.glBindVertexArray(mVaoScreenId);
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
    GLES30.glBindVertexArray(0);
  }

  public void destroy() {
    if (mSharpTextureId != 0) GLES30.glDeleteTextures(1, new int[] {mSharpTextureId}, 0);
    GLES30.glDeleteTextures(MAX_KAWASE_PASSES, mKawaseTextures, 0);
    GLES30.glDeleteFramebuffers(MAX_KAWASE_PASSES, mKawaseFbos, 0);
    GLES30.glDeleteVertexArrays(2, new int[] {mVaoScreenId, mVaoFboId}, 0);
    GLES30.glDeleteProgram(mProgramId);
    GLES30.glDeleteProgram(mKawaseDownProgramId);
    GLES30.glDeleteProgram(mKawaseUpProgramId);
  }

  private int createEmptyTexture(int w, int h) {
    int[] t = new int[1];
    GLES30.glGenTextures(1, t, 0);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
    GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D,
        0,
        GLES30.GL_RGBA,
        w,
        h,
        0,
        GLES30.GL_RGBA,
        GLES30.GL_UNSIGNED_BYTE,
        null);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    return t[0];
  }

  private int uploadTexture(Bitmap b) {
    if (b == null || b.isRecycled()) return 0;
    int[] t = new int[1];
    GLES30.glGenTextures(1, t, 0);
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);

    GLES30.glTexParameteri(
        GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
    GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);

    return t[0];
  }

  private int createProgram(String v, String f) {
    int vs = GLES30.glCreateShader(GLES30.GL_VERTEX_SHADER);
    GLES30.glShaderSource(vs, v);
    GLES30.glCompileShader(vs);

    int fs = GLES30.glCreateShader(GLES30.GL_FRAGMENT_SHADER);
    GLES30.glShaderSource(fs, f);
    GLES30.glCompileShader(fs);

    int p = GLES30.glCreateProgram();
    GLES30.glAttachShader(p, vs);
    GLES30.glAttachShader(p, fs);
    GLES30.glLinkProgram(p);
    return p;
  }
}
