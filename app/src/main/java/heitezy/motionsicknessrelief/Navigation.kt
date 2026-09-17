// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package heitezy.motionsicknessrelief

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import heitezy.motionsicknessrelief.ui.about.AboutScreen
import heitezy.motionsicknessrelief.ui.main.MainScreen
import heitezy.motionsicknessrelief.ui.settings.SettingsScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(onItemClick = { navKey -> backStack.add(navKey) }, modifier = Modifier.safeDrawingPadding().padding(16.dp))
        }
        entry<Settings> {
          SettingsScreen(onBack = { backStack.removeLastOrNull() }, modifier = Modifier.safeDrawingPadding().padding(16.dp))
        }
        entry<About> {
          AboutScreen(onBack = { backStack.removeLastOrNull() }, modifier = Modifier.safeDrawingPadding().padding(16.dp))
        }
      },
  )
}
