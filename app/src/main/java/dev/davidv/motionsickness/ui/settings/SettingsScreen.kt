// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.ui.settings

import android.Manifest
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.davidv.motionsickness.R
import dev.davidv.motionsickness.data.CueColorSlot
import dev.davidv.motionsickness.data.CueSettings
import dev.davidv.motionsickness.data.CueSettingsRepository
import dev.davidv.motionsickness.data.DotShape
import dev.davidv.motionsickness.motion.CueMode
import dev.davidv.motionsickness.motion.MotionFusionMode
import dev.davidv.motionsickness.motion.VehicleDetection
import dev.davidv.motionsickness.motion.VehicleDetectionReceiver
import kotlinx.coroutines.launch

/**
 * Customization screen covering the same knobs Android's own Motion Assist exposes: dot
 * placement, shape, color (sourced from the device's Material You theme), opacity, a
 * randomizer, and automatic activation in a moving vehicle.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val repository = remember { CueSettingsRepository(context.applicationContext) }
  val scope = rememberCoroutineScope()
  val settings by repository.settings.collectAsStateWithLifecycle(initialValue = CueSettings())

  val autoStartPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { granted ->
    if (granted) {
      VehicleDetection.start(context)
      scope.launch { repository.setAutoStart(true) }
    }
  }

  Column(
    modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

    SettingsSection(stringResource(R.string.settings_mode_title)) {
      SegmentedChoice(
        options = CueMode.entries,
        selected = settings.mode,
        label = {
          when (it) {
            CueMode.Uniform -> stringResource(R.string.settings_mode_uniform)
            CueMode.Focus -> stringResource(R.string.settings_mode_focus)
            CueMode.Columns -> stringResource(R.string.settings_mode_columns)
          }
        },
        onSelect = { scope.launch { repository.setMode(it) } },
      )
    }

    SettingsSection(stringResource(R.string.settings_shape_title)) {
      SegmentedChoice(
        options = DotShape.entries,
        selected = settings.shape,
        label = {
          when (it) {
            DotShape.Circle -> stringResource(R.string.settings_shape_circle)
            DotShape.Diamond -> stringResource(R.string.settings_shape_diamond)
            DotShape.Meteoroid -> stringResource(R.string.settings_shape_meteoroid)
          }
        },
        onSelect = { scope.launch { repository.setShape(it) } },
        enabled = !settings.randomize,
      )
    }

    SettingsSection(stringResource(R.string.settings_color_title)) {
      SegmentedChoice(
        options = CueColorSlot.entries,
        selected = settings.colorSlot,
        label = {
          when (it) {
            CueColorSlot.Primary -> stringResource(R.string.settings_color_primary)
            CueColorSlot.Secondary -> stringResource(R.string.settings_color_secondary)
            CueColorSlot.Tertiary -> stringResource(R.string.settings_color_tertiary)
          }
        },
        onSelect = { scope.launch { repository.setColorSlot(it) } },
        enabled = !settings.randomize,
      )
    }

    SettingsSection(stringResource(R.string.settings_opacity_title)) {
      Slider(
        value = settings.opacity,
        onValueChange = { scope.launch { repository.setOpacity(it) } },
        valueRange = CueSettings.MIN_OPACITY..1f,
      )
    }

    HorizontalDivider()

    SettingsSection(stringResource(R.string.settings_motion_feel_title)) {
      SegmentedChoice(
        options = MotionFusionMode.entries,
        selected = settings.motionFusionMode,
        label = {
          when (it) {
            MotionFusionMode.WorldRelative -> stringResource(R.string.settings_motion_feel_world_relative)
            MotionFusionMode.Raw -> stringResource(R.string.settings_motion_feel_raw)
          }
        },
        onSelect = { scope.launch { repository.setMotionFusionMode(it) } },
      )
      Text(
        when (settings.motionFusionMode) {
          MotionFusionMode.WorldRelative -> stringResource(R.string.settings_motion_feel_world_relative_subtitle)
          MotionFusionMode.Raw -> stringResource(R.string.settings_motion_feel_raw_subtitle)
        },
        style = MaterialTheme.typography.bodySmall,
      )
    }

    HorizontalDivider()

    ToggleRow(
      title = stringResource(R.string.settings_randomize_title),
      subtitle = stringResource(R.string.settings_randomize_subtitle),
      checked = settings.randomize,
      onCheckedChange = { enabled -> scope.launch { repository.setRandomize(enabled) } },
    )

    HorizontalDivider()

    ToggleRow(
      title = stringResource(R.string.settings_autostart_title),
      subtitle = stringResource(R.string.settings_autostart_subtitle),
      checked = settings.autoStart,
      onCheckedChange = { enabled ->
        if (!enabled) {
          VehicleDetection.stop(context)
          scope.launch { repository.setAutoStart(false) }
          return@ToggleRow
        }
        if (VehicleDetection.hasPermission(context)) {
          VehicleDetection.start(context)
          scope.launch { repository.setAutoStart(true) }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          autoStartPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
      },
    )

    val isDebugBuild = remember {
      context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }
    if (isDebugBuild) {
      HorizontalDivider()
      SettingsSection("Debug: simulate vehicle transition") {
        Text(
          "Play Services doesn't offer a way to fake a real transition, so these call the " +
            "exact same start/stop path a real vehicle enter/exit would, for testing without a car.",
          style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { VehicleDetectionReceiver.simulateTransition(context, entering = true) }) {
            Text("Simulate enter")
          }
          Button(onClick = { VehicleDetectionReceiver.simulateTransition(context, entering = false) }) {
            Text("Simulate exit")
          }
        }
      }
    }
  }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    content()
  }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedChoice(
  options: List<T>,
  selected: T,
  label: @Composable (T) -> String,
  onSelect: (T) -> Unit,
  enabled: Boolean = true,
) {
  SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
    options.forEachIndexed { index, option ->
      SegmentedButton(
        selected = option == selected,
        onClick = { onSelect(option) },
        enabled = enabled,
        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
      ) { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
  }
}
