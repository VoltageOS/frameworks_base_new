/*
 * Copyright (C) 2025 AxionOS
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
package com.android.systemui.media.controls.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import com.android.systemui.media.MediaSessionManager
import kotlin.math.exp
import kotlin.math.floor

class WaveformSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.seekBarStyle,
) : SeekBar(context, attrs, defStyleAttr), MediaSessionManager.MediaDataListener {

    private val density = resources.displayMetrics.density
    
    private var pseudoEnergy = 0f
    private var lastProgress = 0
    private var lastDrawTime = 0L

    private val backgroundPath = Path()
    private val heartbeatLUT = FloatArray(256)
    
    private val thumbRadius = 8f * density
    
    private val waveHeight = 24f * density
    private val waveLength = 180f * density
    private val strokeThickness = 3f * density
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeThickness
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
        alpha = 77
    }
    
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeThickness
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 60
    }
    
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(4f * density, 0f, 2f * density, Color.argb(80, 0, 0, 0))
    }
    
    private var wavePhase = 0f
    private var waveAmplitudeMultiplier = 0f
    private var waveAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    var isPlaying = false
        private set
    
    init {
        thumb = TransparentDrawable()
        splitTrack = false
        progressDrawable = TransparentDrawable()

        for (i in 0..255) {
            val localPhase = i / 255f
            val p = gaussian(localPhase, 0.20f, 0.025f, 0.12f)
            val q = gaussian(localPhase, 0.46f, 0.012f, -0.15f)
            val r = gaussian(localPhase, 0.50f, 0.015f, 1.0f)
            val s = gaussian(localPhase, 0.54f, 0.015f, -0.3f)
            val t = gaussian(localPhase, 0.75f, 0.035f, 0.2f)
            heartbeatLUT[i] = p + q + r + s + t
        }
    }
    
    fun startWaveAnimation() {
        if (isPlaying && waveAnimator?.isRunning == true) return
        isPlaying = true
        
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(waveAmplitudeMultiplier, 1f).apply {
            duration = 300L
            addUpdateListener { 
                waveAmplitudeMultiplier = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        
        waveAnimator?.cancel()
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { 
                wavePhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    fun stopWaveAnimation() {
        isPlaying = false
        waveAnimator?.cancel()
        waveAnimator = null
        
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(waveAmplitudeMultiplier, 0f).apply {
            duration = 300L
            addUpdateListener { 
                waveAmplitudeMultiplier = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    fun regenerateWaveform(seed: Long = System.currentTimeMillis()) {
        invalidate()
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MediaSessionManager.get().addListener(this)
        
        if (isPlaying && waveAnimator?.isRunning != true) {
            waveAnimator?.cancel()
            waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { 
                    wavePhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        MediaSessionManager.get().removeListener(this)
        waveAnimator?.cancel()
    }
    
    override fun onMediaColorsChanged(color: Int) {
        post { setWaveformColor(color) }
    }
    
    override fun onDraw(canvas: Canvas) {
        val currentTime = android.os.SystemClock.uptimeMillis()
        val dt = if (lastDrawTime > 0) (currentTime - lastDrawTime).toFloat() else 16f
        lastDrawTime = currentTime
        
        val progressDelta = kotlin.math.abs(progress - lastProgress).toFloat()
        lastProgress = progress
        
        val decay = kotlin.math.exp((-dt / 200f).toDouble()).toFloat()
        val velocity = if (dt > 0) progressDelta / dt else 0f
        val targetEnergy = (velocity / 5f).coerceIn(0f, 1f)
        pseudoEnergy = (pseudoEnergy * decay) + targetEnergy * (1f - decay)

        val width = width.toFloat()
        val height = height.toFloat()
        val pLeft = paddingLeft.toFloat()
        val pRight = paddingRight.toFloat()
        
        val drawWidth = width - pLeft - pRight
        if (drawWidth <= 0) return
        
        val centerY = height / 2f + (density * 1.5f)
        
        val ratio = if (max > 0) progress.toFloat() / max else 0f
        val progressX = pLeft + drawWidth * ratio
        
        backgroundPath.reset()
        
        backgroundPaint.alpha = (50 + 30 * pseudoEnergy).toInt().coerceIn(0, 255)
        progressPaint.alpha = 255

        if (waveAmplitudeMultiplier > 0.01f) {
            buildHeartbeatPath(backgroundPath, pLeft, pLeft + drawWidth, pLeft, centerY)
            canvas.drawPath(backgroundPath, backgroundPaint)
            
            if (progressX > pLeft) {
                canvas.save()
                canvas.clipRect(pLeft, 0f, progressX, height)
                canvas.drawPath(backgroundPath, progressPaint)
                canvas.restore()
            }
        } else {
            canvas.drawLine(pLeft, centerY, pLeft + drawWidth, centerY, backgroundPaint)
            if (progressX > pLeft) {
                canvas.drawLine(pLeft, centerY, progressX, centerY, progressPaint)
            }
        }
        
        canvas.drawCircle(progressX, centerY + 2 * density, thumbRadius, thumbShadowPaint)
        canvas.drawCircle(progressX, centerY, thumbRadius, thumbPaint)
    }
    
    private fun buildHeartbeatPath(
        path: Path,
        startX: Float,
        endX: Float,
        offsetOriginX: Float,
        centerY: Float
    ) {
        val step = 1.5f * density 
        var x = startX
        var prevX = x
        var prevY = calculateHeartbeatY(prevX, offsetOriginX, centerY)
        path.moveTo(prevX, prevY)
        
        x += step
        
        while (x <= endX) {
            val currY = calculateHeartbeatY(x, offsetOriginX, centerY)
            val midX = (prevX + x) / 2f
            val midY = (prevY + currY) / 2f
            
            path.quadTo(prevX, prevY, midX, midY)
            
            prevX = x
            prevY = currY
            x += step
        }
        
        path.lineTo(prevX, prevY)
        if (prevX < endX) {
            path.lineTo(endX, calculateHeartbeatY(endX, offsetOriginX, centerY))
        }
    }

    private fun calculateHeartbeatY(x: Float, startX: Float, centerY: Float): Float {
        val rawPhase = ((x - startX) / waveLength + wavePhase).toDouble()
        val normalizedPhase = (rawPhase - floor(rawPhase)).toFloat()
        
        if (normalizedPhase > 0.2f) {
            return centerY
        }
        
        val localPhase = normalizedPhase / 0.2f
        
        val lutIndex = (localPhase * 255).toInt().coerceIn(0, 255)
        val yOffset = heartbeatLUT[lutIndex]
        
        val dynamicAmp = waveHeight * waveAmplitudeMultiplier * (0.7f + 0.6f * pseudoEnergy)
        return centerY - (yOffset * dynamicAmp)
    }

    private fun gaussian(x: Float, center: Float, width: Float, amp: Float): Float {
        val scaled = (x - center) / width
        
        if (scaled < -3.5f || scaled > 3.5f) return 0f
        
        return amp * kotlin.math.exp((-0.5f * scaled * scaled).toDouble()).toFloat()
    }

    fun setWaveformColor(color: Int) {
        progressPaint.color = color
        backgroundPaint.color = color
        backgroundPaint.alpha = 77
        invalidate()
    }
    
    fun setThumbColor(color: Int) {
        thumbPaint.color = color
        thumbPaint.setShadowLayer(4f * density, 0f, 2f * density, Color.argb(80, 0, 0, 0))
        invalidate()
    }
    
    private class TransparentDrawable : Drawable() {
        override fun draw(canvas: Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    }
}
