package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.core.speed.ObservedPeak
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerSpeedModesTest {
  private val dir: File = Files.createTempDirectory("ketch-speed-modes").toFile()
  private val path = File(dir, "config.toml").path
  private val store = FileConfigStore(path)

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun saveSettings_afterADownloadSettingSaved_keepsBoth() = runBlocking {
    val file = file()
    val options = serverSpeedModes(file, KetchConfig(), startCap = SpeedLimit.Unlimited)

    file.save(DownloadConfig(maxConcurrentDownloads = 2))
    options.saveSettings(SpeedSettings(mode = SpeedLimitMode.Auto))

    val saved = store.load()
    assertEquals(2, saved.download.maxConcurrentDownloads)
    assertEquals(SpeedLimitMode.Auto, saved.speed.mode)
  }

  // The file the server saves to, started with the cap of startCap.
  private fun file(startCap: SpeedLimit = SpeedLimit.Unlimited) =
    ServerConfigFile(path, started = DownloadConfig(speedLimit = startCap))

  @Test
  fun saveSettings_capFromAFlag_keepsTheFilesSpeedLimit() = runBlocking {
    store.save(KetchConfig(download = DownloadConfig(speedLimit = SpeedLimit.mbps(5))))
    val options = serverSpeedModes(file(SpeedLimit.mbps(10)), store.load(), SpeedLimit.mbps(10))

    options.saveSettings(
      SpeedSettings(mode = SpeedLimitMode.SlowLane, standard = SpeedLimit.mbps(10)),
    )

    val saved = store.load()
    assertEquals(SpeedLimitMode.SlowLane, saved.speed.mode)
    assertEquals(SpeedLimit.mbps(5), saved.download.speedLimit)
  }

  @Test
  fun saveSettings_newCap_savedAsTheDownloadLimit() = runBlocking {
    val options = serverSpeedModes(file(), KetchConfig(), startCap = SpeedLimit.Unlimited)

    options.saveSettings(SpeedSettings(standard = SpeedLimit.mbps(20)))

    assertEquals(SpeedLimit.mbps(20), store.load().download.speedLimit)
  }

  @Test
  fun savePeak_peak_savedUnderUi() = runBlocking {
    val options = serverSpeedModes(file(), KetchConfig(), startCap = SpeedLimit.Unlimited)

    options.savePeak(ObservedPeak(bytesPerSecond = 8_000_000, atEpochMillis = 1_000))

    val ui = store.load().ui
    assertEquals(8_000_000, ui.observedPeak)
    assertEquals(1_000, ui.observedPeakAt)
  }
}
