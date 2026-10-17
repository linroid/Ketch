package com.linroid.ketch.torrent.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Why a torrent stopped making progress. Values a newer engine adds decode as [UNKNOWN].
 */
@Serializable(with = TorrentStallReasonSerializer::class)
enum class TorrentStallReason(val wireName: String) {
  /** Not one of the reasons below, or one this client does not know. */
  UNKNOWN("unknown"),

  /**
   * Wanted pieces that no connected peer or running web seed has and that every web seed which
   * stopped could not serve, one still failing once started again aside until a later restart
   * finds it answering: the download waits for a peer that has them, or finishes without them
   * once the files they belong to are skipped.
   */
  MISSING_PIECES("missing-pieces"),

  /** No peer is connected, and web seeds, if any, have stored no piece meanwhile. */
  NO_PEERS("no-peers"),

  /** Peers are connected, but none of them lets us download (they all choke us). */
  CHOKED("choked"),

  /** Peers are slow to deliver what was asked of them. */
  SLOW_PEERS("slow-peers"),
}

/**
 * A download with pieces left that has not gained any for a while, and why, as its session
 * diagnosed it. While it lasts the session also tries to recover: it asks other peers for what
 * slow ones hold, announces to its trackers again, dials peers it gave up on and retries web
 * seeds it dropped.
 *
 * @property reason the most telling reason.
 * @property seconds how long the download has made no progress.
 * @property missingPieces wanted pieces that no connected peer or running web seed has and every
 *   web seed that stopped, or started again and has yet to answer, could not serve, as it may
 *   serve the rest; a web seed still failing once started again counts for nothing until a
 *   later restart finds it answering. With nothing connected and no other web seed stopped,
 *   none: the download waits for peers.
 * @property missingFileIds files those pieces belong to, at most [MAX_MISSING_FILES]: skipping
 *   them lets the download finish.
 * @property peers peers connected now.
 * @property unchokingPeers connected peers that let us download.
 */
@Serializable
data class TorrentStall(
  val reason: TorrentStallReason,
  val seconds: Long,
  val missingPieces: Int = 0,
  val missingFileIds: List<String> = emptyList(),
  val peers: Int = 0,
  val unchokingPeers: Int = 0,
) {
  init {
    require(seconds >= 0 && missingPieces >= 0 && peers >= 0 && unchokingPeers in 0..peers)
    require(missingFileIds.size <= MAX_MISSING_FILES)
  }

  companion object {
    /** The most [missingFileIds] a stall names. */
    const val MAX_MISSING_FILES: Int = 32
  }
}

internal object TorrentStallReasonSerializer : KSerializer<TorrentStallReason> {
  override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
    "com.linroid.ketch.torrent.api.TorrentStallReason", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: TorrentStallReason) {
    encoder.encodeString(value.wireName)
  }

  override fun deserialize(decoder: Decoder): TorrentStallReason {
    val name = decoder.decodeString()
    return TorrentStallReason.entries.firstOrNull { it.wireName == name }
      ?: TorrentStallReason.UNKNOWN
  }
}
