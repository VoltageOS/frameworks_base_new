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

public class HazeShaders {
  public static final String VERTEX_SHADER =
      "#version 300 es\n"
          + "in vec4 aPosition;\n"
          + "in vec2 aTexCoord;\n"
          + "out vec2 vTexCoord;\n"
          + "void main() {\n"
          + "    gl_Position = aPosition;\n"
          + "    vTexCoord = aTexCoord;\n"
          + "}\n";

  public static final String KAWASE_DOWN_FRAGMENT_SHADER =
      "#version 300 es\n"
          + "precision mediump float;\n"
          + "in vec2 vTexCoord;\n"
          + "out vec4 fragColor;\n"
          + "uniform sampler2D uTexture;\n"
          + "uniform vec2 uResolution;\n"
          + "uniform float uOffset;\n"
          + "void main() {\n"
          + "    vec2 halfpixel = uOffset / uResolution;\n"
          + "    vec3 sum = texture(uTexture, vTexCoord).rgb * 4.0;\n"
          + "    sum += texture(uTexture, vTexCoord - halfpixel).rgb;\n"
          + "    sum += texture(uTexture, vTexCoord + halfpixel).rgb;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(halfpixel.x, -halfpixel.y)).rgb;\n"
          + "    sum += texture(uTexture, vTexCoord - vec2(halfpixel.x, -halfpixel.y)).rgb;\n"
          + "    fragColor = vec4(sum / 8.0, 1.0);\n"
          + "}\n";

  public static final String KAWASE_UP_FRAGMENT_SHADER =
      "#version 300 es\n"
          + "precision mediump float;\n"
          + "in vec2 vTexCoord;\n"
          + "out vec4 fragColor;\n"
          + "uniform sampler2D uTexture;\n"
          + "uniform vec2 uResolution;\n"
          + "uniform float uOffset;\n"
          + "void main() {\n"
          + "    vec2 halfpixel = uOffset / uResolution;\n"
          + "    vec3 sum = texture(uTexture, vTexCoord + vec2(-halfpixel.x * 2.0, 0.0)).rgb;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(-halfpixel.x, halfpixel.y)).rgb * 2.0;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(0.0, halfpixel.y * 2.0)).rgb;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(halfpixel.x, halfpixel.y)).rgb * 2.0;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(halfpixel.x * 2.0, 0.0)).rgb;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(halfpixel.x, -halfpixel.y)).rgb * 2.0;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(0.0, -halfpixel.y * 2.0)).rgb;\n"
          + "    sum += texture(uTexture, vTexCoord + vec2(-halfpixel.x, -halfpixel.y)).rgb *"
          + " 2.0;\n"
          + "    fragColor = vec4(sum / 12.0, 1.0);\n"
          + "}\n";

  public static final String HAZE_FRAGMENT_SHADER =
      "#version 300 es\n"
          + "precision highp float;\n"
          + "in vec2 vTexCoord;\n"
          + "out vec4 fragColor;\n"
          + "uniform sampler2D uTextureSharp;\n"
          + "uniform sampler2D uTextureBlur;\n"
          + "#define MAX_BLOBS 16\n"
          + "uniform vec3 uBlobColors[MAX_BLOBS];\n"
          + "uniform vec2 uBlobPositions[MAX_BLOBS];\n"
          + "uniform float uBlobSizes[MAX_BLOBS];\n"
          + "uniform int uBlobCount;\n"
          + "uniform float uAspectRatio;\n"
          + "uniform float uBlurStrength;\n"
          + "uniform float uDimLevel;\n"
          + "uniform int uStyle;\n"
          + "float IGN(vec2 p) {\n"
          + "    vec3 magic = vec3(0.06711056, 0.00583715, 52.9829189);\n"
          + "    return fract(magic.z * fract(dot(p, magic.xy)));\n"
          + "}\n"
          + "void main() {\n"
          + "    float t = clamp(uBlurStrength, 0.0, 1.0);\n"
          + "    vec2 uv = vTexCoord;\n"
          + "    vec3 finalColor;\n"
          + "    \n"
          + "    if (uStyle == 0 || uStyle == 1 || uStyle == 4) {\n"
          + "        vec3 sharp = texture(uTextureSharp, vTexCoord).rgb;\n"
          + "        uv.x *= uAspectRatio;\n"
          + "        vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;\n"
          + "        finalColor = mix(sharp, frosted, smoothstep(0.0, 0.2, t));\n"
          + "        if (t > 0.15) {\n"
          + "            vec3 cloudSum = vec3(0.0);\n"
          + "            float cloudWeight = 0.0;\n"
          + "            float blobOpacity = smoothstep(0.15, 0.3, t);\n"
          + "            vec3 overlaidColor = vec3(0.0);\n"
          + "            float accumulatedAlpha = 0.0;\n"
          + "            for(int i = 0; i < MAX_BLOBS; i++) {\n"
          + "                if (i >= uBlobCount) break;\n"
          + "                vec2 pos = uBlobPositions[i];\n"
          + "                pos.x *= uAspectRatio;\n"
          + "                vec2 diff = uv - pos;\n"
          + "                float distSq = dot(diff, diff);\n"
          + "                float w = uBlobSizes[i] / (distSq + 0.05);\n"
          + "                cloudSum += uBlobColors[i] * w;\n"
          + "                cloudWeight += w;\n"
          + "                float sizeSq = uBlobSizes[i] * uBlobSizes[i];\n"
          + "                if (distSq < sizeSq) {\n"
          + "                    float dist = sqrt(distSq);\n"
          + "                    float alpha = (1.0 - smoothstep(0.0, uBlobSizes[i], dist)) *"
          + " blobOpacity;\n"
          + "                    overlaidColor = mix(overlaidColor, uBlobColors[i], alpha);\n"
          + "                    accumulatedAlpha = accumulatedAlpha + alpha * (1.0 -"
          + " accumulatedAlpha);\n"
          + "                }\n"
          + "            }\n"
          + "            if (cloudWeight > 0.0 && t > 0.18) {\n"
          + "                finalColor = mix(finalColor, cloudSum / cloudWeight, smoothstep(0.18,"
          + " 0.5, t));\n"
          + "            }\n"
          + "            if (accumulatedAlpha > 0.0) {\n"
          + "                finalColor = mix(finalColor, overlaidColor, accumulatedAlpha);\n"
          + "            }\n"
          + "        }\n"
          + "    } else {\n"
          + "        vec3 sharpLod = textureLod(uTextureSharp, vTexCoord, t * 4.0).rgb;\n"
          + "        vec3 frosted = texture(uTextureBlur, vTexCoord).rgb;\n"
          + "        finalColor = mix(sharpLod, frosted, t);\n"
          + "    }\n"
          + "    \n"
          + "    if (uStyle == 0 || uStyle == 1 || uStyle == 4) {\n"
          + "        finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);\n"
          + "    }\n"
          + "    if (t > 0.0) {\n"
          + "        finalColor += (IGN(gl_FragCoord.xy) - 0.5) * (4.0 / 255.0);\n"
          + "    }\n"
          + "    fragColor = vec4(finalColor, 1.0);\n"
          + "}\n";
}
