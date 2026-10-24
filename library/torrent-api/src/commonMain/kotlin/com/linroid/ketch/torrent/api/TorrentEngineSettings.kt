package com.linroid.ketch.torrent.api

import com.linroid.ketch.api.SpeedLimit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What an instance uploads to the peers of its torrents. Values this version does not know, such
 * as a mode a newer version adds, decode as [OFF], so one never reads as uploading.
 */
@Serializable(with = TorrentUploadSettingSerializer::class)
enum class TorrentUploadSetting(val wireName: String) {
  /** Never uploads file data; peers cannot fetch a magnet's metadata from it either. */
  OFF("off"),

  /** Uploads verified pieces while torrents download, and stops when each one finishes. */
  WHILE_DOWNLOADING("while-downloading"),

  /** Keeps uploading finished torrents while the instance runs. */
  SEED("seed"),
}

/**
 * Whether peer connections use protocol encryption (MSE/PE). Values this version does not know
 * decode as [PREFERRED].
 */
@Serializable(with = TorrentEncryptionSettingSerializer::class)
enum class TorrentEncryptionSetting(val wireName: String) {
  /** Dials encrypted first and in plaintext when a peer refuses; accepts both. */
  PREFERRED("preferred"),

  /** Encrypted connections only, both ways. */
  REQUIRED("required"),

  /** Plaintext only: encrypted incoming connections are refused. */
  OFF("off"),
}

/**
 * The settings every torrent of an instance runs with, which [TorrentController.settings] reads
 * and [TorrentController.updateSettings] changes. They leave out what belongs to the host rather
 * than the engine, such as the listen port or whether uploads wait for Wi-Fi.
 *
 * @property upload what the instance uploads to peers
 * @property uploadLimit the upload cap all torrents share
 * @property encryption protocol encryption for peer connections
 * @property utp whether peers are also reached over uTP
 * @property portMapping whether the router is asked to forward the listen port
 * @property localDiscovery whether public torrents are announced on the local network and its
 *   peers used; only engines advertising [TorrentCapability.LOCAL_DISCOVERY] run it
 * @property trackers extra announce URLs public torrents announce to, besides their own
 * @property trackerList whether the lists at [trackerListUrls] are subscribed to
 * @property trackerListUrls addresses of tracker lists, kept while [trackerList] is off
 */
@Serializable
data class TorrentEngineSettings(
  val upload: TorrentUploadSetting = TorrentUploadSetting.WHILE_DOWNLOADING,
  val uploadLimit: SpeedLimit = SpeedLimit.Unlimited,
  val encryption: TorrentEncryptionSetting = TorrentEncryptionSetting.PREFERRED,
  val utp: Boolean = true,
  val portMapping: Boolean = true,
  val localDiscovery: Boolean = true,
  val trackers: List<String> = emptyList(),
  val trackerList: Boolean = true,
  val trackerListUrls: List<String> = emptyList(),
)

/**
 * A change to [TorrentEngineSettings]: the fields it sets replace the instance's, and `null`
 * ones keep theirs, so clients changing different settings at once never undo each other.
 */
@Serializable
data class TorrentSettingsChange(
  val upload: TorrentUploadSetting? = null,
  val uploadLimit: SpeedLimit? = null,
  val encryption: TorrentEncryptionSetting? = null,
  val utp: Boolean? = null,
  val portMapping: Boolean? = null,
  val localDiscovery: Boolean? = null,
  val trackers: List<String>? = null,
  val trackerList: Boolean? = null,
  val trackerListUrls: List<String>? = null,
) {
  /** Whether it changes nothing. */
  val isEmpty: Boolean get() = this == TorrentSettingsChange()

  /** [settings] with the fields this change sets replaced. */
  fun applyTo(settings: TorrentEngineSettings): TorrentEngineSettings = TorrentEngineSettings(
    upload = upload ?: settings.upload,
    uploadLimit = uploadLimit ?: settings.uploadLimit,
    encryption = encryption ?: settings.encryption,
    utp = utp ?: settings.utp,
    portMapping = portMapping ?: settings.portMapping,
    localDiscovery = localDiscovery ?: settings.localDiscovery,
    trackers = trackers ?: settings.trackers,
    trackerList = trackerList ?: settings.trackerList,
    trackerListUrls = trackerListUrls ?: settings.trackerListUrls,
  )

  companion object {
    /** The change that turns [from] into [to], setting only the fields that differ. */
    fun between(from: TorrentEngineSettings, to: TorrentEngineSettings) = TorrentSettingsChange(
      upload = to.upload.takeIf { it != from.upload },
      uploadLimit = to.uploadLimit.takeIf { it != from.uploadLimit },
      encryption = to.encryption.takeIf { it != from.encryption },
      utp = to.utp.takeIf { it != from.utp },
      portMapping = to.portMapping.takeIf { it != from.portMapping },
      localDiscovery = to.localDiscovery.takeIf { it != from.localDiscovery },
      trackers = to.trackers.takeIf { it != from.trackers },
      trackerList = to.trackerList.takeIf { it != from.trackerList },
      trackerListUrls = to.trackerListUrls.takeIf { it != from.trackerListUrls },
    )
  }
}

internal object TorrentUploadSettingSerializer : KSerializer<TorrentUploadSetting> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
    "com.linroid.ketch.torrent.api.TorrentUploadSetting", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TorrentUploadSetting) {
    encoder.encodeString(value.wireName)
  }

  override fun deserialize(decoder: Decoder): TorrentUploadSetting {
    val name = decoder.decodeString()
    return TorrentUploadSetting.entries.firstOrNull { it.wireName == name }
      ?: TorrentUploadSetting.OFF
  }
}

internal object TorrentEncryptionSettingSerializer : KSerializer<TorrentEncryptionSetting> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
    "com.linroid.ketch.torrent.api.TorrentEncryptionSetting", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TorrentEncryptionSetting) {
    encoder.encodeString(value.wireName)
  }

  override fun deserialize(decoder: Decoder): TorrentEncryptionSetting {
    val name = decoder.decodeString()
    return TorrentEncryptionSetting.entries.firstOrNull { it.wireName == name }
      ?: TorrentEncryptionSetting.PREFERRED
  }
}
