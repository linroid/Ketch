package com.linroid.ketch.cli

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.TorrentUploadMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerEnvironmentTest {
  private val fileConfig = KetchConfig(
    name = "From file",
    server = ServerConfig(port = 9000, allowedHosts = listOf("file.example")),
    download = DownloadConfig(defaultDirectory = "/file/downloads"),
    torrent = TorrentSettings(listenPort = 6881, upload = TorrentUploadMode.Seed),
  )

  @Test
  fun apply_noVariables_configUnchanged() {
    assertEquals(fileConfig, applyServerEnvironment(fileConfig, emptyMap()))
    assertEquals(fileConfig, applyServerEnvironment(fileConfig, mapOf("PATH" to "/bin")))
  }

  @Test
  fun apply_everyVariable_overridesTheConfigFile() {
    val applied = applyServerEnvironment(
      fileConfig,
      mapOf(
        ServerEnv.NAME to "NAS",
        ServerEnv.HOST to "127.0.0.1",
        ServerEnv.PORT to "8700",
        ServerEnv.CORS to "*",
        ServerEnv.ALLOWED_HOSTS to " nas.local, 192.168.1.20 ,",
        ServerEnv.ALLOWED_DIRS to "/media,/iso",
        ServerEnv.MDNS to "off",
        ServerEnv.DOWNLOAD_DIR to "/downloads",
        ServerEnv.SPEED_LIMIT to "10m",
        ServerEnv.MAX_CONCURRENT_DOWNLOADS to "0",
        ServerEnv.MAX_CONNECTIONS_PER_DOWNLOAD to "8",
        ServerEnv.MAX_CONNECTIONS_PER_HOST to "2",
        ServerEnv.TORRENT_PORT to "16881",
        ServerEnv.TORRENT_UPLOAD to "off",
      ),
    )

    assertEquals("NAS", applied.name)
    assertEquals(
      ServerConfig(
        host = "127.0.0.1",
        port = 8700,
        corsAllowedHosts = listOf("*"),
        allowedHosts = listOf("nas.local", "192.168.1.20"),
        allowedDirectories = listOf("/media", "/iso"),
        mdnsEnabled = false,
      ),
      applied.server,
    )
    assertEquals(
      DownloadConfig(
        defaultDirectory = "/downloads",
        speedLimit = SpeedLimit.mbps(10),
        maxConcurrentDownloads = 0,
        maxConnectionsPerDownload = 8,
        maxConnectionsPerHost = 2,
      ),
      applied.download,
    )
    assertEquals(16881, applied.torrent.listenPort)
    assertEquals(TorrentUploadMode.Off, applied.torrent.upload)
  }

  @Test
  fun apply_blankValues_countAsUnset() {
    val blank = ServerEnv.names.associateWith { " " }

    assertEquals(fileConfig, applyServerEnvironment(fileConfig, blank))
    assertEquals(emptyList(), serverEnvironmentNames(blank))
  }

  @Test
  fun apply_torrentPortZero_letsTheSystemPick() {
    val applied = applyServerEnvironment(fileConfig, mapOf(ServerEnv.TORRENT_PORT to "0"))

    assertEquals(0, applied.torrent.listenPort)
  }

  @Test
  fun apply_torrentUpload_takesEveryModeInAnyCase() {
    val values = mapOf(
      "off" to TorrentUploadMode.Off,
      "while-downloading" to TorrentUploadMode.WhileDownloading,
      "seed" to TorrentUploadMode.Seed,
      " OFF " to TorrentUploadMode.Off,
      "While-Downloading" to TorrentUploadMode.WhileDownloading,
    )
    for ((value, mode) in values) {
      val applied = applyServerEnvironment(
        KetchConfig(torrent = TorrentSettings(upload = TorrentUploadMode.Seed)),
        mapOf(ServerEnv.TORRENT_UPLOAD to value),
      )
      assertEquals(mode, applied.torrent.upload, value)
    }
  }

  @Test
  fun apply_torrentUploadUnset_keepsTheConfigFileMode() {
    val unset = listOf(emptyMap<String, String>(), mapOf(ServerEnv.TORRENT_UPLOAD to "  "))
    for (environment in unset) {
      val applied = applyServerEnvironment(fileConfig, environment)
      assertEquals(TorrentUploadMode.Seed, applied.torrent.upload)
    }
  }

  @Test
  fun apply_torrentUploadUnknown_failsListingTheModes() {
    for (value in listOf("false", "while_downloading", "upload")) {
      val error = assertFailsWith<IllegalArgumentException> {
        applyServerEnvironment(fileConfig, mapOf(ServerEnv.TORRENT_UPLOAD to value))
      }
      assertEquals(
        "${ServerEnv.TORRENT_UPLOAD}: '$value' is not off, while-downloading or seed",
        error.message,
      )
    }
  }

  @Test
  fun parseTorrentUploadMode_readsTheConfigIdsOnly() {
    for (mode in TorrentUploadMode.entries) {
      assertEquals(mode, parseTorrentUploadMode(mode.id))
      assertEquals(mode, parseTorrentUploadMode(mode.id.uppercase()))
    }
    assertNull(parseTorrentUploadMode(""))
    assertNull(parseTorrentUploadMode("WhileDownloading"))
  }

  @Test
  fun describeTorrentUpload_saysEveryModeInPlainWords() {
    val descriptions = TorrentUploadMode.entries.map(::describeTorrentUpload)

    assertEquals(TorrentUploadMode.entries.size, descriptions.toSet().size)
    assertEquals("while downloading", describeTorrentUpload(TorrentUploadMode.WhileDownloading))
    assertEquals("off", describeTorrentUpload(TorrentUploadMode.Off))
  }

  @Test
  fun apply_unusableValues_failNamingTheVariable() {
    val unusable = listOf(
      ServerEnv.PORT to "0",
      ServerEnv.PORT to "http",
      ServerEnv.TORRENT_PORT to "65536",
      ServerEnv.MDNS to "maybe",
      ServerEnv.SPEED_LIMIT to "fast",
      ServerEnv.MAX_CONNECTIONS_PER_DOWNLOAD to "0",
      ServerEnv.MAX_CONCURRENT_DOWNLOADS to "-1",
      ServerEnv.TORRENT_UPLOAD to "on",
    )
    for ((name, value) in unusable) {
      val error = assertFailsWith<IllegalArgumentException> {
        applyServerEnvironment(fileConfig, mapOf(name to value))
      }
      assertTrue(error.message.orEmpty().startsWith("$name: '$value'"), error.message)
    }
  }

  @Test
  fun names_listsOnlyTheServerVariablesSet() {
    val environment = mapOf(ServerEnv.PORT to "8642", "HOME" to "/root", ServerEnv.NAME to "")

    assertEquals(listOf(ServerEnv.PORT), serverEnvironmentNames(environment))
  }

  @Test
  fun names_includeTheTorrentVariables() {
    val environment = mapOf(ServerEnv.TORRENT_UPLOAD to "off", ServerEnv.TORRENT_PORT to "16881")

    assertTrue(ServerEnv.TORRENT_UPLOAD in ServerEnv.names)
    assertEquals(
      listOf(ServerEnv.TORRENT_PORT, ServerEnv.TORRENT_UPLOAD),
      serverEnvironmentNames(environment),
    )
  }
}
