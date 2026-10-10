package com.linroid.ketch.app.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** A step of updating the app, which [AppUpdateState.Failed] names. */
enum class AppUpdateStep { Check, Download, Install }

/** Where the app's update to a newer release stands. */
sealed interface AppUpdateState {
  /** Nothing checked since the app started. */
  data object Idle : AppUpdateState

  /** Asking for the latest release. */
  data object Checking : AppUpdateState

  /** The app is the latest release. */
  data object UpToDate : AppUpdateState

  /**
   * A newer release is out.
   *
   * @property version its version, such as `0.0.2`.
   * @property notesUrl web page with its release notes and installers.
   * @property installable whether the app can download and install it; when it cannot, as when
   *   it runs from source, the release page has the installers.
   */
  data class Available(
    val version: String,
    val notesUrl: String,
    val installable: Boolean,
  ) : AppUpdateState

  /** Downloading [version]: [downloadedBytes] of [totalBytes] so far. */
  data class Downloading(
    val version: String,
    val notesUrl: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
  ) : AppUpdateState

  /**
   * [version] is downloaded and checked.
   *
   * @property restarts whether installing it quits the app, installs it and opens it again;
   *   otherwise its installer opens for the user to finish.
   */
  data class Ready(
    val version: String,
    val notesUrl: String,
    val restarts: Boolean,
  ) : AppUpdateState

  /**
   * [step] failed.
   *
   * @property reason what went wrong, in English as the updater reports it.
   * @property version the release it concerned, once one was found.
   */
  data class Failed(
    val step: AppUpdateStep,
    val reason: String,
    val version: String? = null,
    val notesUrl: String? = null,
  ) : AppUpdateState
}

/**
 * The app updating itself to the latest release. The desktop and direct Android builds provide it
 * through [LocalAppUpdates]; app stores update the other builds.
 */
interface AppUpdates {
  /** Where the update stands. */
  val state: StateFlow<AppUpdateState>

  /** The release the app runs, such as `0.3.1`; `null` for a build that is not a release. */
  val currentVersion: String?

  /** Looks for a newer release now. */
  fun check()

  /** Downloads the release that [AppUpdateState.Available] or a failed download names. */
  fun download()

  /** Installs the downloaded release: quits, installs and restarts, or opens its installer. */
  fun install()

  /**
   * Makes the app look for a newer release once a day, or stop. Callers save `[desktop]
   * checkForUpdates` themselves.
   */
  fun setCheckAutomatically(enabled: Boolean)

  /**
   * The notes of the releases after [since] up to [version], newest first, from where the
   * updater finds its releases; only [version]'s when [since] is `null` or not an older release.
   *
   * @throws Exception when they cannot be read, with a message that says why.
   */
  suspend fun releaseNotes(version: String, since: String?): List<ReleaseNotes>
}

/** [AppUpdates] of the running app, `null` where the app does not update itself. */
val LocalAppUpdates: ProvidableCompositionLocal<AppUpdates?> = staticCompositionLocalOf { null }
