package com.roman.zemzeme.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ============================================================================
// ACCESSIBILITY - REDUCED MOTION SUPPORT
// ============================================================================

/**
 * Composition local that provides whether reduced motion is preferred.
 * Check system accessibility settings and animator duration scale.
 * When true, animations should be minimized or disabled.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Composition local that provides whether the app is in dark theme.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

// ============================================================================
// ZEMZEME - CYBERPUNK COLOR SYSTEM
// ============================================================================

// Primary: Electric Cyan - The signature color representing connectivity
val ElectricCyan = Color(0xFF00F5FF)
val ElectricCyanDark = Color(0xFF00C4CC)
val ElectricCyanLight = Color(0xFF7FFFFF)

// Accent: Neon Purple - For special actions and highlights
val NeonPurple = Color(0xFFBF5AF2)
val NeonPurpleDark = Color(0xFF9945D9)
val NeonPurpleLight = Color(0xFFD98FFF)

// Success: Electric Cyan - For encrypted/secure indicators (changed from green)
val MatrixGreen = Color(0xFF00F5FF)  // Now using Electric Cyan
val MatrixGreenDark = Color(0xFF00C4CC)  // Cyan dark variant
val MatrixGreenLight = Color(0xFF7FFFFF)  // Cyan light variant

// Warning: Solar Orange - For alerts and important notices
val SolarOrange = Color(0xFFFF9F0A)
val SolarOrangeDark = Color(0xFFFF6B00)

// Error: Signal Red - For errors and destructive actions
val SignalRed = Color(0xFFFF453A)
val SignalRedDark = Color(0xFFD70015)

// Neutral: Deep Space colors for backgrounds and surfaces
val DeepSpace = Color(0xFF0A0A0F)         // Deepest background
val SpaceGray = Color(0xFF12121A)          // Card/surface background
val CosmicGray = Color(0xFF1C1C28)         // Elevated surface
val NebulaMist = Color(0xFF2A2A3C)         // Borders, dividers
val StardustGray = Color(0xFF8E8E9A)       // Secondary text
val MoonlightWhite = Color(0xFFF5F5F7)     // Primary text
val TextTertiary = Color(0xFF5C5C6E)       // Disabled, placeholder

// Light mode alternatives
val CloudWhite = Color(0xFFFAFAFC)
val SilverMist = Color(0xFFF0F0F5)
val FogGray = Color(0xFFE5E5EA)
val AsphaltGray = Color(0xFF3A3A4C)
val InkBlack = Color(0xFF1A1A24)
val TextTertiaryLight = Color(0xFF6E6E80)

// Message bubble colors
val SentBubbleDark = Color(0xFF1A3A4C)     // Tinted cyan for sent messages
val ReceivedBubbleDark = Color(0xFF1E1E2E) // Neutral for received
val SentBubbleLight = Color(0xFFE3F6FF)
val ReceivedBubbleLight = Color(0xFFF0F0F5)

// Semantic colors
val SemanticSuccess = Color(0xFF00F5FF)    // Electric Cyan (success indicator)
val SemanticLink = Color(0xFF007AFF)       // iOS blue

// ============================================================================
// EXTENDED COLOR PALETTE FOR UI COMPONENTS
// ============================================================================

data class ExtendedColors(
    val electricCyan: Color,
    val electricCyanVariant: Color,
    val neonPurple: Color,
    val matrixGreen: Color,
    val solarOrange: Color,
    val signalRed: Color,
    val sentBubble: Color,
    val receivedBubble: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val glowColor: Color,
    val success: Color,
    val link: Color,
    val contactGreen: Color
)

val DarkExtendedColors = ExtendedColors(
    electricCyan = ElectricCyan,
    electricCyanVariant = ElectricCyanDark,
    neonPurple = NeonPurple,
    matrixGreen = MatrixGreen,
    solarOrange = SolarOrange,
    signalRed = SignalRed,
    sentBubble = SentBubbleDark,
    receivedBubble = ReceivedBubbleDark,
    surfaceElevated = CosmicGray,
    borderSubtle = NebulaMist,
    textSecondary = StardustGray,
    textTertiary = TextTertiary,
    glowColor = ElectricCyan.copy(alpha = 0.4f),
    success = SemanticSuccess,
    link = SemanticLink,
    contactGreen = Color(0xFF30D158)
)

val LightExtendedColors = ExtendedColors(
    electricCyan = ElectricCyanDark,
    electricCyanVariant = ElectricCyan,
    neonPurple = NeonPurpleDark,
    matrixGreen = MatrixGreenDark,
    solarOrange = SolarOrangeDark,
    signalRed = SignalRedDark,
    sentBubble = SentBubbleLight,
    receivedBubble = ReceivedBubbleLight,
    surfaceElevated = CloudWhite,
    borderSubtle = FogGray,
    textSecondary = AsphaltGray,
    textTertiary = TextTertiaryLight,
    glowColor = ElectricCyanDark.copy(alpha = 0.2f),
    success = MatrixGreenDark,
    link = SemanticLink,
    contactGreen = Color(0xFF248A3D)
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtendedColors }

// ============================================================================
// MATERIAL 3 COLOR SCHEMES
// ============================================================================

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = DeepSpace,
    primaryContainer = Color(0xFF003D42),
    onPrimaryContainer = ElectricCyanLight,
    secondary = NeonPurple,
    onSecondary = DeepSpace,
    secondaryContainer = Color(0xFF3D2952),
    onSecondaryContainer = NeonPurpleLight,
    tertiary = MatrixGreen,
    onTertiary = DeepSpace,
    tertiaryContainer = Color(0xFF1A3D24),
    onTertiaryContainer = MatrixGreenLight,
    background = DeepSpace,
    onBackground = MoonlightWhite,
    surface = SpaceGray,
    onSurface = MoonlightWhite,
    surfaceVariant = CosmicGray,
    onSurfaceVariant = StardustGray,
    outline = NebulaMist,
    outlineVariant = Color(0xFF3A3A4C),
    error = SignalRed,
    onError = DeepSpace,
    errorContainer = Color(0xFF4D1F1A),
    onErrorContainer = Color(0xFFFFB4AB)
)

private val LightColorScheme = lightColorScheme(
    primary = ElectricCyanDark,
    onPrimary = MoonlightWhite,
    primaryContainer = Color(0xFFB8F5FF),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = NeonPurpleDark,
    onSecondary = MoonlightWhite,
    secondaryContainer = Color(0xFFF2DAFF),
    onSecondaryContainer = Color(0xFF2D0A42),
    tertiary = MatrixGreenDark,
    onTertiary = MoonlightWhite,
    tertiaryContainer = Color(0xFFC8FFCE),
    onTertiaryContainer = Color(0xFF002108),
    background = CloudWhite,
    onBackground = InkBlack,
    surface = MoonlightWhite,
    onSurface = InkBlack,
    surfaceVariant = SilverMist,
    onSurfaceVariant = AsphaltGray,
    outline = FogGray,
    outlineVariant = Color(0xFFD1D1DC),
    error = SignalRedDark,
    onError = MoonlightWhite,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

// ============================================================================
// GRADIENT DEFINITIONS
// ============================================================================

object ZemzemeGradients {
    val primaryGlow: Brush
        @Composable get() = Brush.radialGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0f)
            )
        )

    val accentGradient: Brush
        @Composable get() = Brush.horizontalGradient(
            colors = listOf(
                ElectricCyan,
                NeonPurple
            )
        )

    val sentMessageGradient: (Boolean) -> Brush = { isDark ->
        if (isDark) {
            Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF1A3A4C),
                    Color(0xFF1E2F3D)
                )
            )
        } else {
            Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFFE3F6FF),
                    Color(0xFFE8F4FF)
                )
            )
        }
    }
}

// ============================================================================
// SHAPE DEFINITIONS
// ============================================================================

object ZemzemeShapes {
    val MessageBubbleRadius = 20.dp
    val MessageBubbleRadiusSmall = 6.dp
    val CardRadius = 16.dp
    val ButtonRadius = 12.dp
    val InputRadius = 24.dp
    val ChipRadius = 20.dp
    val BottomSheetRadius = 28.dp
}

// ============================================================================
// ELEVATION & EFFECTS
// ============================================================================

object ZemzemeElevation {
    val None = 0.dp
    val Low = 2.dp
    val Medium = 4.dp
    val High = 8.dp
    val Floating = 16.dp
}

// ============================================================================
// MAIN THEME COMPOSABLE
// ============================================================================

@Composable
fun ZemzemeTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // App-level override from ThemePreferenceManager
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.Dark)

    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkColorScheme else LightColorScheme
    val extendedColors = if (shouldUseDark) DarkExtendedColors else LightExtendedColors

    // Animate color transitions for smooth theme switching (300ms ease)
    val animatedColorScheme = colorScheme.copy(
        primary = animateColorAsState(colorScheme.primary, tween(300), label = "primary").value,
        background = animateColorAsState(colorScheme.background, tween(300), label = "background").value,
        surface = animateColorAsState(colorScheme.surface, tween(300), label = "surface").value
    )

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            // Use WindowInsetsController for light/dark appearance
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !shouldUseDark
            insetsController.isAppearanceLightNavigationBars = !shouldUseDark

            // Disable navigation bar contrast enforcement
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    // Accessibility: Check for reduced motion preference
    val reduceMotion = remember(context) {
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val accessibilityEnabled = am?.isEnabled == true
            val animatorDurationScale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            accessibilityEnabled && (animatorDurationScale == 0f || am?.isTouchExplorationEnabled == true)
        } catch (e: Exception) {
            false
        }
    }

    CompositionLocalProvider(
        LocalExtendedColors provides extendedColors,
        LocalReducedMotion provides reduceMotion,
        LocalIsDarkTheme provides shouldUseDark
    ) {
        MaterialTheme(
            colorScheme = animatedColorScheme,
            typography = Typography,
            content = content
        )
    }
}

// ============================================================================
// THEME EXTENSIONS
// ============================================================================

/**
 * Access extended colors from anywhere in the composition
 */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable get() = LocalExtendedColors.current

/**
 * Check if currently in dark theme
 */
@Composable
fun isAppInDarkTheme(): Boolean = LocalIsDarkTheme.current

/**
 * Check if reduced motion is preferred by the user.
 * Use this to disable or reduce animations for accessibility.
 */
@Composable
fun isReducedMotionEnabled(): Boolean = LocalReducedMotion.current
