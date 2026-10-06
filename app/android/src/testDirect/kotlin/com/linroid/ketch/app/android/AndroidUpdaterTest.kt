package com.linroid.ketch.app.android

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.app.platform.AppUpdateState
import com.linroid.ketch.app.platform.AppUpdateStep
import com.linroid.ketch.updater.Release
import com.linroid.ketch.updater.ReleaseAsset
import com.linroid.ketch.updater.ReleaseFeed
import com.linroid.ketch.updater.ReleaseVersion
import com.linroid.ketch.updater.UpdateException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidUpdaterTest {
  private val version = ReleaseVersion(0, 0, 2)
  private val release = Release(
    version = version,
    pageUrl = "https://github.com/linroid/Ketch/releases/tag/v$version",
    assets = listOf(
      ReleaseAsset("ketch-android-$version.apk", "https://example.com/app.apk", 100, "00"),
    ),
  )
  private var latest = release
  private var checkFailure: Exception? = null
  private var downloadFailure: Exception? = null
  private var validationFailure: Exception? = null
  private var installFailure: Exception? = null
  private var gate: CompletableDeferred<Unit>? = null
  private var checks = 0
  private var downloads = 0
  private var validations = 0
  private val installs = mutableListOf<File>()
  private val notices = mutableListOf<AppUpdateState>()

  @Test
  fun check_newerRelease_offersTheApkWithoutInstalling() = runTest {
    val updater = available()
    assertEquals(AppUpdateState.Available("0.0.2", release.pageUrl, true), updater.state.value)
    assertEquals(0, downloads)
    assertTrue(installs.isEmpty())
    assertTrue(notices.isEmpty())
  }

  @Test
  fun check_sameOrOlderRelease_doesNotOfferDowngrade() = runTest {
    for (version in listOf(ReleaseVersion(0, 0, 1), ReleaseVersion(0, 0, 0))) {
      latest = release.copy(version = version)
      val updater = updater()
      updater.check()
      runCurrent()
      assertEquals(AppUpdateState.UpToDate, updater.state.value)
    }
  }

  @Test
  fun check_apkNotUploadedYet_canCheckAgain() = runTest {
    latest = release.copy(assets = emptyList())
    val updater = updater()
    updater.check()
    runCurrent()
    assertEquals(AppUpdateState.UpToDate, updater.state.value)
    latest = release
    updater.check()
    runCurrent()
    assertIs<AppUpdateState.Available>(updater.state.value)
  }

  @Test
  fun check_offline_manualFailureCanRetry() = runTest {
    checkFailure = UpdateException("offline")
    val updater = updater()
    updater.check()
    runCurrent()
    assertEquals(AppUpdateStep.Check, assertIs<AppUpdateState.Failed>(updater.state.value).step)
    checkFailure = null
    updater.check()
    runCurrent()
    assertIs<AppUpdateState.Available>(updater.state.value)
  }

  @Test
  fun automaticCheck_offlineIsQuiet_andCanBeDisabled() = runTest {
    checkFailure = UpdateException("offline")
    val updater = updater()
    updater.setCheckAutomatically(true)
    advanceTimeBy(30.seconds)
    runCurrent()
    assertEquals(1, checks)
    assertEquals(AppUpdateState.Idle, updater.state.value)
    assertTrue(notices.isEmpty())
    updater.setCheckAutomatically(false)
    advanceTimeBy(25.hours)
    runCurrent()
    assertEquals(1, checks)
  }

  @Test
  fun automaticCheck_releaseFound_notifiesWithoutDownloading() = runTest {
    val updater = updater()
    updater.setCheckAutomatically(true)
    advanceTimeBy(30.seconds)
    runCurrent()
    assertIs<AppUpdateState.Available>(notices.single())
    assertEquals(0, downloads)
  }

  @Test
  fun automaticCheck_debugBuild_doesNotCheck() = runTest {
    updater(automaticChecks = false).setCheckAutomatically(true)
    advanceTimeBy(25.hours)
    runCurrent()
    assertEquals(0, checks)
  }

  @Test
  fun download_repeatedCommands_areSerializedAndReportProgress() = runTest {
    val updater = available()
    gate = CompletableDeferred()
    updater.download()
    updater.download()
    runCurrent()
    updater.check()
    updater.install()
    assertEquals(1, downloads)
    assertEquals(1, checks)
    assertTrue(installs.isEmpty())
    assertEquals(50L, assertIs<AppUpdateState.Downloading>(updater.state.value).downloadedBytes)
    gate!!.complete(Unit)
    runCurrent()
    assertEquals(AppUpdateState.Ready("0.0.2", release.pageUrl, false), updater.state.value)
    assertEquals(1, validations)
    assertIs<AppUpdateState.Ready>(notices.single())
  }

  @Test
  fun download_failedChecksum_neverValidatesOrInstalls_andCanRetry() = runTest {
    val updater = available()
    downloadFailure = UpdateException("checksum mismatch")
    updater.download()
    runCurrent()
    updater.install()
    assertEquals(AppUpdateStep.Download, assertIs<AppUpdateState.Failed>(updater.state.value).step)
    assertEquals(0, validations)
    assertTrue(installs.isEmpty())
    downloadFailure = null
    updater.download()
    runCurrent()
    assertIs<AppUpdateState.Ready>(updater.state.value)
  }

  @Test
  fun download_invalidApk_neverOpensInstaller() = runTest {
    val updater = available()
    validationFailure = UpdateException("wrong package")
    updater.download()
    runCurrent()
    updater.install()
    runCurrent()
    assertEquals(AppUpdateStep.Download, assertIs<AppUpdateState.Failed>(updater.state.value).step)
    assertTrue(installs.isEmpty())
  }

  @Test
  fun install_canceledSystemDialog_canBeOpenedAgain() = runTest {
    val updater = ready()
    updater.install()
    runCurrent()
    assertIs<AppUpdateState.Ready>(updater.state.value)
    updater.install()
    runCurrent()
    assertEquals(2, installs.size)
    assertEquals(UPDATE_APK, installs.first().name)
    assertEquals(1, downloads)
  }

  @Test
  fun install_cacheEvicted_offersDownloadAgain() = runTest {
    val updater = ready()
    validationFailure = UpdateException("missing APK")
    updater.install()
    runCurrent()
    assertEquals(AppUpdateStep.Download, assertIs<AppUpdateState.Failed>(updater.state.value).step)
    assertTrue(installs.isEmpty())
    validationFailure = null
    updater.download()
    runCurrent()
    assertIs<AppUpdateState.Ready>(updater.state.value)
    assertEquals(2, downloads)
  }

  @Test
  fun install_noHandler_reportsFailureAndRetries() = runTest {
    val updater = ready()
    installFailure = IllegalStateException("no installer")
    updater.install()
    runCurrent()
    assertEquals(AppUpdateStep.Install, assertIs<AppUpdateState.Failed>(updater.state.value).step)
    installFailure = null
    updater.install()
    runCurrent()
    assertEquals(1, installs.size)
  }

  private fun TestScope.available(): AndroidUpdater = updater().also {
    it.check()
    runCurrent()
  }

  private fun TestScope.ready(): AndroidUpdater = available().also {
    it.download()
    runCurrent()
  }

  private fun TestScope.updater(automaticChecks: Boolean = true) = AndroidUpdater(
    scope = backgroundScope,
    feed = object : ReleaseFeed {
      override suspend fun latest(): Release {
        checks++
        checkFailure?.let { throw it }
        return latest
      }
      override suspend fun release(version: ReleaseVersion): Release = error("unused")
    },
    current = ReleaseVersion(0, 0, 1),
    workDir = File("unused-update-cache"),
    download = { asset, _, progress ->
      downloads++
      downloadFailure?.let { throw it }
      progress(DownloadProgress(asset.size / 2, asset.size))
      gate?.await()
    },
    validate = { _, _ ->
      validations++
      validationFailure?.let { throw it }
    },
    openInstaller = {
      installFailure?.let { throw it }
      installs += it
    },
    automaticChecks = automaticChecks,
    onNotice = { notices += it },
  )
}
