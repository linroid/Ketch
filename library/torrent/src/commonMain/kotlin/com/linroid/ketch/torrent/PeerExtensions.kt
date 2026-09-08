package com.linroid.ketch.torrent

/** BEP 10 IDs belong to the receiving peer. Repeated handshakes update the existing mapping. */
internal class PeerExtensions {
  private val ids = mutableMapOf<String, Int>()
  var metadataSize: Int? = null
    private set

  fun id(name: String): Int = ids[name] ?: 0

  fun receive(bytes: ByteArray, maxMetadataBytes: Int) {
    val root = Bencode.parse(bytes, PeerWire.MAX_FRAME_SIZE)
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
  }

  companion object {
    const val METADATA = 1
    const val PEX = 2

    fun handshake(metadata: TorrentMetadata? = null, pex: Boolean = false): PeerMessage.Extended {
      val mapping = mutableMapOf("ut_metadata" to METADATA.toLong())
      if (pex) mapping["ut_pex"] = PEX.toLong()
      val values = mutableMapOf<String, Any>("m" to mapping, "reqq" to 16L)
      if (metadata != null) values["metadata_size"] = metadata.infoBytes.size.toLong()
      return PeerMessage.Extended(0, Bencode.encode(values))
    }
  }
}
