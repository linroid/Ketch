package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.app.platform.AppUpdateState
import com.linroid.ketch.app.platform.AppUpdateStep
import com.linroid.ketch.updater.Release
import com.linroid.ketch.updater.ReleaseArch
import com.linroid.ketch.updater.ReleaseAsset
import com.linroid.ketch.updater.ReleaseFeed
import com.linroid.ketch.updater.ReleaseOs
import com.linroid.ketch.updater.ReleasePlatform
import com.linroid.ketch.updater.ReleaseVersion
import com.linroid.ketch.updater.UpdateException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class DesktopUpdaterTest {
  private val workDir = Files.createTempDirectory("ketch-updater").toFile()
  private val platform = ReleasePlatform(ReleaseOs.MacOs, ReleaseArch.Arm64)
  private val newer = release("0.0.2")
  private val feed = FakeFeed(newer)
  private val installer = FakeInstaller()
  private val events = mutableListOf<UpdateEvent>()
  private val downloads = mutableListOf<ReleaseAsset>()
  private var downloadFails = false
  private var quits = 0

  @AfterTest
  fun cleanUp() {
    workDir.deleteRecursively()
  }

  @Test
  fun check_newerRelease_isAvailableWithoutAToast() = runTest {
    val updater = updater()
    updater.check()
    runCurrent()

    assertEquals(
      AppUpdateState.Available("0.0.2", newer.pageUrl, installable = true),
      updater.state.value,
    )
    assertTrue(events.isEmpty())
  }

  @Test
  fun check_sameVersion_isUpToDate() = runTest {
    val updater = updater(current = "0.0.2")
    updater.check()
    runCurrent()

    assertEquals(AppUpdateState.UpToDate, updater.state.value)
  }

  @Test
  fun check_releaseWithoutThisSystemsInstaller_isUpToDateUntilItIsUploaded() = runTest {
    feed.latest = newer.copy(assets = emptyList())
    val updater = updater()
    updater.check()
    runCurrent()
    assertEquals(AppUpdateState.UpToDate, updater.state.value)

    feed.latest = newer
    updater.check()
    runCurrent()
    assertIs<AppUpdateState.Available>(updater.state.value)
  }

  @Test
  fun check_failure_isFailed() = runTest {
    feed.failure = UpdateException("GitHub is down")
    val updater = updater()
    updater.check()
    runCurrent()

    assertEquals(AppUpdateState.Failed(AppUpdateStep.Check, "GitHub is down"), updater.state.value)
  }

  @Test
  fun automaticCheck_newerRelease_postsAToastOnceADay() = runTest {
    val updater = updater()
    updater.setCheckAutomatically(true)
    advanceTimeBy(31.seconds)
    runCurrent()

    val found = assertIs<UpdateEvent.Found>(events.single())
    assertEquals("0.0.2", found.update.version)
    // A release already found is not looked up again.
    advanceTimeBy(25.hours)
    runCurrent()
    assertEquals(1, feed.calls)
  }

  @Test
  fun automaticCheck_failure_keepsTheStateItHad() = runTest {
    feed.failure = UpdateException("offline")
    val updater = updater()
    updater.setCheckAutomatically(true)
    advanceTimeBy(31.seconds)
    runCurrent()

    assertEquals(AppUpdateState.Idle, updater.state.value)
    advanceTimeBy(24.hours)
    runCurrent()
    assertEquals(2, feed.calls)
  }

  @Test
  fun automaticCheck_runFromSource_neverRuns() = runTest {
    val updater = updater(installer = null)
    updater.setCheckAutomatically(true)
    advanceTimeBy(48.hours)
    runCurrent()

    assertEquals(0, feed.calls)
  }

  @Test
  fun check_runFromSource_isNotInstallable() = runTest {
    val updater = updater(installer = null)
    updater.check()
    runCurrent()

    assertEquals(false, assertIs<AppUpdateState.Available>(updater.state.value).installable)
  }

  @Test
  fun download_success_preparesTheInstallerAndIsReady() = runTest {
    val updater = available()
    updater.download()
    runCurrent()

    assertEquals("ketch-desktop-0.0.2-macos-arm64.dmg", downloads.single().name)
    assertEquals(File(workDir, "ketch-desktop-0.0.2-macos-arm64.dmg"), installer.prepared.single())
    val ready = AppUpdateState.Ready("0.0.2", newer.pageUrl, restarts = true)
    assertEquals(ready, updater.state.value)
    assertEquals(listOf<UpdateEvent>(UpdateEvent.Ready(ready)), events)
  }

  @Test
  fun download_failure_keepsTheReleaseToTryAgain() = runTest {
    downloadFails = true
    val updater = available()
    updater.download()
    runCurrent()

    val failed = assertIs<AppUpdateState.Failed>(updater.state.value)
    assertEquals(AppUpdateStep.Download, failed.step)
    assertEquals("0.0.2", failed.version)

    downloadFails = false
    updater.download()
    runCurrent()
    assertIs<AppUpdateState.Ready>(updater.state.value)
  }

  @Test
  fun install_restarting_recordsTheVersionAndQuits() = runTest {
    val updater = ready()
    updater.install()
    runCurrent()

    assertEquals(ProcessHandle.current().pid(), installer.installed.single().second)
    assertEquals("0.0.2", File(workDir, "pending-install").readText())
    assertEquals(1, quits)
  }

  @Test
  fun install_openingTheInstaller_staysOpen() = runTest {
    installer.restarts = false
    val updater = ready()
    updater.install()
    runCurrent()

    assertEquals(1, installer.installed.size)
    assertFalse(File(workDir, "pending-install").exists())
    assertEquals(0, quits)
  }

  @Test
  fun install_failure_isFailedAndForgetsTheRecord() = runTest {
    installer.installFailure = IOException("no permission")
    val updater = ready()
    updater.install()
    runCurrent()

    val failed = assertIs<AppUpdateState.Failed>(updater.state.value)
    assertEquals(AppUpdateStep.Install, failed.step)
    assertEquals("no permission", failed.reason)
    assertFalse(File(workDir, "pending-install").exists())
    assertEquals(0, quits)
  }

  @Test
  fun start_afterInstallingThisVersion_reportsItAndClearsTheFiles() = runTest {
    File(workDir, "pending-install").writeText("0.0.2")
    File(workDir, "Ketch.app").mkdirs()
    updater(current = "0.0.2").start(checkAutomatically = false)
    runCurrent()

    val installed = assertIs<UpdateEvent.Installed>(events.single())
    assertEquals("0.0.2", installed.version)
    assertEquals("https://github.com/linroid/Ketch/releases/tag/v0.0.2", installed.notesUrl)
    assertFalse(workDir.exists())
  }

  @Test
  fun start_afterAnInstallThatDidNotTake_reportsTheFailure() = runTest {
    File(workDir, "pending-install").writeText("0.0.2")
    updater(current = "0.0.1").start(checkAutomatically = false)
    runCurrent()

    assertEquals(listOf<UpdateEvent>(UpdateEvent.InstallFailed("0.0.2")), events)
  }

  @Test
  fun start_withoutAnInstall_reportsNothing() = runTest {
    updater().start(checkAutomatically = false)
    runCurrent()

    assertTrue(events.isEmpty())
  }

  private fun TestScope.available(): DesktopUpdater = updater().also {
    it.check()
    runCurrent()
  }

  private fun TestScope.ready(): DesktopUpdater = available().also {
    it.download()
    runCurrent()
    events.clear()
  }

  private fun TestScope.updater(
    current: String = "0.0.1",
    installer: UpdateInstaller? = this@DesktopUpdaterTest.installer,
  ) = DesktopUpdater(
    scope = backgroundScope,
    feed = feed,
    current = ReleaseVersion.parse(current),
    platform = platform,
    installer = installer,
    workDir = workDir,
    download = { asset, file, onProgress ->
      downloads += asset
      if (downloadFails) throw UpdateException("connection reset")
      onProgress(DownloadProgress(asset.size / 2, asset.size))
      file.parentFile.mkdirs()
      file.writeText("installer")
    },
    quit = { quits++ },
    onEvent = { events += it },
    io = StandardTestDispatcher(testScheduler),
  )

  private fun release(version: String) = Release(
    version = ReleaseVersion.parse(version)!!,
    pageUrl = "https://github.com/linroid/Ketch/releases/tag/v$version",
    assets = listOf(
      ReleaseAsset(
        name = "ketch-desktop-$version-macos-arm64.dmg",
        url = "https://example.com/ketch.dmg",
        size = 1000,
        sha256 = "00",
      ),
    ),
  )

  private class FakeFeed(var latest: Release) : ReleaseFeed {
    var failure: Exception? = null
    var calls = 0
      private set

    override suspend fun latest(): Release {
      calls++
      failure?.let { throw it }
      return latest
    }

    override suspend fun release(version: ReleaseVersion): Release = error("Not used")
  }

  private class FakeInstaller : UpdateInstaller {
    override var restarts = true
    var installFailure: Exception? = null
    val prepared = mutableListOf<File>()
    val installed = mutableListOf<Pair<File, Long>>()

    override fun prepare(download: File): File = download.also { prepared += it }

    override fun install(prepared: File, pid: Long) {
      installFailure?.let { throw it }
      installed += prepared to pid
    }
  }
}
