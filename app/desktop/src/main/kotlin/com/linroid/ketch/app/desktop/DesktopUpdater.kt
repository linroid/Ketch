package com.linroid.ketch.app.desktop

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
import com.linroid.ketch.updater.ReleasePlatform
import com.linroid.ketch.updater.ReleaseProduct
import com.linroid.ketch.updater.ReleaseVersion
import com.linroid.ketch.updater.UpdateException
import com.linroid.ketch.updater.releasesBetween
import com.linroid.ketch.updater.releasesFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** What the updater tells the user about outside Settings, as toasts. */
internal sealed interface UpdateEvent {
  /** A check of its own found [update]. */
  data class Found(val update: AppUpdateState.Available) : UpdateEvent

  /** [update] is downloaded and can be installed. */
  data class Ready(val update: AppUpdateState.Ready) : UpdateEvent

  /**
   * The app now runs [version], newer than the run before, which ran [since]; `null` when that
   * run was of a release that did not record its version.
   */
  data class Installed(val version: String, val since: String?) : UpdateEvent

  /** The run before set out to install [version], and the app does not run it. */
  data class InstallFailed(val version: String) : UpdateEvent
}

/**
 * Updates the desktop app to the latest release: looks for one once a day while automatic
 * checks are on, and when asked; downloads this system's installer and checks it against the
 * digest GitHub published; then installs it with [installer], which replaces the app once it
 * quits and opens it again.
 *
 * Every step runs in [scope], on the main thread, one at a time. Files live in [workDir]: the
 * download, what [installer] unpacks from it, and the record of an install in progress, which
 * the next run reads in [start] to report how it went. [start] also reports an update installed
 * any other way, by the version that ran last.
 *
 * @param current the running version; `null` when it is not a release version.
 * @param platform the system the installers are for; `null` where Ketch is not released.
 * @param installer installs a downloaded release; `null` when the app runs from source, which
 *   checks only when asked and links to the release page.
 * @param download downloads a release file and checks its digest.
 * @param quit quits the app without asking, once an installer that restarts it waits.
 * @param onEvent tells the user what happened outside Settings.
 */
