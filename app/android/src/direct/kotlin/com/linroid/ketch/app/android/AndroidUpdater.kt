package com.linroid.ketch.app.android

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.platform.AppUpdateState
import com.linroid.ketch.app.platform.AppUpdateStep
import com.linroid.ketch.app.platform.AppUpdates
import com.linroid.ketch.app.platform.ReleaseNotes
import com.linroid.ketch.updater.Release
import com.linroid.ketch.updater.ReleaseAsset
import com.linroid.ketch.updater.ReleaseFeed
import com.linroid.ketch.updater.ReleaseVersion
import com.linroid.ketch.updater.UpdateException
import com.linroid.ketch.updater.releasesBetween
import com.linroid.ketch.updater.releasesFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Updates the direct Android build. Steps run on the main thread in the activity model's
 * scope, so rotation keeps the download. The downloader verifies the digest before [validate]
 * checks the APK; only an explicit [install] opens Android's installer.
 */
internal class AndroidUpdater(
  private val scope: CoroutineScope,
  private val feed: ReleaseFeed,
  private val current: ReleaseVersion?,
  private val workDir: File,
  private val download: suspend (ReleaseAsset, File, (DownloadProgress) -> Unit) -> Unit,
  private val validate: suspend (File, ReleaseVersion) -> Unit,
  private val openInstaller: (File) -> Unit,
  private val automaticChecks: Boolean = true,
  private val onNotice: (AppUpdateState) -> Unit = {},
) : AppUpdates {
  private val log = KetchLogger("AndroidUpdater")
  private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
  override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
  override val currentVersion: String? = current?.toString()
  private var release: Release? = null
  private var prepared: File? = null
  private var step: Job? = null
  private var automatic: Job? = null

  override fun check() = check(automatic = false)

  /**
   * The release that ran before this one when the app was updated since: [lastVersion], the
   * version that ran last, when it is a release older than this one. `null` on a first run,
   * after a downgrade and when either is not a release.
   */
  fun updatedFrom(lastVersion: String?): String? {
    val current = current ?: return null
    val last = lastVersion?.let(ReleaseVersion::parse) ?: return null
    return last.takeIf { it < current }?.toString()
  }

  override suspend fun releaseNotes(version: String, since: String?): List<ReleaseNotes> =
    feed.releasesBetween(since?.let(ReleaseVersion::parse), releaseVersion(version))
      .map { it.toNotes() }

  override suspend fun releaseHistory(first: String): List<ReleaseNotes> =
    feed.releasesFrom(releaseVersion(first)).map { it.toNotes() }

  private fun check(automatic: Boolean) {
    if (step?.isActive == true) return
    val before = state.value
    if (before != AppUpdateState.Idle && before != AppUpdateState.UpToDate &&
      !(before is AppUpdateState.Failed && before.step == AppUpdateStep.Check)
    ) return
    step = scope.launch {
      mutableState.value = AppUpdateState.Checking
      mutableState.value = try {
        val latest = feed.latest()
        when {
          current == null || latest.version <= current -> AppUpdateState.UpToDate
          // Releases appear before all their assets have finished uploading.
          latest.androidAsset() == null -> AppUpdateState.UpToDate
          else -> {
            release = latest
            AppUpdateState.Available(latest.version.toString(), latest.pageUrl, installable = true)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't check for updates: ${e.describeCauses()}" }
        if (automatic) before else failure(AppUpdateStep.Check, e)
      }
      if (automatic && state.value is AppUpdateState.Available) onNotice(state.value)
    }
  }

  override fun download() {
    if (step?.isActive == true) return
    val before = state.value
    if (before !is AppUpdateState.Available &&
      !(before is AppUpdateState.Failed && before.step == AppUpdateStep.Download)
    ) return
    val release = release ?: return
    val asset = release.androidAsset() ?: return
    val version = release.version.toString()
    prepared = null
    step = scope.launch {
      mutableState.value = AppUpdateState.Downloading(version, release.pageUrl, 0, asset.size)
      mutableState.value = try {
        // A fixed private path also limits what the installer and FileProvider can expose.
        val file = File(workDir, UPDATE_APK)
        download(asset, file) { progress ->
          mutableState.value = AppUpdateState.Downloading(
            version = version,
            notesUrl = release.pageUrl,
            downloadedBytes = progress.downloadedBytes,
            totalBytes = progress.totalBytes.takeIf { it > 0 } ?: asset.size,
          )
        }
        validate(file, release.version)
        prepared = file
        AppUpdateState.Ready(version, release.pageUrl, restarts = false)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't download the update: ${e.describeCauses()}" }
        failure(AppUpdateStep.Download, e)
      }
      if (state.value is AppUpdateState.Ready) onNotice(state.value)
    }
  }

  override fun install() {
    if (step?.isActive == true) return
    val file = prepared ?: return
    val release = release ?: return
    step = scope.launch {
      try {
        // Cache eviction or a changed file must not leave a permanently broken Install button.
        validate(file, release.version)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        prepared = null
        mutableState.value = failure(AppUpdateStep.Download, e)
        return@launch
      }
      try {
        openInstaller(file)
        // Handing off is not installation success. Canceling Android's dialog allows retry.
        mutableState.value = AppUpdateState.Ready(
          release.version.toString(), release.pageUrl, restarts = false,
        )
      } catch (e: Exception) {
        log.w { "Couldn't open the installer: ${e.describeCauses()}" }
        mutableState.value = failure(AppUpdateStep.Install, e)
      }
    }
  }

  override fun setCheckAutomatically(enabled: Boolean) {
    automatic?.cancel()
    automatic = null
    if (!enabled || !automaticChecks) return
    automatic = scope.launch {
      delay(30.seconds)
      while (true) {
        check(automatic = true)
        delay(24.hours)
      }
    }
  }

  private fun failure(step: AppUpdateStep, error: Exception) = AppUpdateState.Failed(
    step = step,
    reason = error.describeCauses(),
    version = release?.version?.toString(),
    notesUrl = release?.pageUrl,
  )
}

internal const val UPDATE_APK = "ketch-update.apk"
internal const val UPDATES_DIR = "updates"

private fun releaseVersion(text: String): ReleaseVersion =
  ReleaseVersion.parse(text) ?: throw UpdateException("$text is not a release")

private fun Release.toNotes() =
  ReleaseNotes.parse(version.toString(), pageUrl, publishedAt, notes)
