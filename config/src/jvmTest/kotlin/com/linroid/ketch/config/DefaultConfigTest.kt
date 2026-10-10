package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultConfigTest {

  @Test
  fun `default template decodes with every commented option at its default`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      DEFAULT_CONFIG_CONTENT,
    )
    assertEquals(DownloadConfig.Default, decoded.download)
    assertEquals(TorrentSettings(), decoded.torrent)
    assertEquals(8642, decoded.server.port)
  }

  @Test
  fun `commented examples are usable once uncommented`() {
    // Uncomment `# key = value` and `# [section]` lines, leaving the prose comments
    val example = Regex("^# (?=\\[|\\w+ = )", RegexOption.MULTILINE)
    val content = DEFAULT_CONFIG_CONTENT.replace(example, "")
    val dir = createTempDirectory("ketch-config").toFile()
    try {
      val file = File(dir, "config.toml").apply { writeText(content) }
      val config = FileConfigStore(file.path).load()

      val home = System.getProperty("user.home").trimEnd('/', '\\')
      assertEquals("$home/Downloads", config.download.defaultDirectory)
      assertEquals(
        DownloadConfig.Default.copy(defaultDirectory = "$home/Downloads"),
        config.download,
      )
      // Ktor's CORS allowHost rejects a scheme in the host, failing server startup
      assertEquals(listOf("localhost:3000"), config.server.corsAllowedHosts)
      assertEquals(listOf("$home/Media"), config.server.allowedDirectories)
      assertEquals("My Ketch", config.name)
      val remote = RemoteConfig("192.168.1.100", apiToken = "token", name = "NAS")
      assertEquals(listOf(remote), config.remotes)
      assertEquals(1, config.torrent.trackers.size)
      assertEquals(listOf("https://lists.example.org/trackers.txt"), config.torrent.trackerListUrls)
      assertEquals(6881, config.torrent.listenPort)
    } finally {
      dir.deleteRecursively()
    }
  }
}