internal class DesktopUpdater(
  private val scope: CoroutineScope,
  private val feed: ReleaseFeed,
  private val current: ReleaseVersion?,
  private val platform: ReleasePlatform?,
  private val installer: UpdateInstaller?,
  private val workDir: File,
  private val download: suspend (ReleaseAsset, File, (DownloadProgress) -> Unit) -> Unit,
  private val quit: () -> Unit,
  private val onEvent: (UpdateEvent) -> Unit,
  private val io: CoroutineDispatcher = Dispatchers.IO,
  private val firstCheckDelay: Duration = 30.seconds,
  private val checkInterval: Duration = 24.hours,
) : AppUpdates {
  private val log = KetchLogger("Updater")
  private val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
  override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
  override val currentVersion: String? = current?.toString()

  /** The newer release found, which [download] fetches. */
  private var release: Release? = null

  /** What [UpdateInstaller.prepare] made of the download, which [install] installs. */
  private var prepared: File? = null
  private var step: Job? = null
  private var automatic: Job? = null

  /**
   * Reports the update since the run before, which ran [lastVersion], or its install that did
   * not take, clears the files it left and starts the automatic checks when
   * [checkAutomatically]. Call once, at launch.
   */
  fun start(checkAutomatically: Boolean, lastVersion: String?) {
    scope.launch { reportPreviousRun(lastVersion?.let(ReleaseVersion::parse)) }
    setCheckAutomatically(checkAutomatically)
  }

  override fun check() {
    check(automatic = false)
  }

  override fun download() {
    if (step?.isActive == true) return
    val release = release ?: return
    val installer = installer ?: return
    val asset = asset(release) ?: return
    val version = release.version.toString()
    step = scope.launch {
      mutableState.value = AppUpdateState.Downloading(version, release.pageUrl, 0, asset.size)
      val next = try {
        val file = File(workDir, asset.name)
        download(asset, file) { progress ->
          mutableState.value = AppUpdateState.Downloading(
            version = version,
            notesUrl = release.pageUrl,
            downloadedBytes = progress.downloadedBytes,
            totalBytes = progress.totalBytes.takeIf { it > 0 } ?: asset.size,
          )
        }
        prepared = withContext(io) { installer.prepare(file) }
        log.i { "Ketch $version is ready to install" }
        AppUpdateState.Ready(version, release.pageUrl, installer.restarts)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't download Ketch $version: ${e.describeCauses()}" }
        AppUpdateState.Failed(AppUpdateStep.Download, reason(e), version, release.pageUrl)
      }
      mutableState.value = next
      if (next is AppUpdateState.Ready) onEvent(UpdateEvent.Ready(next))
    }
  }

  override fun install() {
    if (step?.isActive == true) return
    val release = release ?: return
    val prepared = prepared ?: return
    val installer = installer ?: return
    val version = release.version.toString()
    val record = File(workDir, PENDING_INSTALL)
    step = scope.launch {
      try {
        withContext(io) {
          if (installer.restarts) record.writeText(version)
          installer.install(prepared, ProcessHandle.current().pid())
        }
        if (installer.restarts) {
          log.i { "Quitting to install Ketch $version" }
          quit()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't install Ketch $version: ${e.describeCauses()}" }
        withContext(io) { record.delete() }
        mutableState.value =
          AppUpdateState.Failed(AppUpdateStep.Install, reason(e), version, release.pageUrl)
      }
    }
  }

  override suspend fun releaseNotes(version: String, since: String?): List<ReleaseNotes> =
    feed.releasesBetween(since?.let(ReleaseVersion::parse), releaseVersion(version))
      .map { it.toNotes() }

  override suspend fun releaseHistory(first: String): List<ReleaseNotes> =
    feed.releasesFrom(releaseVersion(first)).map { it.toNotes() }

  override fun setCheckAutomatically(enabled: Boolean) {
    automatic?.cancel()
    automatic = null
    // An app run from source only checks when asked, so it does not nag while it is worked on.
    if (!enabled || installer == null) return
    automatic = scope.launch {
      delay(firstCheckDelay)
      while (true) {
        check(automatic = true)
        delay(checkInterval)
      }
    }
  }

  /**
   * Looks for a newer release, unless one was already found or a step runs. A failed check of
   * its own, such as one made offline, leaves the state as it was.
   */
  private fun check(automatic: Boolean) {
    if (step?.isActive == true) return
    val before = mutableState.value
    val checkable = before is AppUpdateState.Idle || before is AppUpdateState.UpToDate ||
      (before is AppUpdateState.Failed && before.step == AppUpdateStep.Check)
    if (!checkable) return
    step = scope.launch {
      mutableState.value = AppUpdateState.Checking
      val next = try {
        val latest = feed.latest()
        when {
          current != null && latest.version <= current -> AppUpdateState.UpToDate
          // A release is published before its files are uploaded; a later check finds them.
          installer != null && asset(latest) == null -> {
            log.i { "Ketch ${latest.version} has no installer for $platform yet" }
            AppUpdateState.UpToDate
          }
          else -> {
            log.i { "Ketch ${latest.version} is available; this is $current" }
            release = latest
            available(latest)
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't check for updates: ${e.describeCauses()}" }
        if (automatic) before else AppUpdateState.Failed(AppUpdateStep.Check, reason(e))
      }
      mutableState.value = next
      if (automatic && next is AppUpdateState.Available) onEvent(UpdateEvent.Found(next))
    }
  }

  private fun available(release: Release) = AppUpdateState.Available(
    version = release.version.toString(),
    notesUrl = release.pageUrl,
    installable = installer != null && asset(release) != null,
  )

  /** The file of [release] that [installer] installs on this system, or `null` without one. */
  private fun asset(release: Release): ReleaseAsset? =
    platform?.let { release.asset(installer?.product ?: ReleaseProduct.Desktop, it) }

  /**
   * Reports an install the run before set out to make that did not take, or an update since
   * [lastVersion] ran, made by this updater or any other way.
   */
  private suspend fun reportPreviousRun(lastVersion: ReleaseVersion?) {
    val record = File(workDir, PENDING_INSTALL)
    val pending = withContext(io) {
      val text = record.takeIf { it.isFile }?.readText()?.trim()
      // The download, the unpacked app and the scripts have served their purpose.
      workDir.deleteRecursively()
      text?.let(ReleaseVersion::parse)
    }
    val event = when {
      pending != null && pending != current -> {
        log.w { "The update to Ketch $pending did not install; this is $current" }
        UpdateEvent.InstallFailed(pending.toString())
      }
      current == null -> return
      lastVersion != null && lastVersion < current -> {
        log.i { "Updated to Ketch $current from $lastVersion" }
        UpdateEvent.Installed(current.toString(), since = lastVersion.toString())
      }
      // Installed by a release that did not record its version.
      pending != null -> {
        log.i { "Updated to Ketch $current" }
        UpdateEvent.Installed(current.toString(), since = null)
      }
      else -> return
    }
    onEvent(event)
  }

  private fun reason(e: Exception): String = when (e) {
    is UpdateException, is IOException -> e.message ?: e.describeCauses()
    else -> e.describeCauses()
  }

  private companion object {
    /** Holds the version an installer that restarts the app set out to install. */
    const val PENDING_INSTALL = "pending-install"
  }
}

private fun releaseVersion(text: String): ReleaseVersion =
  ReleaseVersion.parse(text) ?: throw UpdateException("$text is not a release")

private fun Release.toNotes() =
  ReleaseNotes.parse(version.toString(), pageUrl, publishedAt, notes)
