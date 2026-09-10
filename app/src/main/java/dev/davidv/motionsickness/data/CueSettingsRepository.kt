// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.davidv.motionsickness.motion.CueMode
import dev.davidv.motionsickness.motion.MotionFusionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.cueSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "cue_settings")

enum class DotShape { Circle, Diamond, Meteoroid }

enum class CueColorSlot { Primary, Secondary, Tertiary }

data class CueSettings(
  val mode: CueMode = CueMode.Focus,
  val shape: DotShape = DotShape.Circle,
  val colorSlot: CueColorSlot = CueColorSlot.Primary,
  val opacity: Float = DEFAULT_OPACITY,
  val randomize: Boolean = false,
  val autoStart: Boolean = false,
  val motionFusionMode: MotionFusionMode = MotionFusionMode.WorldRelative,
) {
  companion object {
    const val DEFAULT_OPACITY = 0.85f
    const val MIN_OPACITY = 0.1f
  }
}

class CueSettingsRepository(context: Context) {
  private val dataStore = context.applicationContext.cueSettingsDataStore

  private object Keys {
    val MODE = stringPreferencesKey("mode")
    val SHAPE = stringPreferencesKey("shape")
    val COLOR_SLOT = stringPreferencesKey("color_slot")
    val OPACITY = floatPreferencesKey("opacity")
    val RANDOMIZE = booleanPreferencesKey("randomize")
    val AUTO_START = booleanPreferencesKey("auto_start")
    val MOTION_FUSION_MODE = stringPreferencesKey("motion_fusion_mode")
  }

  val settings: Flow<CueSettings> =
    dataStore.data.map { prefs ->
      CueSettings(
        mode = prefs.enumOrDefault(Keys.MODE, CueMode.Focus),
        shape = prefs.enumOrDefault(Keys.SHAPE, DotShape.Circle),
        colorSlot = prefs.enumOrDefault(Keys.COLOR_SLOT, CueColorSlot.Primary),
        opacity = (prefs[Keys.OPACITY] ?: CueSettings.DEFAULT_OPACITY).coerceIn(CueSettings.MIN_OPACITY, 1f),
        randomize = prefs[Keys.RANDOMIZE] ?: false,
        autoStart = prefs[Keys.AUTO_START] ?: false,
        motionFusionMode = prefs.enumOrDefault(Keys.MOTION_FUSION_MODE, MotionFusionMode.WorldRelative),
      )
    }

  suspend fun setMode(mode: CueMode) {
    dataStore.edit { it[Keys.MODE] = mode.name }
  }

  suspend fun setShape(shape: DotShape) {
    dataStore.edit { it[Keys.SHAPE] = shape.name }
  }

  suspend fun setColorSlot(slot: CueColorSlot) {
    dataStore.edit { it[Keys.COLOR_SLOT] = slot.name }
  }

  suspend fun setOpacity(opacity: Float) {
    dataStore.edit { it[Keys.OPACITY] = opacity.coerceIn(CueSettings.MIN_OPACITY, 1f) }
  }

  suspend fun setRandomize(enabled: Boolean) {
    dataStore.edit { it[Keys.RANDOMIZE] = enabled }
  }

  suspend fun setAutoStart(enabled: Boolean) {
    dataStore.edit { it[Keys.AUTO_START] = enabled }
  }

  suspend fun setMotionFusionMode(mode: MotionFusionMode) {
    dataStore.edit { it[Keys.MOTION_FUSION_MODE] = mode.name }
  }

  private inline fun <reified T : Enum<T>> Preferences.enumOrDefault(
    key: Preferences.Key<String>,
    default: T,
  ): T = this[key]?.let { stored -> runCatching { enumValueOf<T>(stored) }.getOrNull() } ?: default
}
