package com.linroid.ketch.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

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
 * @property successSound whether success feedback, given when a download finishes and is
 *   reported or a Discover search finds downloads, plays a soft chime.
 * @property successVibration whether success feedback vibrates briefly, on phones.
 * @property statusBarIcon whether the Android app's notification of running downloads shows
 *   its icon in the status bar, through a channel Android does not count as silent; off keeps
 *   it among the silent notifications. Anything but a Boolean loads as on.
 * @property liveUpdates whether the Android app asks Android 16 and later to show a running
 *   download as a Live Update, a chip in the status bar. Anything but a Boolean loads as on.
 */
@Serializable
data class NotificationSettings(
  val finished: NotificationMode = NotificationMode.Notify,
  val failed: NotificationMode = NotificationMode.Notify,
  val queueDrained: Boolean = true,
  val deviceOffline: Boolean = true,
  val onlyInBackground: Boolean = true,
  val mutedDevices: List<String> = emptyList(),
  val successSound: Boolean = true,
  val successVibration: Boolean = true,
  @Serializable(with = LenientOnSerializer::class)
  val statusBarIcon: Boolean = true,
  @Serializable(with = LenientOnSerializer::class)
  val liveUpdates: Boolean = true,
)

/**
 * Reads a switch that is on by default, loading `"true"` and `"false"` as text like the
 * Booleans, and any other value as on, rather than failing the whole config file.
 */
internal object LenientOnSerializer : KSerializer<Boolean> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.LenientOn", PrimitiveKind.BOOLEAN)

  override fun serialize(encoder: Encoder, value: Boolean) {
    encoder.encodeBoolean(value)
  }

  override fun deserialize(decoder: Decoder): Boolean = try {
    decoder.decodeBoolean()
  } catch (_: SerializationException) {
    try {
      decoder.decodeString().trim().lowercase() != "false"
    } catch (_: SerializationException) {
      true
    }
  }
}
