package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Which side opened a connection. Serialized as `"outgoing"` or `"incoming"`; a value this
 * version does not know decodes as [OUTGOING].
 */
@Serializable(with = ConnectionDirectionSerializer::class)
enum class ConnectionDirection {
  /** This instance connected to the remote side. */
  OUTGOING,

  /** The remote side connected to this instance, such as a torrent peer. */
  INCOMING,
}

/**
 * How a connection reaches its endpoint. Serialized as `"direct"`, `"http_proxy"`,
 * `"socks5_proxy"` or `"unknown"`; a value this version does not know decodes as [UNKNOWN].
 */
@Serializable(with = ConnectionRouteSerializer::class)
enum class ConnectionRoute {
  /** Straight to the endpoint. */
  DIRECT,

  /** Through an HTTP proxy. */
  HTTP_PROXY,

  /** Through a SOCKS5 proxy. */
  SOCKS5_PROXY,

  /** Not known, such as when the platform chooses a system proxy by itself. */
  UNKNOWN,
}

/**
 * Details of a connection to a torrent peer.
 *
 * @property wire the peer wire protocol: `"v1"` or `"v2"`. On a hybrid torrent, `"v1"` is a peer
 *   of the v1 swarm
 * @property peerChoking whether the peer chokes this instance
 * @property uploadSlot whether this instance unchoked the peer, so it may upload to it
 * @property peerInterested whether the peer wants data this instance has
 */
@Serializable
data class PeerDetails(
  val wire: String,
  val peerChoking: Boolean,
  val uploadSlot: Boolean,
  val peerInterested: Boolean,
)

/**
 * One live network connection of a task, sampled once a second.
 *
 * @property id unique among this instance's connections until it restarts; never reused
 * @property taskId the task the connection transfers for
 * @property source the download source type, such as `"http"`, `"ftp"`, `"hls"`, `"dash"` or
 *   `"torrent"`
 * @property protocol a display label such as `"HTTP/1.1"`, `"HTTP/2"`, `"FTP"`, `"FTPS"` or
 *   `"BitTorrent"`; `null` when the engine did not say
 * @property secure whether the transport is TLS (https, ftps); `null` when unknown
 * @property direction which side opened the connection
 * @property host the remote host name or IP literal (IPv6 without brackets), never with user
 *   info or a path
 * @property port the remote port; `null` when unknown
 * @property route how the connection reaches [host]
 * @property downloadBps bytes per second received over the last two samples
 * @property uploadBps bytes per second sent over the last two samples
 * @property downloadedBytes payload bytes received since the connection opened
 * @property uploadedBytes payload bytes sent since the connection opened
 * @property openedAt when the connection opened
 * @property peer torrent peer details; `null` for other sources
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
)

/**
 * The live connections of an instance at [sampledAt]: at most the requested limit of them, the
 * fastest by combined rate, ordered by [ActiveConnection.openedAt] so their order stays stable.
 * [total], [downloadBps] and [uploadBps] cover every connection, including those past the
 * limit.
 *
 * @property sampledAt when the sample was taken
 * @property connections the connections, at most the requested limit
 * @property total how many connections are open, including those past the limit
 * @property downloadBps bytes per second received over every connection
 * @property uploadBps bytes per second sent over every connection
 * @property dropped how many connections were not tracked since the instance started because
 *   too many were open at once
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
    /** The number of connections [KetchApi.activeConnections] returns unless asked otherwise. */
    const val DEFAULT_LIMIT: Int = 256

    /** The most connections one snapshot can hold. */
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
