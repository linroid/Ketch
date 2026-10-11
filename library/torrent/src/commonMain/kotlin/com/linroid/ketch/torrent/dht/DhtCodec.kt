package com.linroid.ketch.torrent.dht

import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.numericHost
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal data class DhtContact(val id: ByteString, val endpoint: PeerEndpoint) {
  init { require(id.size == 20 && endpoint.port != 0) }
}

internal data class DhtMessage(
  val transaction: ByteString,
  val type: String,
  val root: Bencode.Node,
) {
  val body: Bencode.Node? get() = root[if (type == "q") "a" else "r"]
  val query: String? get() = root["q"]?.text()
}

internal object DhtCodec {
  const val MAX_SEND = 1024
  const val MAX_RECEIVE = 4096

  fun parse(bytes: ByteArray): DhtMessage {
    val root = Bencode.parse(bytes, MAX_RECEIVE)
    require(root.dictionary != null)
    val transaction = requireNotNull(root["t"]?.bytes).toByteString()
    require(transaction.size in 1..8)
    val type = root["y"]?.text()
    require(type == "q" || type == "r" || type == "e")
    if (type == "e") {
      val error = requireNotNull(root["e"]?.list)
      require(error.size == 2 && error[0].integer != null && error[1].bytes != null)
    } else {
      val body = requireNotNull(root[if (type == "q") "a" else "r"])
      require(body.dictionary != null && body["id"]?.bytes?.size == 20)
      if (type == "q") require(root["q"]?.bytes?.size in 1..32)
    }
    return DhtMessage(transaction, type, root)
  }

  fun query(transaction: ByteString, name: String, arguments: Map<String, Any>): ByteArray =
    encode(mapOf("t" to transaction.toByteArray(), "y" to "q", "q" to name, "a" to arguments))

  fun response(
    transaction: ByteString,
    values: Map<String, Any>,
    observed: PeerEndpoint,
  ): ByteArray = encode(mapOf("t" to transaction.toByteArray(), "y" to "r", "r" to values,
    "ip" to compactEndpoint(observed)))

  fun error(transaction: ByteString, code: Int): ByteArray = encode(mapOf(
    "t" to transaction.toByteArray(), "y" to "e", "e" to listOf(code.toLong(), "DHT error")
  ))

  fun nodes(bytes: ByteArray, ipv6: Boolean): List<DhtContact> {
    val stride = if (ipv6) 38 else 26
    require(bytes.size % stride == 0 && bytes.size / stride <= 64)
    return bytes.indices.step(stride).map { offset ->
      DhtContact(bytes.copyOfRange(offset, offset + 20).toByteString(),
        endpoint(bytes.copyOfRange(offset + 20, offset + stride)))
    }
  }

  fun compactNodes(nodes: List<DhtContact>): ByteArray {
    require(nodes.size <= 8)
    val output = Buffer()
    for (node in nodes) output.write(node.id).write(compactEndpoint(node.endpoint))
    return output.readByteArray()
  }

  fun compactEndpoint(endpoint: PeerEndpoint): ByteArray =
    Buffer().write(requireNotNull(numericAddress(endpoint.host))).writeShort(endpoint.port)
      .readByteArray()

  fun endpoint(bytes: ByteArray): PeerEndpoint {
    require(bytes.size == 6 || bytes.size == 18)
    val port = ((bytes[bytes.size - 2].toInt() and 255) shl 8) or
      (bytes.last().toInt() and 255)
    require(port != 0)
    return PeerEndpoint(numericHost(bytes.copyOfRange(0, bytes.size - 2)), port)
  }

  private fun encode(value: Map<String, Any>): ByteArray = Bencode.encode(value).also {
    require(it.size <= MAX_SEND) { "DHT response exceeds packet limit" }
  }
}

internal fun dhtDistance(first: ByteString, second: ByteString): ByteString {
  require(first.size == 20 && second.size == 20)
  return ByteArray(20) { (first[it].toInt() xor second[it].toInt()).toByte() }.toByteString()
}
