package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.FileConflict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadRetryConfigTest {

  @Test
  fun `a download table from before idle timeouts loads with their defaults`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[download]
      |retryCount = 5
      |retryDelayMs = 2000
      """.trimMargin(),
    )
    assertEquals(DownloadConfig(retryCount = 5, retryDelayMs = 2_000), decoded.download)
  }

  @Test
  fun `hand-written timeout and retry keys are read`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[download]
      |unlimitedRetries = true
      |maxRetryDelayMs = 30000
      |idleTimeoutMs = 0
      |minSpeed = 10240
      """.trimMargin(),
    )
    assertEquals(
      DownloadConfig(
        unlimitedRetries = true,
        maxRetryDelayMs = 30_000,
        idleTimeoutMs = 0,
        minSpeed = 10_240,
      ),
      decoded.download,
    )
  }

  @Test
  fun `fileConflict is written as text and read back`() {
    val config = KetchConfig(download = DownloadConfig(fileConflict = FileConflict.OVERWRITE))

    val text = ConfigStore.encode(config)

    assertTrue("fileConflict = \"overwrite\"" in text, text)
    assertEquals(config.download, ConfigStore.decode(text).download)
  }

  @Test
  fun `a fileConflict this version does not know loads as rename`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[download]
      |fileConflict = "ask"
      """.trimMargin(),
    )
    assertEquals(FileConflict.RENAME, decoded.download.fileConflict)
  }
}
