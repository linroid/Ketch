package com.linroid.kdown.api

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Defines when a download should start.
 */
@Serializable
sealed class DownloadSchedule {
  /** Download starts immediately (default behavior). */
  data object Immediate : DownloadSchedule()

  /**
   * Download starts at a specific time.
   *
   * @property startAt the [Instant] at which the download should begin
   */
  data class AtTime(val startAt: Instant) : DownloadSchedule()

  /**
   * Download starts after a delay from the time it is submitted.
   *
   * @property delay the [Duration] to wait before starting
   */
  data class AfterDelay(val delay: Duration) : DownloadSchedule()
}
