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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HazeColorExtractor {

  public static class ColorCluster {
    public int color;
    public float centerX;
    public float centerY;

    public ColorCluster(int color, float centerX, float centerY) {
      this.color = color;
      this.centerX = centerX;
      this.centerY = centerY;
    }
  }

  private static class Bucket {
    int start, end;
    Bucket(int s, int e) { start = s; end = e; }
    int size() { return end - start; }
  }

  public static List<ColorCluster> extractColors(Bitmap original, int targetColors) {
    Bitmap bitmap = original;
    boolean didScale = false;

    if (original.getWidth() > 128 || original.getHeight() > 128) {
      float scale = Math.min(128f / original.getWidth(), 128f / original.getHeight());
      bitmap =
          Bitmap.createScaledBitmap(
              original,
              Math.max(1, Math.round(original.getWidth() * scale)),
              Math.max(1, Math.round(original.getHeight() * scale)),
              true);
      didScale = true;
    }

    int w = bitmap.getWidth();
    int h = bitmap.getHeight();

    int[] pixels = new int[w * h];
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

    int stepX = Math.max(1, w / 32);
    int stepY = Math.max(1, h / 32);

    int maxSamples = ((w - 1) / stepX + 1) * ((h - 1) / stepY + 1);
    int[] sampleColors = new int[maxSamples];
    int[] sampleXs = new int[maxSamples];
    int[] sampleYs = new int[maxSamples];
    int[] indices = new int[maxSamples];
    int sampleCount = 0;

    for (int y = 0; y < h; y += stepY) {
      for (int x = 0; x < w; x += stepX) {
        sampleColors[sampleCount] = pixels[y * w + x];
        sampleXs[sampleCount] = x;
        sampleYs[sampleCount] = y;
        indices[sampleCount] = sampleCount;
        sampleCount++;
      }
    }

    List<Bucket> buckets = new ArrayList<>();
    buckets.add(new Bucket(0, sampleCount));

    while (buckets.size() < targetColors) {
      Bucket largestBucket = null;
      int largestRange = 0;
      int splitChannel = 0;

      for (Bucket bucket : buckets) {
        if (bucket.size() <= 1) continue;
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (int i = bucket.start; i < bucket.end; i++) {
          int c = sampleColors[indices[i]];
          int r = Color.red(c);
          int g = Color.green(c);
          int b = Color.blue(c);
          if (r < minR) minR = r;
          if (r > maxR) maxR = r;
          if (g < minG) minG = g;
          if (g > maxG) maxG = g;
          if (b < minB) minB = b;
          if (b > maxB) maxB = b;
        }
        int rRange = maxR - minR, gRange = maxG - minG, bRange = maxB - minB;
        int maxRange = Math.max(rRange, Math.max(gRange, bRange));
        if (maxRange > largestRange) {
          largestRange = maxRange;
          largestBucket = bucket;
          splitChannel = (maxRange == rRange) ? 0 : (maxRange == gRange) ? 1 : 2;
        }
      }
      if (largestBucket == null) break;

      final int channel = splitChannel;
      quickSortIndices(indices, sampleColors, largestBucket.start, largestBucket.end - 1, channel);

      int median = largestBucket.start + largestBucket.size() / 2;
      Bucket bucket1 = new Bucket(largestBucket.start, median);
      Bucket bucket2 = new Bucket(median, largestBucket.end);
      buckets.remove(largestBucket);
      buckets.add(bucket1);
      buckets.add(bucket2);
    }

    List<ColorCluster> clusters = new ArrayList<>();
    for (Bucket bucket : buckets) {
      if (bucket.size() == 0) continue;
      long sumR = 0, sumG = 0, sumB = 0;
      float sumX = 0, sumY = 0;
      for (int i = bucket.start; i < bucket.end; i++) {
        int idx = indices[i];
        int c = sampleColors[idx];
        sumR += Color.red(c);
        sumG += Color.green(c);
        sumB += Color.blue(c);
        sumX += sampleXs[idx];
        sumY += sampleYs[idx];
      }
      int count = bucket.size();
      int avgColor = Color.rgb((int) (sumR / count), (int) (sumG / count), (int) (sumB / count));
      clusters.add(new ColorCluster(avgColor, sumX / count / w, sumY / count / h));
    }

    if (didScale) {
      bitmap.recycle();
    }

    return clusters;
  }

  private static void quickSortIndices(int[] indices, int[] colors, int low, int high, int channel) {
   if (low < high) {
      int pi = partition(indices, colors, low, high, channel);
      quickSortIndices(indices, colors, low, pi - 1, channel);
      quickSortIndices(indices, colors, pi + 1, high, channel);
    }
  }

  private static int partition(int[] indices, int[] colors, int low, int high, int channel) {
    int pivotColor = colors[indices[high]];
    int pivotVal = (channel == 0) ? Color.red(pivotColor) : ((channel == 1) ? Color.green(pivotColor) : Color.blue(pivotColor));
    int i = (low - 1);
    for (int j = low; j < high; j++) {
      int c = colors[indices[j]];
      int val = (channel == 0) ? Color.red(c) : ((channel == 1) ? Color.green(c) : Color.blue(c));
      if (val <= pivotVal) {
        i++;
        int temp = indices[i]; indices[i] = indices[j]; indices[j] = temp;
      }
    }
    int temp = indices[i + 1]; indices[i + 1] = indices[high]; indices[high] = temp;
    return i + 1;
  }
}
