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

/**
 * Accent palettes the apps can be themed with, each named after its color.
 *
 * Saved as [id]. The names older versions saved ([previousId]) still load, and an id this version
 * does not know loads as [Indigo] rather than failing the whole config file.
 *
 * @property id value stored in `config.toml`.
 * @property previousId the name older versions saved this palette under, if it had another.
 */
@Serializable(with = AccentColorSerializer::class)
enum class AccentColor(val id: String, internal val previousId: String? = null) {
  Indigo(id = "indigo", previousId = "signal"),
  Purple(id = "purple"),
  Pink(id = "pink"),
  Orange(id = "orange", previousId = "beacon"),
  Green(id = "green", previousId = "fathom"),
  Teal(id = "teal", previousId = "harbor"),
  Blue(id = "blue"),
}

internal object AccentColorSerializer : KSerializer<AccentColor> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.AccentColor", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: AccentColor) {
    encoder.encodeString(value.id)
  }

  override fun deserialize(decoder: Decoder): AccentColor {
    val id = decoder.decodeString()
    return AccentColor.entries.firstOrNull { it.id == id || it.previousId == id }
      ?: AccentColor.Indigo
  }
}

/** Whether the apps use a light or dark palette. */
@Serializable
enum class ThemeMode {
  /** Follow the operating system's light/dark setting. */
  @SerialName("system")
  System,

  @SerialName("light")
  Light,

  @SerialName("dark")
  Dark,
}

/**
 * Look and feel settings.
 *
 * Only the apps read this section; the CLI and server ignore it.
 *
 * @property accent accent palette used by the UI.
 * @property theme light/dark mode used by the UI.
 * @property language BCP 47 tag of the language the UI shows, such as "de" or "zh-Hant"; `null`
 *   follows the system. Android 13 and later and iOS keep the choice in their per-app language
 *   setting instead, so the apps leave this unset there.
 * @property zoom how large the desktop app draws its windows and its tray menu's text, in percent
 *   of their normal size, from [MIN_ZOOM] to [MAX_ZOOM]. A value outside them loads as the
 *   nearest one, and one that is not a whole number as [DEFAULT_ZOOM]. Phones follow the system's
 *   display and text size, and browsers zoom the web app themselves, so the other apps ignore it.
 */
@Serializable
data class AppearanceConfig(
  val accent: AccentColor = AccentColor.Indigo,
  val theme: ThemeMode = ThemeMode.System,
  val language: String? = null,
  @Serializable(with = LenientZoomSerializer::class)
  val zoom: Int = DEFAULT_ZOOM,
) {
  companion object {
    /** [zoom] at the size the UI was designed for. */
    const val DEFAULT_ZOOM: Int = 100

    /** Smallest [zoom]. */
    const val MIN_ZOOM: Int = 50

    /** Largest [zoom]. */
    const val MAX_ZOOM: Int = 300
  }
}

/**
 * Reads `zoom`, loading a value outside its range as the nearest one in it, and one that is not a
 * whole number as [AppearanceConfig.DEFAULT_ZOOM], rather than failing the whole config file.
 */
internal object LenientZoomSerializer : KSerializer<Int> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.LenientZoom", PrimitiveKind.LONG)

  override fun serialize(encoder: Encoder, value: Int) {
    encoder.encodeLong(value.toLong())
  }

  override fun deserialize(decoder: Decoder): Int {
    val percent = try { decoder.decodeLong() } catch (_: SerializationException) {
      try {
        decoder.decodeString().trim().removeSuffix("%").trim().toLongOrNull()
      } catch (_: SerializationException) {
        null
      }
    } ?: return AppearanceConfig.DEFAULT_ZOOM
    val min = AppearanceConfig.MIN_ZOOM.toLong()
    val max = AppearanceConfig.MAX_ZOOM.toLong()
    return percent.coerceIn(min, max).toInt()
  }
}
