package com.linroid.ketch.torrent.peer

import com.linroid.ketch.torrent.TorrentClient
import com.linroid.ketch.torrent.TorrentUploadPolicy
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.numericHost

/** BEP 10 IDs belong to the receiving peer. Repeated handshakes update the existing mapping. */
internal class PeerExtensions {
  private val ids = mutableMapOf<String, Int>()
  var metadataSize: Int? = null
    private set

  /** The peer's listen port (BEP 10 `p`); values outside 1..65535 are ignored. */
  var listenPort: Int? = null
    private set

  /** Requests the peer queues (BEP 10 `reqq`); values outside 1..2048 are ignored. */
  var requestQueue: Int? = null
    private set

  /** The peer's client as it names itself (BEP 10 `v`), cleaned up for display. */
  var client: String? = null
    private set

  fun id(name: String): Int = ids[name] ?: 0

  fun receive(bytes: ByteArray, maxMetadataBytes: Int) {
    val root = Bencode.parse(bytes, PeerWire.MAX_FRAME_SIZE, lenient = true)
    require(root.dictionary != null)
    root["m"]?.let { mapping ->
      val entries = requireNotNull(mapping.dictionary)
      require(entries.size <= 64)
      val updated = ids.toMutableMap()
      for ((name, value) in entries) {
        require(name.size <= 64)
        val id = requireNotNull(value.integer)
        require(id in 0..255)
        updated[name.utf8()] = id.toInt()
      }
      require(updated.size <= 64)
      val active = updated.values.filter { it != 0 }
      require(active.distinct().size == active.size) { "Duplicate peer extension IDs" }
      ids.clear()
      ids.putAll(updated)
    }
    root["metadata_size"]?.let { size ->
      val value = requireNotNull(size.integer)
      require(value in 1..maxMetadataBytes.toLong()) { "Peer metadata exceeds limit" }
      require(metadataSize == null || metadataSize == value.toInt()) { "Metadata size changed" }
      metadataSize = value.toInt()
    }
    // Hints only: a peer that sends nonsense here is not worth dropping.
    root["p"]?.integer?.takeIf { it in 1..65_535 }?.let { listenPort = it.toInt() }
    root["reqq"]?.integer?.takeIf { it in 1..MAX_REQUEST_QUEUE }?.let { requestQueue = it.toInt() }
    root["v"]?.bytes?.let(PeerClients::fromVersion)?.let { client = it }
  }

  companion object {
    const val METADATA = 1
    const val PEX = 2
    const val HOLEPUNCH = 3
    private const val MAX_REQUEST_QUEUE = 2048L

    /**
     * Our handshake for a peer of a torrent we hold: [utMetadata] offers the info dictionary of
     * [metadataSize] bytes, [pex] offers peer exchange, [listenPort] is where peers can reach us
     * (0 leaves `p` out) and [requestQueue] how many requests we queue. [holepunch] offers to
     * relay and take part in hole punching (BEP 55). Every handshake names our client (`v`) and,
     * when [remote] is an address, tells the peer where we see it (`yourip`).
     */
    fun handshake(
      utMetadata: Boolean,
      metadataSize: Int?,
      pex: Boolean,
      listenPort: Int,
      requestQueue: Int,
      holepunch: Boolean = false,
      remote: PeerEndpoint? = null,
    ): PeerMessage.Extended {
      require(listenPort in 0..65_535 && requestQueue in 1..MAX_REQUEST_QUEUE)
      val mapping = mutableMapOf<String, Long>()
      if (utMetadata) mapping["ut_metadata"] = METADATA.toLong()
      if (pex) mapping["ut_pex"] = PEX.toLong()
      if (holepunch) mapping[HOLEPUNCH_EXTENSION] = HOLEPUNCH.toLong()
      val values = identify(remote, mapping, requestQueue.toLong())
      if (utMetadata && metadataSize != null) values["metadata_size"] = metadataSize.toLong()
      if (listenPort != 0) values["p"] = listenPort.toLong()
      return PeerMessage.Extended(0, Bencode.encode(values))
    }

    /** A metadata fetcher's handshake: it asks for `ut_metadata` and has nothing to serve. */
    fun handshake(
      metadata: TorrentMetadata? = null,
      pex: Boolean = false,
      remote: PeerEndpoint? = null,
    ): PeerMessage.Extended {
      val mapping = mutableMapOf("ut_metadata" to METADATA.toLong())
      if (pex) mapping["ut_pex"] = PEX.toLong()
      val values = identify(remote, mapping, 16L)
      if (metadata != null) values["metadata_size"] = metadata.infoBytes.size.toLong()
      return PeerMessage.Extended(0, Bencode.encode(values))
    }

    /** What every handshake says: [mapping], [requestQueue], our client and where [remote] is. */
    private fun identify(
      remote: PeerEndpoint?,
      mapping: Map<String, Long>,
      requestQueue: Long,
    ): MutableMap<String, Any> {
      val values = mutableMapOf<String, Any>("m" to mapping, "reqq" to requestQueue,
        "v" to TorrentClient.name)
      remote?.let { yourIp(it.host) }?.let { values["yourip"] = it }
      return values
    }

    /** [host] as BEP 10 `yourip` bytes: 4 for IPv4, an IPv4-mapped IPv6 address included. */
    private fun yourIp(host: String): ByteArray? =
      numericAddress(host)?.let { numericAddress(numericHost(it)) }
  }
}

/**
 * Whether a torrent offers its info dictionary to peers (`ut_metadata`): never for private
 * torrents (BEP 27), and only while [policy] uploads anything, so that switching uploads off
 * keeps everything derived from the content on this device.
 */
internal fun servesMetadata(privateTorrent: Boolean, policy: TorrentUploadPolicy): Boolean =
  !privateTorrent && policy != TorrentUploadPolicy.DISABLED
