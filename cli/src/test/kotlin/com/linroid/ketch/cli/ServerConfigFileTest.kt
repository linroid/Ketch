package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.config.FileConfigStore
import com.linroid.ketch.config.HookConfig
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.TorrentUploadMode
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Saves write real files on Dispatchers.IO, so the tests block rather than run on virtual time,
// which would also report the failures servers of other tests in this JVM leave behind.
class ServerConfigFileTest {
  private val dir = createTempDirectory("ketch-server-config").toFile()
  private val file = File(dir, "config.toml")

  @AfterTest
  fun deleteDir() {
    dir.deleteRecursively()
  }

  @Test
  fun save_changedField_writesItOverTheFileKeepingEverythingElse() = runBlocking {
    val written = KetchConfig(
      name = "NAS",
      server = ServerConfig(port = 9000, allowedDirectories = listOf("D:\\Media\\iso")),
      download = DownloadConfig(defaultDirectory = "F:\\x\\backup", maxConcurrentDownloads = 2),
      torrent = TorrentSettings(listenPort = 16881, upload = TorrentUploadMode.Seed),
      hooks = listOf(HookConfig(events = listOf("completed"), command = listOf("notify", "done"))),
    )
    FileConfigStore(file.path).save(written)
    // As KETCH_SPEED_LIMIT=10m starts the server: in effect, but not in the file.
    val started = written.download.copy(speedLimit = SpeedLimit.parse("10m")!!)
    val settings = ServerConfigFile(file.path, started)

    settings.save(started.copy(maxConnectionsPerHost = 3))

    val saved = FileConfigStore(file.path).load()
    assertEquals(written.copy(download = written.download.copy(maxConnectionsPerHost = 3)), saved)
    assertEquals("F:\\x\\backup", saved.download.defaultDirectory)
  }

  @Test
  fun save_noFile_createsOneWithOnlyTheChange() = runBlocking {
    // A Docker volume holds no config.toml; the image sets the folder with KETCH_DOWNLOAD_DIR.
    val path = File(dir, "config/config.toml")
    val started = DownloadConfig(defaultDirectory = "/downloads")
    val settings = ServerConfigFile(path.path, started)

    settings.save(started.copy(speedLimit = SpeedLimit.parse("2m")!!))

    val saved = FileConfigStore(path.path).load()
    assertEquals(SpeedLimit.parse("2m"), saved.download.speedLimit)
    assertNull(saved.download.defaultDirectory)
  }

  @Test
  fun save_fileEditedMeanwhile_keepsTheEdit() = runBlocking {
    FileConfigStore(file.path).save(KetchConfig())
    val settings = ServerConfigFile(file.path, DownloadConfig())
    FileConfigStore(file.path).save(KetchConfig(download = DownloadConfig(retryCount = 7)))

    settings.save(DownloadConfig(maxConcurrentDownloads = 1))

    val download = FileConfigStore(file.path).load().download
    assertEquals(7, download.retryCount)
    assertEquals(1, download.maxConcurrentDownloads)
  }

  @Test
  fun save_unchangedSettings_leavesTheFileAlone() = runBlocking {
    val settings = ServerConfigFile(file.path, DownloadConfig(maxConcurrentDownloads = 2))

    settings.save(DownloadConfig(maxConcurrentDownloads = 2))

    assertFalse(file.exists())
  }

  @Test
  fun save_unreadableFile_failsLeavingItAndSavesTheChangeLater() = runBlocking {
    val broken = "[download\nmaxConcurrentDownloads = 2\n"
    file.writeText(broken)
    val settings = ServerConfigFile(file.path, DownloadConfig())

    assertFails { settings.save(DownloadConfig(maxConcurrentDownloads = 1)) }
    assertEquals(broken, file.readText())

    // Once the file reads again, the next change saves the one that failed too.
    file.writeText("")
    settings.save(DownloadConfig(maxConcurrentDownloads = 1, retryCount = 9))
    val download = FileConfigStore(file.path).load().download
    assertEquals(1, download.maxConcurrentDownloads)
    assertEquals(9, download.retryCount)
  }

  @Test
  fun pinnedDownloadFields_namesFieldsOfDownloadConfig() {
    val names = (0..<DownloadConfig.serializer().descriptor.elementsCount)
      .map { DownloadConfig.serializer().descriptor.getElementName(it) }
    val every = ServerEnv.names.associateWith { "set" }

    val pinned = pinnedDownloadFields(every)

    assertTrue(pinned.isNotEmpty())
    assertTrue(names.containsAll(pinned.keys), "$pinned")
  }

  @Test
  fun pinnedDownloadFields_flagsWinAndBlankVariablesDoNotCount() {
    val pinned = pinnedDownloadFields(
      mapOf(ServerEnv.SPEED_LIMIT to "10m", ServerEnv.DOWNLOAD_DIR to " "),
      flags = mapOf("speedLimit" to "--speed-limit"),
    )

    assertEquals(mapOf("speedLimit" to "--speed-limit"), pinned)
  }
}
