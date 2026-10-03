package com.linroid.ketch.app.snapshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.platform.AppUpdateState
import com.linroid.ketch.app.platform.AppUpdateStep
import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.platform.LocalAppUpdates
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.ui.settings.SettingsContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The desktop app's Updates group on Settings → About in each step of an update; see
 * [SnapshotHarness] for how to run it.
 */
class UpdateSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun about_eachUpdateState_showsItsNextStep() {
    val notes = "https://github.com/linroid/Ketch/releases/tag/v0.0.2"
    val states = mapOf(
      "idle" to AppUpdateState.Idle,
      "checking" to AppUpdateState.Checking,
      "current" to AppUpdateState.UpToDate,
      "available" to AppUpdateState.Available("0.0.2", notes, installable = true),
      "source" to AppUpdateState.Available("0.0.2", notes, installable = false),
      "downloading" to AppUpdateState.Downloading("0.0.2", notes, 31_457_280, 72_433_828),
      "ready" to AppUpdateState.Ready("0.0.2", notes, restarts = true),
      "failed" to AppUpdateState.Failed(
        step = AppUpdateStep.Download,
        reason = "ketch-desktop-0.0.2-macos-arm64.dmg doesn't match the checksum GitHub published",
        version = "0.0.2",
        notesUrl = notes,
      ),
    )
    for ((name, state) in states) {
      for (theme in SnapshotTheme.entries) {
        withSettings(theme, WindowSize.density) { environment ->
          SnapshotHarness.capture("about-update-$name-${theme.id}-${WindowSize.id}", WindowSize) {
            SettingsFrame(environment, theme, WindowSize.density, desktop = true) {
              CompositionLocalProvider(LocalAppUpdates provides FixedUpdates(state)) {
                SettingsContent(
                  environment.controller.state,
                  SettingsTarget(SettingsTarget.Page.About),
                  onClose = {},
                )
              }
            }
          }
        }
      }
    }
  }

  /** Updates that stay in [state]. */
  private class FixedUpdates(state: AppUpdateState) : AppUpdates {
    override val state: StateFlow<AppUpdateState> = MutableStateFlow(state)

    override fun check() {}

    override fun download() {}

    override fun install() {}

    override fun setCheckAutomatically(enabled: Boolean) {}
  }

  private companion object {
    /** The Settings window's size until the user resizes it, tall enough for the group. */
    val WindowSize = SnapshotSize(860.dp, 900.dp, KetchDensity.Compact)
  }
}
