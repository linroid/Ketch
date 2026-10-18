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
import java.util.Properties
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
 * digest GitHub published, on its own while automatic checks are on; then installs it with
 * [installer], which replaces the app once it quits and opens it again.
 *
 * Every step runs in [scope], on the main thread, one at a time. Files live in [workDir]: the
 * download, what [installer] unpacks from it, the record of a download ready to install, which
 * the next launch installs ([installDownloadedUpdate]) or [start] offers again, and the record of
 * an install in progress, which the next run reads in [start] to report how it went. [start]
 * also reports an update installed any other way, by the version that ran last.
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

  /** The release downloaded and prepared, which [install] installs. */
  private var ready: ReadyUpdate? = null
  private var step: Job? = null
  private var automaticChecks: Job? = null

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
    step = scope.launch { fetch(release) }
  }

  /**
   * Downloads [release] and readies it for [install], saving the record the next launch installs
   * from; tells the user once it is ready. Returns whether it is.
   */
  private suspend fun fetch(release: Release): Boolean {
    val installer = installer ?: return false
    val asset = asset(release) ?: return false
    val version = release.version.toString()
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
      val update = withContext(io) {
        ReadyUpdate(version, release.pageUrl, installer.prepare(file)).also { it.save(workDir) }
      }
      ready = update
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
    return next is AppUpdateState.Ready
  }

  override fun install() {
    if (step?.isActive == true) return
    val ready = ready ?: return
    val installer = installer ?: return
    val version = ready.version
    val record = File(workDir, PENDING_INSTALL)
    step = scope.launch {
      try {
        withContext(io) {
          if (installer.restarts) record.writeText(version)
          installer.install(ready.prepared, ProcessHandle.current().pid())
          // Installing now; the next launch must not install it again.
          if (installer.restarts) File(workDir, READY_UPDATE).delete()
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
          AppUpdateState.Failed(AppUpdateStep.Install, reason(e), version, ready.notesUrl)
      }
    }
  }

  override suspend fun releaseNotes(version: String, since: String?): List<ReleaseNotes> =
    feed.releasesBetween(since?.let(ReleaseVersion::parse), releaseVersion(version))
      .map { it.toNotes() }

  override suspend fun releaseHistory(first: String): List<ReleaseNotes> =
    feed.releasesFrom(releaseVersion(first)).map { it.toNotes() }

  override fun setCheckAutomatically(enabled: Boolean) {
    automaticChecks?.cancel()
    automaticChecks = null
    // An app run from source only checks when asked, so it does not nag while it is worked on.
    if (!enabled || installer == null) return
    automaticChecks = scope.launch {
      delay(firstCheckDelay)
      while (true) {
        check(automatic = true)
        delay(checkInterval)
      }
    }
  }

  /**
   * Looks for a newer release, unless one was already found or a step runs, and downloads it
   * while automatic checks are on. A failed check of its own, such as one made offline, leaves
   * the state as it was; one of its own after a failed download tries the download again.
   */
  private fun check(automatic: Boolean) {
    if (step?.isActive == true) return
    val before = mutableState.value
    val found = release
    if (automatic && found != null &&
      before is AppUpdateState.Failed && before.step == AppUpdateStep.Download
    ) {
      step = scope.launch { fetch(found) }
      return
    }
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
      if (next !is AppUpdateState.Available) return@launch
      // Downloaded on its own, it tells the user once it is ready to install.
      val fetched = next.installable && automaticChecks != null &&
        fetch(checkNotNull(release))
      // Its Update button tries a failed download again.
      if (automatic && !fetched) onEvent(UpdateEvent.Found(next))
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
   * [lastVersion] ran, made by this updater or any other way; else offers an update a run before
   * downloaded and did not install.
   */
  private suspend fun reportPreviousRun(lastVersion: ReleaseVersion?) {
    val record = File(workDir, PENDING_INSTALL)
    var downloaded: ReadyUpdate? = null
    val pending = withContext(io) {
      val text = record.takeIf { it.isFile }?.readText()?.trim()
      // After an install, taken or not, nothing is offered again.
      if (text == null && installer != null) downloaded = ReadyUpdate.read(workDir, current)
      // Otherwise the download, the unpacked app and the scripts have served their purpose.
      if (downloaded == null) workDir.deleteRecursively()
      text?.let(ReleaseVersion::parse)
    }
    val restarts = installer?.restarts ?: false
    downloaded?.let { update ->
      log.i { "Ketch ${update.version} was downloaded before and is ready to install" }
      ready = update
      val state = AppUpdateState.Ready(update.version, update.notesUrl, restarts)
      mutableState.value = state
      onEvent(UpdateEvent.Ready(state))
      return
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
}

/**
 * Starts installing the update a run before downloaded and did not install, as the app starts,
 * when [installer] replaces the app and opens it again; the caller then exits before it starts
 * anything. Leaves it to [DesktopUpdater.start] to report or offer it otherwise: after an install
 * that did not take, which is not tried again, with an installer the user finishes, or when the
 * installer cannot start.
 *
 * @param workDir the [DesktopUpdater]'s folder.
 * @param current the running version.
 * @return whether the installer started.
 */
internal fun installDownloadedUpdate(
  workDir: File,
  current: ReleaseVersion?,
  installer: UpdateInstaller?,
): Boolean {
  if (installer == null || !installer.restarts) return false
  val record = File(workDir, PENDING_INSTALL)
  if (record.exists()) return false
  val update = ReadyUpdate.read(workDir, current) ?: return false
  val log = KetchLogger("Updater")
  return try {
    record.writeText(update.version)
    installer.install(update.prepared, ProcessHandle.current().pid())
    // Kept when the installer cannot start, so the app offers it again.
    File(workDir, READY_UPDATE).delete()
    log.i { "Installing Ketch ${update.version}, downloaded before, as the app starts" }
    true
  } catch (e: Exception) {
    log.w { "Couldn't install Ketch ${update.version}: ${e.describeCauses()}" }
    record.delete()
    false
  }
}

/**
 * A release downloaded and readied for its installer.
 *
 * @property prepared what [UpdateInstaller.prepare] made of the download.
 */
internal data class ReadyUpdate(val version: String, val notesUrl: String, val prepared: File) {
  /** Saves it in [workDir], for a later launch. Blocks. */
  fun save(workDir: File) {
    val properties = Properties().apply {
      setProperty(VERSION, version)
      setProperty(NOTES_URL, notesUrl)
      setProperty(PREPARED, prepared.absolutePath)
    }
    File(workDir, READY_UPDATE).outputStream().use { properties.store(it, null) }
  }

  companion object {
    private const val VERSION = "version"
    private const val NOTES_URL = "notesUrl"
    private const val PREPARED = "prepared"

    /**
     * The update saved in [workDir], when it is newer than [current] and its files are still
     * there; `null` otherwise. Blocks.
     */
    fun read(workDir: File, current: ReleaseVersion?): ReadyUpdate? {
      val file = File(workDir, READY_UPDATE).takeIf { it.isFile } ?: return null
      val properties = try {
        Properties().apply { file.inputStream().use { load(it) } }
      } catch (_: IOException) {
        return null
      }
      val version = properties.getProperty(VERSION)?.let(ReleaseVersion::parse) ?: return null
      if (current == null || version <= current) return null
      val prepared = properties.getProperty(PREPARED)?.let(::File)?.takeIf { it.exists() }
        ?: return null
      val notesUrl = properties.getProperty(NOTES_URL).orEmpty()
      return ReadyUpdate(version.toString(), notesUrl, prepared)
    }
  }
}

/** Holds the [ReadyUpdate] a run downloaded and did not install yet. */
private const val READY_UPDATE = "ready-update.properties"

/** Holds the version an installer that restarts the app set out to install. */
private const val PENDING_INSTALL = "pending-install"

private fun releaseVersion(text: String): ReleaseVersion =
  ReleaseVersion.parse(text) ?: throw UpdateException("$text is not a release")

private fun Release.toNotes() =
  ReleaseNotes.parse(version.toString(), pageUrl, publishedAt, notes)
