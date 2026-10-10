package com.linroid.ketch.app.snapshot

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.platform.AppUpdateState
import com.linroid.ketch.app.platform.AppUpdateStep
import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.platform.LocalAppUpdates
import com.linroid.ketch.app.platform.ReleaseNotes
import com.linroid.ketch.app.platform.ReleaseNotesRequest
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.ui.settings.ReleaseHistoryDialog
import com.linroid.ketch.app.ui.settings.ReleaseNotesDialog
import com.linroid.ketch.app.ui.settings.SettingsContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The desktop app's Updates group on Settings → About in each step of an update, and the release
 * notes dialog; see [SnapshotHarness] for how to run it.
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

  @Test
  fun releaseNotes_eachForm_listsWhatChanged() {
    val oneRelease = listOf(SampleNotes.first())
    val scenarios = listOf(
      NotesScenario("release-notes-available", available("0.3.2"), SampleNotes, since = "0.3.0"),
      NotesScenario("release-notes-installed", AppUpdateState.UpToDate, oneRelease),
      NotesScenario("release-notes-quiet", AppUpdateState.UpToDate, listOf(quietRelease())),
      NotesScenario("release-notes-failed", AppUpdateState.UpToDate, failure = GitHubLimit),
    )
    for (scenario in scenarios) {
      for ((size, theme) in listOf(
        SnapshotSize.Desktop to SnapshotTheme.Light,
        SnapshotSize.Desktop to SnapshotTheme.Dark,
        SnapshotSize.Phone to SnapshotTheme.Light,
      )) {
        val updates = FixedUpdates(scenario.state, scenario.notes, scenario.failure)
        val request = ReleaseNotesRequest(version = "0.3.2", since = scenario.since)
        snapshot(scenario.name, size, theme) {
          CompositionLocalProvider(LocalClock provides SampleData.CLOCK) {
            ReleaseNotesDialog(updates, request, onDismiss = {})
          }
        }
      }
    }
  }

  @Test
  fun releaseHistory_listsEveryReleaseWithTheNewestOpen() {
    for ((size, theme) in listOf(
      SnapshotSize.Desktop to SnapshotTheme.Light,
      SnapshotSize.Desktop to SnapshotTheme.Dark,
      SnapshotSize.Phone to SnapshotTheme.Light,
    )) {
      val updates = FixedUpdates(AppUpdateState.UpToDate, SampleNotes + quietRelease("0.3.0"))
      snapshot("release-history", size, theme) {
        CompositionLocalProvider(LocalClock provides SampleData.CLOCK) {
          ReleaseHistoryDialog(updates, version = "0.3.2", onDismiss = {})
        }
      }
    }
  }

  /** A dialog of notes as [updates] in [state] reads them, from [since]. */
  private class NotesScenario(
    val name: String,
    val state: AppUpdateState,
    val notes: List<ReleaseNotes> = emptyList(),
    val since: String? = null,
    val failure: Exception? = null,
  )

  /** Updates that stay in [state] and read [notes], or fail with [failure]. */
  private class FixedUpdates(
    state: AppUpdateState,
    private val notes: List<ReleaseNotes> = emptyList(),
    private val failure: Exception? = null,
  ) : AppUpdates {
    override val state: StateFlow<AppUpdateState> = MutableStateFlow(state)
    override val currentVersion: String = "0.3.0"

    override fun check() {}

    override fun download() {}

    override fun install() {}

    override fun setCheckAutomatically(enabled: Boolean) {}

    override suspend fun releaseNotes(version: String, since: String?): List<ReleaseNotes> {
      failure?.let { throw it }
      return notes
    }

    override suspend fun releaseHistory(version: String, first: String): List<ReleaseNotes> {
      failure?.let { throw it }
      return notes
    }
  }

  private companion object {
    /** The Settings window's size until the user resizes it, tall enough for the group. */
    val WindowSize = SnapshotSize(860.dp, 900.dp, KetchDensity.Compact)

    val GitHubLimit = IllegalStateException(
      "GitHub refused the request (HTTP 403), likely its hourly limit; try again later",
    )

    /** The notes of two releases since 0.3.0, as GitHub generates them. */
    val SampleNotes = listOf(
      notes(
        "0.3.2",
        "2026-09-30T09:12:00Z",
        firstPull = 450,
        "feat(app): show the release notes when an update is available or installed",
        "feat(torrent): keep looking for magnet metadata and widen tracker lists",
        "fix(android): count starting tasks as downloading in the ongoing notification",
        "fix(desktop): keep JVM warnings off the native messaging host's stdout",
        "perf: shrink the desktop, Android and CLI releases",
        "chore(deps): update dependency io.ktor to v3.5.2",
      ),
      notes(
        "0.3.1",
        "2026-09-26T16:40:00Z",
        firstPull = 430,
        "fix(ai): call versioned OpenAI-compatible endpoints at chat/completions",
        "feat(app): pause and resume downloads with a double-click",
      ),
    )

    fun available(version: String) = AppUpdateState.Available(
      version = version,
      notesUrl = "https://github.com/linroid/Ketch/releases/tag/v$version",
      installable = true,
    )

    fun quietRelease(version: String = "0.3.2") =
      notes(version, "2026-09-30T09:12:00Z", firstPull = 460, "ci: cache the Gradle wrapper")

    /** Notes listing pull requests titled [titles], numbered from [firstPull]. */
    fun notes(
      version: String,
      published: String,
      firstPull: Int,
      vararg titles: String,
    ) = ReleaseNotes.parse(
      version = version,
      pageUrl = "https://github.com/linroid/Ketch/releases/tag/v$version",
      publishedAt = Instant.parse(published),
      markdown = titles.mapIndexed { index, title ->
        "* $title by @linroid in https://github.com/linroid/Ketch/pull/${firstPull + index}"
      }.joinToString("\n"),
    )
  }
}
