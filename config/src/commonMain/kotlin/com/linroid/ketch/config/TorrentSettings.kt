package com.linroid.ketch.config

import com.linroid.ketch.api.SpeedLimit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What this device shares with the peers of its torrents.
 *
 * Saved as [id]. An id this version does not know, or a value that is not a string at all, loads
 * as [Off] rather than failing the whole config file.
 *
 * @property id value stored in `config.toml`.
 */
@Serializable(with = TorrentUploadModeSerializer::class)
enum class TorrentUploadMode(val id: String) {
  /** Never uploads file data; peers cannot fetch a magnet's metadata from this device either. */
  Off(id = "off"),

  /** Uploads verified pieces while torrents download, and stops when each one finishes. */
  WhileDownloading(id = "while-downloading"),

  /**
   * Keeps uploading finished torrents while Ketch runs, until they are removed or this is
   * changed. A seeding torrent gives its place among the active torrents
   * (`TorrentConfig.maxActiveTorrents`) to one waiting to download, and one that finishes while
   * another waits does not seed.
   */
  Seed(id = "seed"),
}

internal object TorrentUploadModeSerializer : KSerializer<TorrentUploadMode> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.TorrentUploadMode", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TorrentUploadMode) {
    encoder.encodeString(value.id)
  }

  override fun deserialize(decoder: Decoder): TorrentUploadMode {
    // A value of another type (`upload = false`) is as unknown as a misspelled id.
    val id = try { decoder.decodeString() } catch (_: SerializationException) { null }
    return TorrentUploadMode.entries.firstOrNull { it.id == id } ?: TorrentUploadMode.Off
  }
}

/**
 * Reads a speed limit as [SpeedLimit] does (`"unlimited"`, `"1m"`, `"500k"` or bytes), also
 * bytes written as a bare number, but loads a value it cannot read, of any type, as unlimited
 * rather than failing the whole config file.
 */
internal object LenientSpeedLimitSerializer : KSerializer<SpeedLimit> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.LenientSpeedLimit", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: SpeedLimit) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): SpeedLimit {
    val text = try { decoder.decodeString() } catch (_: SerializationException) {
      // Not a string: the bytes form without quotes (`uploadLimit = 1048576`), or nothing usable.
      val bytes = try { decoder.decodeLong() } catch (_: SerializationException) { 0L }
      return if (bytes > 0) SpeedLimit.of(bytes) else SpeedLimit.Unlimited
    }
    return SpeedLimit.parse(text) ?: SpeedLimit.Unlimited
  }
}

/**
 * BitTorrent settings for the local engine.
 *
 * @property trackers extra tracker announce URLs (`http`, `https` or `udp`)
 *   that public torrents announce to alongside their own trackers. Private
 *   torrents and tracker-only discovery ignore them.
 * @property trackerList whether to subscribe to the tracker lists at
 *   [trackerListUrls], downloaded daily, whose trackers are used like
 *   [trackers], after them. On by default, so magnets without trackers still
 *   find peers where DHT cannot bootstrap.
 * @property trackerListUrls `http` or `https` URLs of plain-text tracker
 *   lists, one announce URL per line; [DEFAULT_TRACKER_LISTS] by default.
 * @property trackerListUrl the one list Ketch 0.3.0 subscribed to, read from
 *   its config files: a custom one replaces the default [trackerListUrls]
 *   until the lists are changed ([withTrackerLists]).
 * @property listenPort port `ketch server` accepts BitTorrent peers on, TCP,
 *   and runs DHT on, UDP; `0` lets the system pick a free one at every launch.
 *   The apps and the CLI's other commands always let the system pick, so they
 *   never contend for the server's port.
 * @property upload what this device uploads to peers: nothing by default. Peers it uploads to
 *   see its IP address.
 * @property uploadLimit cap on the upload speed of all torrents together; unlimited by default.
 */
@Serializable
data class TorrentSettings(
  val trackers: List<String> = emptyList(),
  val trackerList: Boolean = true,
  val trackerListUrls: List<String> = DEFAULT_TRACKER_LISTS,
  val trackerListUrl: String? = null,
  val listenPort: Int = 0,
  val upload: TorrentUploadMode = TorrentUploadMode.Off,
  @Serializable(with = LenientSpeedLimitSerializer::class)
  val uploadLimit: SpeedLimit = SpeedLimit.Unlimited,
) {
  init {
    require(listenPort in 0..65535) { "listenPort must be between 0 and 65535" }
  }

  /** The tracker lists configured, whether or not [trackerList] is on, without repeats. */
  val trackerListAddresses: List<String>
    get() {
      val legacy = trackerListUrl?.trim()?.takeIf { it.isNotEmpty() && it != NGOSANG_BEST }
      val urls = if (legacy != null && trackerListUrls == DEFAULT_TRACKER_LISTS) {
        listOf(legacy)
      } else {
        trackerListUrls
      }
      return urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

  /** The tracker lists subscribed to: [trackerListAddresses], none while [trackerList] is off. */
  val subscribedTrackerLists: List<String>
    get() = if (trackerList) trackerListAddresses else emptyList()

  /** These settings with [urls] as the tracker lists, leaving the 0.3.0 [trackerListUrl] behind. */
  fun withTrackerLists(urls: List<String>): TorrentSettings =
    copy(trackerListUrls = urls, trackerListUrl = null)

  companion object {
    private const val NGOSANG_BEST =
      "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"
    private const val XIU2_BEST =
      "https://raw.githubusercontent.com/XIU2/TrackersListCollection/master/best.txt"

    /** ngosang's and XIU2's lists of the best public trackers, both refreshed daily. */
    val DEFAULT_TRACKER_LISTS: List<String> = listOf(NGOSANG_BEST, XIU2_BEST)
  }
}
