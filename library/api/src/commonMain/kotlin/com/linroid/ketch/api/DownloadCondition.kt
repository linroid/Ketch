package com.linroid.ketch.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Something that must hold before a download starts; it starts once all of them do. */
@Serializable
sealed class DownloadCondition {
  /** Whether the condition holds, as it changes. */
  abstract fun isMet(): Flow<Boolean>

  /**
   * A condition backed by [flow], which is not serialized (decoded as always met).
   *
   * ```kotlin
   * val wifiConnected = MutableStateFlow(false)
   * val condition = DownloadCondition.Test(wifiConnected)
   * ```
   */
  @Serializable
  @SerialName("test")
  data class Test(
    @Transient val flow: Flow<Boolean> = flowOf(true),
  ) : DownloadCondition() {
    override fun isMet(): Flow<Boolean> = flow
  }
}
