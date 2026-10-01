package com.linroid.ketch.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the apps report an event. */
@Serializable
enum class NotificationMode {
  /** A system notification, or an in-app toast while Ketch is in front. */
  @SerialName("notify")
  Notify,

  /** Only an in-app toast. */
  @SerialName("in-app")
  InApp,

  /** Not reported. */
  @SerialName("off")
  Off,
}

/**
 * Which activity the apps report, persisted under `[notifications]`.
 *
 * Only the apps read this section.
 *
 * @property finished how a finished download is reported.
 * @property failed how a failed download is reported.
 * @property queueDrained whether "All downloads finished" is reported.
 * @property deviceOffline whether a device going offline shows an in-app message.
 * @property onlyInBackground whether system notifications are posted only while
 *   Ketch is in the background; in front, events show as toasts.
 * @property mutedDevices ids of the devices whose events are not reported.
 */
@Serializable
data class NotificationSettings(
  val finished: NotificationMode = NotificationMode.Notify,
  val failed: NotificationMode = NotificationMode.Notify,
  val queueDrained: Boolean = true,
  val deviceOffline: Boolean = true,
  val onlyInBackground: Boolean = true,
  val mutedDevices: List<String> = emptyList(),
)
