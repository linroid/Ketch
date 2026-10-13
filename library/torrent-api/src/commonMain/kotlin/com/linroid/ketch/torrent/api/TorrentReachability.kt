package com.linroid.ketch.torrent.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Whether peers can connect to the instance's torrent engine; see
 * [TorrentController.reachability]. Values a newer backend adds decode as [UNKNOWN].
 */
@Serializable(with = TorrentReachabilityStatusSerializer::class)
enum class TorrentReachabilityStatus(val wireName: String) {
  /** The engine is not running, or has not found out yet. */
  UNKNOWN("unknown"),

  /** Peers have connected to it: incoming connections work. */
  REACHABLE("reachable"),

  /** Every NAT layer up to a public address forwards its port, so peers can connect. */
  FORWARDED("forwarded"),

  /**
   * The routers it could ask forward its port, but a NAT outside them, often the provider's,
   * does not, so peers cannot connect; it still reaches peers itself, and by hole punching.
   */
  BLOCKED("blocked"),

  /**
   * No router forwards its port: port mapping is off or no router answered. Peers can connect
   * only if the port is forwarded by hand, or nothing stands in the way.
   */
  NOT_FORWARDED("not-forwarded"),
}

/**
 * How peers reach the instance's torrent engine.
 *
 * @property status what it found.
 * @property port the port peers are told to connect to; `null` while the engine is not running.
 * @property forwardedLayers NAT layers whose routers forward the port, the home router first.
 * @property incomingPeers peers that connected to it over TCP since the engine started.
 * @property holePunching whether it punches through NATs (BEP 55), which reaches peers behind
 *   them without a forwarded port.
 */
@Serializable
data class TorrentReachability(
  val status: TorrentReachabilityStatus = TorrentReachabilityStatus.UNKNOWN,
  val port: Int? = null,
  val forwardedLayers: Int = 0,
  val incomingPeers: Long = 0,
  val holePunching: Boolean = false,
)

internal object TorrentReachabilityStatusSerializer : KSerializer<TorrentReachabilityStatus> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
    "com.linroid.ketch.torrent.api.TorrentReachabilityStatus", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TorrentReachabilityStatus) {
    encoder.encodeString(value.wireName)
  }

  override fun deserialize(decoder: Decoder): TorrentReachabilityStatus {
    val name = decoder.decodeString()
    return TorrentReachabilityStatus.entries.firstOrNull { it.wireName == name }
      ?: TorrentReachabilityStatus.UNKNOWN
  }
}
