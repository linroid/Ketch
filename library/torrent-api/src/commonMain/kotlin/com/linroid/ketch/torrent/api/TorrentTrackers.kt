package com.linroid.ketch.torrent.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How a tracker last answered a torrent's announce; see [TorrentTracker]. Values a newer backend
 * adds decode as [IDLE].
 */
@Serializable(with = TorrentTrackerStateSerializer::class)
enum class TorrentTrackerState(val wireName: String) {
  /** Not asked: the torrent is not running, or its turn has not come. */
  IDLE("idle"),

  /** Being asked now. */
  ANNOUNCING("announcing"),

  /** It answered the last announce. */
  WORKING("working"),

  /** It refused the last announce, or the request failed. */
  FAILED("failed"),

  /** It did not answer the last announce in time. */
  TIMED_OUT("timed-out"),
}

/**
 * One of a torrent's own trackers and how it answers; see [TorrentController.trackers].
 *
 * @property endpoint the tracker's scheme, host and port, such as `udp://tracker.example:1337`,
 *   never its path or query, which may hold a passkey
 * @property tier the index of its tier in the torrent's announce list, 0 for the first: a
 *   torrent asks the trackers of a later tier only when those before it fail
 * @property state how it last answered
 * @property peers how many peers its last answer named, or `null` before one
 * @property seeders the peers it says hold the whole torrent, or `null` when it has not said
 * @property leechers the peers it says are still downloading, or `null` when it has not said
 * @property failures how many announces in a row it failed or left unanswered
 * @property message why it refused the last announce, else the warning its last answer carried,
 *   without URLs or anything that looks like a passkey; `null` when it said nothing
 */
@Serializable
data class TorrentTracker(
  val endpoint: String,
  val tier: Int = 0,
  val state: TorrentTrackerState = TorrentTrackerState.IDLE,
  val peers: Int? = null,
  val seeders: Long? = null,
  val leechers: Long? = null,
  val failures: Int = 0,
  val message: String? = null,
)

internal object TorrentTrackerStateSerializer : KSerializer<TorrentTrackerState> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("TorrentTrackerState", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TorrentTrackerState) {
    encoder.encodeString(value.wireName)
  }

  override fun deserialize(decoder: Decoder): TorrentTrackerState {
    val name = decoder.decodeString()
    return TorrentTrackerState.entries.firstOrNull { it.wireName == name }
      ?: TorrentTrackerState.IDLE
  }
}
