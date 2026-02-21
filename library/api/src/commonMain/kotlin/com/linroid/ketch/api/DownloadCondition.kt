package com.linroid.ketch.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A condition that must be met before a scheduled download can start.
 *
 * Conditions are evaluated continuously. When all conditions for a task
 * are met simultaneously, the download proceeds.
 */
@Serializable
sealed class DownloadCondition {
  /** Emits `true` when the condition is satisfied, `false` when not. */
  abstract fun isMet(): Flow<Boolean>

  /**
   * A condition backed by a runtime [Flow]. Useful for testing and
   * for consumers who need a simple, ad-hoc condition.
   *
   * The [flow] is `@Transient` and defaults to `flowOf(true)` after
   * deserialization.
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
