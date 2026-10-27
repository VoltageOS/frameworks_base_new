/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * Copyright (C) 2025-2026 AxionOS
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
@file:OptIn(ExperimentalTextApi::class, ExperimentalMaterial3ExpressiveApi::class)

package com.android.appdatabackup

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class AppDataBackupActivity : ComponentActivity() {
    private val viewModel: BackupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoltageTheme {
                BackupScreen(viewModel, onBack = { finish() })
            }
        }
    }
}

// ---- Theme ----

@Composable
fun VoltageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    applySystemBars: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

    val view = LocalView.current
    val activity = view.context.findActivity()
    if (applySystemBars && !view.isInEditMode && activity != null) {
        SideEffect {
            val window = activity.window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val typography = rememberVoltageTypography()

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = ExpressiveShapes,
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content
        )
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

// ---- Typography ----

private class VoltageFontFamily(context: Context) {
    private val brand = context.configFontFamily("config_headlineFontFamily", "config_headlineFontFamilyMedium")
    private val plain = context.configFontFamily("config_bodyFontFamily", "config_bodyFontFamilyMedium")

    fun brand(
        token: String,
        size: TextUnit,
        lineHeight: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        tracking: TextUnit = 0.02.em,
    ): TextStyle = expressiveStyle(
        fontFamily = brand ?: variableFont("variable-$token"),
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = tracking,
    )

    fun brandEmphasized(
        token: String,
        size: TextUnit,
        lineHeight: TextUnit,
        tracking: TextUnit = 0.02.em,
    ): TextStyle = expressiveStyle(
        fontFamily = brand ?: variableFont("variable-$token-emphasized"),
        fontWeight = FontWeight.SemiBold,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = tracking,
    )

    fun plain(
        token: String,
        size: TextUnit,
        lineHeight: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        tracking: TextUnit = 0.02.em,
    ): TextStyle = expressiveStyle(
        fontFamily = plain ?: variableFont("variable-$token"),
        fontWeight = weight,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = tracking,
    )

    fun plainEmphasized(
        token: String,
        size: TextUnit,
        lineHeight: TextUnit,
        tracking: TextUnit = 0.02.em,
    ): TextStyle = expressiveStyle(
        fontFamily = plain ?: variableFont("variable-$token-emphasized"),
        fontWeight = FontWeight.SemiBold,
        fontSize = size,
        lineHeight = lineHeight,
        letterSpacing = tracking,
    )

    companion object {
        fun variableFont(name: String): FontFamily =
            FontFamily(Font(DeviceFontFamilyName(name)))

        @SuppressLint("DiscouragedApi")
        private fun Context.configFontFamily(normalKey: String, mediumKey: String): FontFamily? {
            val normal = getAndroidConfig(normalKey)
            val medium = getAndroidConfig(mediumKey)
            if (normal.isEmpty() || medium.isEmpty()) return null
            if (normal == "sans-serif" && medium == "sans-serif-medium") return null
            return FontFamily(
                Font(DeviceFontFamilyName(normal), FontWeight.Normal),
                Font(DeviceFontFamilyName(medium), FontWeight.Medium),
            )
        }

        @SuppressLint("DiscouragedApi")
        private fun Context.getAndroidConfig(name: String): String {
            val id = resources.getIdentifier(name, "string", "android")
            return if (id != 0) resources.getString(id) else ""
        }
    }
}

private fun expressiveStyle(
    fontFamily: FontFamily,
    fontWeight: FontWeight,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    letterSpacing: TextUnit = 0.02.em,
): TextStyle = TextStyle(
    fontFamily = fontFamily,
    fontWeight = fontWeight,
    fontSize = fontSize,
    lineHeight = lineHeight,
    letterSpacing = letterSpacing,
    hyphens = Hyphens.Auto,
)

private fun buildExpressiveTypography(context: Context): Typography {
    val f = VoltageFontFamily(context)
    return Typography(
        displayLarge = f.brand("display-large", 57.sp, 64.sp),
        displayLargeEmphasized = f.brandEmphasized("display-large", 57.sp, 64.sp),
        displayMedium = f.brand("display-medium", 45.sp, 52.sp),
        displayMediumEmphasized = f.brandEmphasized("display-medium", 45.sp, 52.sp),
        displaySmall = f.brand("display-small", 36.sp, 44.sp),
        displaySmallEmphasized = f.brandEmphasized("display-small", 36.sp, 44.sp),

        headlineLarge = f.brand("headline-large", 32.sp, 40.sp),
        headlineLargeEmphasized = f.brandEmphasized("headline-large", 32.sp, 40.sp),
        headlineMedium = f.brand("headline-medium", 28.sp, 36.sp),
        headlineMediumEmphasized = f.brandEmphasized("headline-medium", 28.sp, 36.sp),
        headlineSmall = f.brand("headline-small", 24.sp, 32.sp),
        headlineSmallEmphasized = f.brandEmphasized("headline-small", 24.sp, 32.sp),

        titleLarge = f.brand("title-large", 22.sp, 28.sp),
        titleLargeEmphasized = f.brandEmphasized("title-large", 22.sp, 28.sp),
        titleMedium = f.brand("title-medium", 16.sp, 24.sp, FontWeight.Medium),
        titleMediumEmphasized = f.brandEmphasized("title-medium", 16.sp, 24.sp),
        titleSmall = f.brand("title-small", 14.sp, 20.sp, FontWeight.Medium),
        titleSmallEmphasized = f.brandEmphasized("title-small", 14.sp, 20.sp),

        bodyLarge = f.plain("body-large", 16.sp, 24.sp),
        bodyLargeEmphasized = f.plainEmphasized("body-large", 16.sp, 24.sp),
        bodyMedium = f.plain("body-medium", 14.sp, 20.sp),
        bodyMediumEmphasized = f.plainEmphasized("body-medium", 14.sp, 20.sp),
        bodySmall = f.plain("body-small", 12.sp, 16.sp, tracking = 0.00833333.em),
        bodySmallEmphasized = f.plainEmphasized("body-small", 12.sp, 16.sp),

        labelLarge = f.plain("label-large", 14.sp, 20.sp, FontWeight.Medium),
        labelLargeEmphasized = f.plainEmphasized("label-large", 14.sp, 20.sp),
        labelMedium = f.plain("label-medium", 12.sp, 16.sp, FontWeight.Medium, 0.00833333.em),
        labelMediumEmphasized = f.plainEmphasized("label-medium", 12.sp, 16.sp, tracking = 0.00833333.em),
        labelSmall = f.plain("label-small", 11.sp, 16.sp, FontWeight.Medium, 0.00909091.em),
        labelSmallEmphasized = f.plainEmphasized("label-small", 11.sp, 16.sp, tracking = 0.00909091.em),
    )
}

@Composable
fun rememberVoltageTypography(): Typography {
    val context = LocalContext.current
    return remember { buildExpressiveTypography(context) }
}

// ---- Shapes ----

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
