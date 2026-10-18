package ps.reso.instaeclipse.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = AppPrimary,
    onPrimary = AppOnPrimary,
    primaryContainer = AppPrimaryContainer,
    onPrimaryContainer = AppOnPrimaryContainer,
    secondary = AppSecondary,
    onSecondary = AppOnSecondary,
    secondaryContainer = AppSecondaryContainer,
    onSecondaryContainer = AppOnSecondaryContainer,
    tertiary = AppTertiary,
    onTertiary = AppOnTertiary,
    tertiaryContainer = AppTertiaryContainer,
    onTertiaryContainer = AppOnTertiaryContainer,
    error = AppError,
    onError = AppOnError,
    errorContainer = AppErrorContainer,
    onErrorContainer = AppOnErrorContainer,
    background = AppSurfaceLight,
    onBackground = AppOnSurfaceLight,
    surface = AppSurfaceLight,
    onSurface = AppOnSurfaceLight,
    surfaceVariant = AppSurfaceVariantLight,
    onSurfaceVariant = AppOnSurfaceVariantLight,
    surfaceDim = AppSurfaceDimLight,
    surfaceBright = AppSurfaceBrightLight,
    surfaceContainerLowest = AppSurfaceContainerLowestLight,
    surfaceContainerLow = AppSurfaceContainerLowLight,
    surfaceContainer = AppSurfaceContainerLight,
    surfaceContainerHigh = AppSurfaceContainerHighLight,
    surfaceContainerHighest = AppSurfaceContainerHighestLight,
    inverseSurface = AppInverseSurfaceLight,
    inverseOnSurface = AppInverseOnSurfaceLight,
    inversePrimary = AppInversePrimaryLight,
    outline = AppOutlineLight,
    outlineVariant = AppOutlineVariantLight,
    scrim = AppScrimLight,
    primaryFixed = AppPrimaryFixed,
    primaryFixedDim = AppPrimaryFixedDim,
    onPrimaryFixed = AppOnPrimaryFixed,
    onPrimaryFixedVariant = AppOnPrimaryFixedVariant,
    secondaryFixed = AppSecondaryFixed,
    secondaryFixedDim = AppSecondaryFixedDim,
    onSecondaryFixed = AppOnSecondaryFixed,
    onSecondaryFixedVariant = AppOnSecondaryFixedVariant,
    tertiaryFixed = AppTertiaryFixed,
    tertiaryFixedDim = AppTertiaryFixedDim,
    onTertiaryFixed = AppOnTertiaryFixed,
    onTertiaryFixedVariant = AppOnTertiaryFixedVariant,
)

private val DarkColorScheme = darkColorScheme(
    primary = AppPrimaryDark,
    onPrimary = AppOnPrimaryDark,
    primaryContainer = AppPrimaryContainerDark,
    onPrimaryContainer = AppOnPrimaryContainerDark,
    secondary = AppSecondaryDark,
    onSecondary = AppOnSecondaryDark,
    secondaryContainer = AppSecondaryContainerDark,
    onSecondaryContainer = AppOnSecondaryContainerDark,
    tertiary = AppTertiaryDark,
    onTertiary = AppOnTertiaryDark,
    tertiaryContainer = AppTertiaryContainerDark,
    onTertiaryContainer = AppOnTertiaryContainerDark,
    error = AppErrorDark,
    onError = AppOnErrorDark,
    errorContainer = AppErrorContainerDark,
    onErrorContainer = AppOnErrorContainerDark,
    background = AppSurfaceDark,
    onBackground = AppOnSurfaceDark,
    surface = AppSurfaceDark,
    onSurface = AppOnSurfaceDark,
    surfaceVariant = AppSurfaceVariantDark,
    onSurfaceVariant = AppOnSurfaceVariantDark,
    surfaceDim = AppSurfaceDimDark,
    surfaceBright = AppSurfaceBrightDark,
    surfaceContainerLowest = AppSurfaceContainerLowestDark,
    surfaceContainerLow = AppSurfaceContainerLowDark,
    surfaceContainer = AppSurfaceContainerDark,
    surfaceContainerHigh = AppSurfaceContainerHighDark,
    surfaceContainerHighest = AppSurfaceContainerHighestDark,
    inverseSurface = AppInverseSurfaceDark,
    inverseOnSurface = AppInverseOnSurfaceDark,
    inversePrimary = AppInversePrimaryDark,
    outline = AppOutlineDark,
    outlineVariant = AppOutlineVariantDark,
    scrim = AppScrimDark,
    primaryFixed = AppPrimaryFixed,
    primaryFixedDim = AppPrimaryFixedDim,
    onPrimaryFixed = AppOnPrimaryFixed,
    onPrimaryFixedVariant = AppOnPrimaryFixedVariant,
    secondaryFixed = AppSecondaryFixed,
    secondaryFixedDim = AppSecondaryFixedDim,
    onSecondaryFixed = AppOnSecondaryFixed,
    onSecondaryFixedVariant = AppOnSecondaryFixedVariant,
    tertiaryFixed = AppTertiaryFixed,
    tertiaryFixedDim = AppTertiaryFixedDim,
    onTertiaryFixed = AppOnTertiaryFixed,
    onTertiaryFixedVariant = AppOnTertiaryFixedVariant,
)

private fun ColorScheme.withAmoled(): ColorScheme = copy(
    background = AppSurfaceAmoled,
    surface = AppSurfaceAmoled,
    surfaceDim = AppSurfaceAmoled,
    surfaceContainerLowest = AppSurfaceAmoled,
    surfaceContainerLow = AppSurfaceContainerLowAmoled,
    surfaceContainer = AppSurfaceContainerAmoled,
    surfaceContainerHigh = AppSurfaceContainerHighAmoled,
    surfaceContainerHighest = AppSurfaceContainerHighestAmoled
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            val base = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (darkTheme && amoled) base.withAmoled() else base
        }
        darkTheme -> if (amoled) DarkColorScheme.withAmoled() else DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
