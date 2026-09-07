package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** K=8 routing buckets containing only contacts that answered a correlated query. */
internal class DhtRoutingTable(
  val localId: ByteString,
  private val nowMs: () -> Long = monotonicClock(),
  private val enforceDiversity: Boolean = false,
) {
  private data class Entry(val contact: DhtContact, var seen: Long, var failures: Int = 0)
  private data class Bucket(
    val prefix: ByteString,
    val depth: Int,
    val entries: MutableList<Entry> = mutableListOf(),
    var changed: Long = 0,
  ) {
    fun contains(id: ByteString): Boolean = (0 until depth).all { bit(prefix, it) == bit(id, it) }
  }

  private val mutex = Mutex()
  private val buckets = mutableListOf(Bucket(ByteArray(20).toByteString(), 0))

  init { require(localId.size == 20) }

  /** Returns a questionable incumbent to ping; healthy incumbents are never displaced. */
  suspend fun verified(contact: DhtContact): DhtContact? = mutex.withLock {
    if (contact.id == localId) return@withLock null
    while (true) {
      val bucket = buckets.first { it.contains(contact.id) }
      if (enforceDiversity) {
        val address = requireNotNull(numericAddress(contact.endpoint.host))
        val prefix = address.copyOf(if (address.size == 4) 3 else 8).toByteString()
        val sameNetwork = bucket.entries.count {
          val other = requireNotNull(numericAddress(it.contact.endpoint.host))
          it.contact.id != contact.id && other.size == address.size &&
            other.copyOf(if (other.size == 4) 3 else 8).toByteString() == prefix
        }
        if (sameNetwork >= 2) return@withLock null
      }
      val existing = bucket.entries.firstOrNull { it.contact.id == contact.id }
      if (existing != null) {
        bucket.entries.remove(existing)
        bucket.entries += Entry(contact, nowMs())
        bucket.changed = nowMs()
        return@withLock null
      }
      bucket.entries.removeAll { it.failures >= 2 }
      if (bucket.entries.size < 8) {
        bucket.entries += Entry(contact, nowMs())
        bucket.changed = nowMs()
        return@withLock null
      }
      if (bucket.contains(localId) && bucket.depth < 160) {
        val right = bucket.prefix.toByteArray()
        right[bucket.depth / 8] = (right[bucket.depth / 8].toInt() or
          (128 ushr (bucket.depth % 8))).toByte()
        val children = listOf(Bucket(bucket.prefix, bucket.depth + 1, changed = nowMs()),
          Bucket(right.toByteString(), bucket.depth + 1, changed = nowMs()))
        for (entry in bucket.entries) children.first { it.contains(entry.contact.id) }.entries += entry
        buckets.remove(bucket)
        buckets += children
      } else {
        return@withLock bucket.entries.filter { nowMs() - it.seen >= 900_000 }
          .minByOrNull { it.seen }?.contact
      }
    }
    @Suppress("UNREACHABLE_CODE")
    null
  }

  suspend fun failed(contact: DhtContact) = mutex.withLock {
    buckets.flatMap { it.entries }.firstOrNull { it.contact == contact }?.let { it.failures++ }
  }

  suspend fun seen(contact: DhtContact) = mutex.withLock {
    buckets.first { it.contains(contact.id) }.let { bucket ->
      bucket.entries.firstOrNull { it.contact == contact }?.let {
        it.seen = nowMs()
        bucket.changed = nowMs()
      }
    }
  }

  suspend fun closest(
    target: ByteString,
    count: Int = 8,
    goodOnly: Boolean = false,
  ): List<DhtContact> = mutex.withLock {
    require(target.size == 20 && count in 1..64)
    buckets.flatMap { it.entries }.filter {
      it.failures < 2 && (!goodOnly || nowMs() - it.seen < 900_000)
    }.map { it.contact }
      .sortedBy { dhtDistance(it.id, target) }.take(count)
  }

  suspend fun refreshTargets(): List<ByteString> = mutex.withLock {
    buckets.filter { nowMs() - it.changed >= 900_000 }.map { bucket ->
      val target = torrentRandomBytes(20)
      for (index in 0 until bucket.depth) {
        val mask = 128 ushr (index % 8)
        target[index / 8] = ((target[index / 8].toInt() and mask.inv()) or
          (if (bit(bucket.prefix, index)) mask else 0)).toByte()
      }
      bucket.changed = nowMs()
      target.toByteString()
    }
  }

  suspend fun snapshot(): ByteArray = mutex.withLock {
    Bencode.encode(mapOf("version" to 1L, "id" to localId.toByteArray(), "nodes" to
      buckets.flatMap { it.entries }.filter { it.failures < 2 }.map {
        mapOf("id" to it.contact.id.toByteArray(), "address" to DhtCodec.compactEndpoint(
          it.contact.endpoint))
      }))
  }

  companion object {
    /** Persisted contacts are bootstrap candidates; they must answer again before insertion. */
    fun restore(bytes: ByteArray): Pair<ByteString, List<DhtContact>> {
      val root = Bencode.parse(bytes, 256 * 1024)
      require(root["version"]?.integer == 1L)
      val id = requireNotNull(root["id"]?.bytes).toByteString()
      require(id.size == 20)
      val nodes = requireNotNull(root["nodes"]?.list)
      require(nodes.size <= 1280)
      return id to nodes.map {
        DhtContact(requireNotNull(it["id"]?.bytes).toByteString(),
          DhtCodec.endpoint(requireNotNull(it["address"]?.bytes)))
      }.distinct()
    }

    private fun bit(id: ByteString, index: Int): Boolean =
      id[index / 8].toInt() and (128 ushr (index % 8)) != 0
  }
}
