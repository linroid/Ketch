package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
