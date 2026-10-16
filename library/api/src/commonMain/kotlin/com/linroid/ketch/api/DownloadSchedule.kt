package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/** When a download starts. */
@Serializable
sealed class DownloadSchedule {
  /** At once. */
  @Serializable
  @SerialName("immediate")
  data object Immediate : DownloadSchedule()

  /** At [startAt]. */
  @Serializable
  @SerialName("at_time")
  data class AtTime(val startAt: Instant) : DownloadSchedule()

  /** [delay] after it is added. */
  @Serializable
  @SerialName("after_delay")
  data class AfterDelay(val delay: Duration) : DownloadSchedule()
}
