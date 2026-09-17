// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package heitezy.motionsicknessrelief.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = resolveColorScheme(LocalContext.current, darkTheme, dynamicColor)
  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

/**
 * Same color-scheme selection logic [MyApplicationTheme] uses, exposed as a plain function so
 * non-Compose call sites (e.g. the overlay [android.app.Service], which never enters
 * composition) can resolve the same Material-You-aware palette used on screen.
 */
fun resolveColorScheme(context: Context, darkTheme: Boolean, dynamicColor: Boolean = true): ColorScheme =
  when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicColorScheme(context, darkTheme)
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

/**
 * The three theme "slots" (primary/secondary/tertiary) Motion Assist-style color customization
 * lets a person pick between, resolved to ARGB ints so the overlay's plain-Canvas [Paint] can
 * use them directly. Index matches [heitezy.motionsicknessrelief.data.CueColorSlot.ordinal].
 */
fun cueColorPalette(context: Context): IntArray {
  val darkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
    Configuration.UI_MODE_NIGHT_YES
  val scheme = resolveColorScheme(context, darkTheme)
  return intArrayOf(scheme.primary.toArgb(), scheme.secondary.toArgb(), scheme.tertiary.toArgb())
}

@RequiresApi(Build.VERSION_CODES.S)
private fun dynamicColorScheme(context: Context, darkTheme: Boolean): ColorScheme =
  if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
