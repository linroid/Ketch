package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HomeDirectoryTest {

  @Test
  fun `leading tilde expands to the home directory`() {
    assertEquals("/home/me", expandHome("~", "/home/me"))
    assertEquals("/home/me/Downloads", expandHome("~/Downloads", "/home/me"))
    assertEquals("/home/me/Downloads", expandHome("~/Downloads", "/home/me/"))
    assertEquals("C:\\Users\\me\\Downloads", expandHome("~\\Downloads", "C:\\Users\\me"))
  }

  @Test
  fun `other paths are unchanged`() {
    assertEquals("/data/Downloads", expandHome("/data/Downloads", "/home/me"))
    assertEquals("~other/Downloads", expandHome("~other/Downloads", "/home/me"))
    assertEquals("Downloads/~", expandHome("Downloads/~", "/home/me"))
  }

  @Test
  fun `nothing expands without a home directory`() {
    assertEquals("~/Downloads", expandHome("~/Downloads", null))
    assertEquals("~/Downloads", expandHome("~/Downloads", ""))
  }

  @Test
  fun `config expands its download directory`() {
    val config = KetchConfig(download = DownloadConfig(defaultDirectory = "~/Downloads"))
    val expanded = config.expandHome("/home/me")
    assertEquals("/home/me/Downloads", expanded.download.defaultDirectory)
    assertEquals(config.copy(download = expanded.download), expanded)
  }

  @Test
  fun `config expands the server's allowed directories`() {
    val config = KetchConfig(
      server = ServerConfig(allowedDirectories = listOf("~/Media", "/srv/media")),
    )
    val expanded = config.expandHome("/home/me")
    assertEquals(listOf("/home/me/Media", "/srv/media"), expanded.server.allowedDirectories)
    assertEquals(config.copy(server = expanded.server), expanded)
  }

  @Test
  fun `config without a tilde path is returned as is`() {
    val config = KetchConfig()
    assertSame(config, config.expandHome("/home/me"))
  }
}
