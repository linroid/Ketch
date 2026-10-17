package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/** Which side opened a connection; unknown values decode as [OUTGOING]. */
@Serializable(with = ConnectionDirectionSerializer::class)
enum class ConnectionDirection {
  /** This instance connected out. */
  OUTGOING,

  /** The remote side connected in, such as a torrent peer. */
  INCOMING,
}

/** How a connection reaches its endpoint; unknown values decode as [UNKNOWN]. */
@Serializable(with = ConnectionRouteSerializer::class)
enum class ConnectionRoute {
  DIRECT,
  HTTP_PROXY,
  SOCKS5_PROXY,

  /** Not known, such as when the platform picks a system proxy itself. */
  UNKNOWN,
}

/**
 * A torrent peer connection's details.
 *
 * @property wire the peer protocol, `v1` or `v2` (a hybrid torrent's v1 swarm is `v1`)
 * @property peerChoking whether the peer chokes this instance
 * @property uploadSlot whether this instance unchoked the peer
 * @property peerInterested whether the peer wants data this instance has
 * @property client the peer's client, such as `qBittorrent 4.6.3`, as the peer names it (its
 *   BEP 10 `v`, else its peer ID); self-reported, so only fit for display. `null` when unknown
 */
@Serializable
data class PeerDetails(
  val wire: String,
  val peerChoking: Boolean,
  val uploadSlot: Boolean,
  val peerInterested: Boolean,
  val client: String? = null,
)

/**
 * One live connection of a task, sampled each second. `null` fields are unknown.
 *
 * @property id unique until the instance restarts
 * @property source the source type, such as `http` or `torrent`
 * @property protocol a display label, such as `HTTP/2` or `BitTorrent`
 * @property secure whether it uses TLS
 * @property host the remote host name or IP (IPv6 without brackets)
 * @property downloadBps bytes per second received, over the last two samples
 * @property uploadBps bytes per second sent, over the last two samples
 * @property downloadedBytes payload bytes received since it opened
 * @property uploadedBytes payload bytes sent since it opened
 * @property peer torrent peer details
 * @property country the ISO 3166 code of [host]'s country, such as `DE`, from the instance's
 *   [CountryDatabase]; `null` without one, or for reserved and unknown addresses
 */
@Serializable
data class ActiveConnection(
  val id: Long,
  val taskId: String,
  val source: String,
  val protocol: String? = null,
  val secure: Boolean? = null,
  val direction: ConnectionDirection = ConnectionDirection.OUTGOING,
  val host: String,
  val port: Int? = null,
  val route: ConnectionRoute = ConnectionRoute.UNKNOWN,
  val downloadBps: Long = 0,
  val uploadBps: Long = 0,
  val downloadedBytes: Long = 0,
  val uploadedBytes: Long = 0,
  val openedAt: Instant,
  val peer: PeerDetails? = null,
  val country: String? = null,
)

/**
 * An instance's live connections at [sampledAt]: the fastest, up to the requested limit, in the
 * order they opened. [total], [downloadBps] and [uploadBps] cover them all.
 *
 * @property dropped connections never tracked since the instance started, as too many were open
 */
@Serializable
data class ActiveConnections(
  val sampledAt: Instant,
  val connections: List<ActiveConnection> = emptyList(),
  val total: Int = 0,
  val downloadBps: Long = 0,
  val uploadBps: Long = 0,
  val dropped: Long = 0,
) {
  companion object {
    /** [Interest.Connections]'s default limit. */
    const val DEFAULT_LIMIT: Int = 256

    /** The most connections a snapshot can hold. */
    const val MAX_LIMIT: Int = 1024
  }
}

internal object ConnectionDirectionSerializer : KSerializer<ConnectionDirection> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.api.ConnectionDirection", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ConnectionDirection) {
    encoder.encodeString(value.name.lowercase())
  }

  override fun deserialize(decoder: Decoder): ConnectionDirection =
    when (decoder.decodeString()) {
      "incoming" -> ConnectionDirection.INCOMING
      else -> ConnectionDirection.OUTGOING
    }
}

internal object ConnectionRouteSerializer : KSerializer<ConnectionRoute> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.api.ConnectionRoute", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: ConnectionRoute) {
    encoder.encodeString(value.name.lowercase())
  }

  override fun deserialize(decoder: Decoder): ConnectionRoute {
    val name = decoder.decodeString()
    return ConnectionRoute.entries.firstOrNull { it.name.lowercase() == name }
      ?: ConnectionRoute.UNKNOWN
  }
}
