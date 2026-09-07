package com.linroid.ketch.torrent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okio.ByteString
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** One address-family DHT. Only correlated, validated respondents enter the routing table. */
@OptIn(ExperimentalAtomicApi::class)
internal class DhtNode(
  socket: TorrentDatagramSocket,
  scope: CoroutineScope,
  id: ByteString? = null,
  private val allowLocalAddresses: Boolean = false,
  private val queryTimeoutMs: Long = 5000,
  private val nowMs: () -> Long = monotonicClock(),
) {
  private val ipv6 = ':' in socket.local.host
  private val routingState = AtomicReference(DhtRoutingTable(id ?: run {
    val address = numericAddress(socket.local.host)!!
    if (publicTorrentAddress(address)) DhtIdentity.create(address)
    else torrentRandomBytes(20).toByteString()
  }, nowMs, enforceDiversity = !allowLocalAddresses))
  private val routing: DhtRoutingTable get() = routingState.load()
  private val voteMutex = Mutex()
  private val votes = linkedMapOf<ByteString, MutableSet<ByteString>>()
  private var voteWindow = nowMs()
  private var externalAddress: ByteString? = null
  private val tokens = DhtTokens(nowMs)
  private val stored = linkedMapOf<ByteString, LinkedHashMap<PeerEndpoint, Long>>()
  private val lookups = Semaphore(4)
  private val rpc = DhtRpc(socket, scope, ::handle, nowMs)
  private val bootstrapMutex = Mutex()
  private val bootstrapCandidates = linkedSetOf<PeerEndpoint>()

  val local: PeerEndpoint get() = rpc.local

  fun start() = rpc.start()

  suspend fun close() = rpc.close()

  suspend fun snapshot(): ByteArray = routing.snapshot()

  suspend fun bootstrap(endpoints: List<PeerEndpoint>) = bootstrapMutex.withLock {
    for (endpoint in endpoints.take(64)) {
      if (validEndpoint(endpoint)) bootstrapCandidates += canonical(endpoint)
    }
    while (bootstrapCandidates.size > 64) bootstrapCandidates.remove(bootstrapCandidates.first())
    coroutineScope {
      bootstrapCandidates.toList().chunked(3).forEach { batch ->
        batch.map { endpoint -> async { ask(endpoint, "ping", emptyMap()) } }.awaitAll()
      }
    }
    lookup(routing.localId, announcePort = null, peers = false)
  }

  suspend fun peers(hash: InfoHash, announcePort: Int? = null): List<PeerEndpoint> =
    lookups.withPermit { lookup(hash.toBytes().toByteString(), announcePort, peers = true) }

  suspend fun refresh() {
    for (target in routing.refreshTargets().take(16)) {
      lookups.withPermit { lookup(target, announcePort = null, peers = false) }
    }
  }

  private suspend fun lookup(
    target: ByteString,
    announcePort: Int?,
    peers: Boolean,
  ): List<PeerEndpoint> = coroutineScope {
    val candidates = linkedMapOf<ByteString, DhtContact>()
    routing.closest(target, 32).forEach { candidates[it.id] = it }
    val queried = mutableSetOf<PeerEndpoint>()
    val found = linkedSetOf<PeerEndpoint>()
    val announcements = mutableMapOf<DhtContact, ByteArray>()
    for (round in 0 until 32) {
      currentCoroutineContext().ensureActive()
      val batch = candidates.values.sortedBy { dhtDistance(it.id, target) }
        .filter { it.endpoint !in queried }.take(3)
      if (batch.isEmpty()) break
      queried += batch.map { it.endpoint }
      val replies = batch.map { contact -> async {
        val arguments = mapOf<String, Any>((if (peers) "info_hash" else "target") to
          target.toByteArray(), "want" to listOf(if (ipv6) "n6" else "n4"))
        contact to ask(contact.endpoint, if (peers) "get_peers" else "find_node", arguments,
          expected = contact.id)
      } }.awaitAll()
      for ((contact, reply) in replies) {
        val body = reply?.body ?: continue
        val nodes = body[if (ipv6) "nodes6" else "nodes"]?.bytes?.let {
          runCatching { DhtCodec.nodes(it, ipv6) }.getOrDefault(emptyList())
        }.orEmpty()
        for (node in nodes) {
          if (validContact(node) && candidates.size < 256) candidates[node.id] = node
        }
        body["values"]?.list?.take(64)?.forEach { value ->
          val endpoint = value.bytes?.let { runCatching { DhtCodec.endpoint(it) }.getOrNull() }
          if (endpoint != null && validEndpoint(endpoint) && found.size < 1024) found += endpoint
        }
        body["token"]?.bytes?.let { token ->
          if (token.size in 1..64) announcements[contact] = token
        }
      }
    }
    if (announcePort != null) {
      require(announcePort in 1..65535)
      announcements.entries.sortedBy { dhtDistance(it.key.id, target) }.take(8).chunked(3)
        .forEach { batch ->
          batch.map { (contact, token) -> async {
            ask(contact.endpoint, "announce_peer", mapOf("info_hash" to target.toByteArray(),
              "port" to announcePort.toLong(), "token" to token, "implied_port" to 0L), contact.id)
          } }.awaitAll()
        }
    }
    found.toList()
  }

  private suspend fun ask(
    endpoint: PeerEndpoint,
    method: String,
    arguments: Map<String, Any>,
    expected: ByteString? = null,
  ): DhtMessage? {
    if (!validEndpoint(endpoint)) return null
    val remote = canonical(endpoint)
    try {
      val response = rpc.query(remote, method, arguments + ("id" to routing.localId.toByteArray()),
        queryTimeoutMs)
      if (response != null) observeAddress(response, remote)
      if (response?.type != "r") {
        if (expected != null) routing.failed(DhtContact(expected, remote))
        return null
      }
      val id = requireNotNull(response.body?.get("id")?.bytes).toByteString()
      val contact = DhtContact(id, remote)
      if (expected != null && expected != id || !validContact(contact)) return null
      val incumbent = routing.verified(contact)
      if (incumbent != null && method != "ping") {
        ask(incumbent.endpoint, "ping", emptyMap(), incumbent.id)
        routing.verified(contact)
      }
      return response
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      if (expected != null) routing.failed(DhtContact(expected, remote))
      return null
    }
  }


  /** Require matching reports from three different network prefixes before changing identity. */
  private suspend fun observeAddress(response: DhtMessage, remote: PeerEndpoint) {
    if (allowLocalAddresses) return
    val compact = response.root["ip"]?.bytes ?: return
    val observed = runCatching { DhtCodec.endpoint(compact) }.getOrNull() ?: return
    if (!validEndpoint(observed)) return
    val address = numericAddress(observed.host)!!.toByteString()
    val voter = numericAddress(remote.host)!!.copyOf(if (ipv6) 8 else 3).toByteString()
    voteMutex.withLock {
      if (address == externalAddress) return@withLock
      if (nowMs() - voteWindow >= 600_000) {
        votes.clear()
        voteWindow = nowMs()
      }
      if (address !in votes && votes.size >= 16) votes.remove(votes.keys.first())
      val reporters = votes.getOrPut(address) { mutableSetOf() }
      reporters += voter
      if (reporters.size < 3) return@withLock
      val previous = routing
      val updated = DhtRoutingTable(DhtIdentity.create(address.toByteArray()), nowMs,
        enforceDiversity = !allowLocalAddresses)
      for (contact in previous.closest(previous.localId, 64)) updated.verified(contact)
      routingState.store(updated)
      externalAddress = address
      votes.clear()
    }
  }

  /** Called serially by the RPC receiver; never waits for another RPC. */
  private suspend fun handle(message: DhtMessage, remote: PeerEndpoint): ByteArray? {
    if (!validEndpoint(remote)) return null
    val body = requireNotNull(message.body)
    val sender = DhtContact(requireNotNull(body["id"]?.bytes).toByteString(), remote)
    if (validContact(sender)) routing.seen(sender)
    val values = mutableMapOf<String, Any>("id" to routing.localId.toByteArray())
    when (message.query) {
      "ping" -> Unit
      "find_node", "get_peers" -> {
        val target = requireNotNull(body[if (message.query == "get_peers") "info_hash" else "target"]
          ?.bytes).toByteString()
        require(target.size == 20)
        val nodes = routing.closest(target, goodOnly = true)
        values[if (ipv6) "nodes6" else "nodes"] = DhtCodec.compactNodes(nodes)
        if (message.query == "get_peers") {
          values["token"] = tokens.issue(remote)
          val entries = stored[target]
          entries?.entries?.removeAll { nowMs() - it.value >= 1_800_000 }
          if (!entries.isNullOrEmpty()) {
            values.remove(if (ipv6) "nodes6" else "nodes")
            values["values"] = entries.keys.take(if (ipv6) 32 else 64)
              .map(DhtCodec::compactEndpoint)
          }
        }
      }
      "announce_peer" -> {
        require(validContact(sender)) { "Invalid announcing node" }
        require(tokens.valid(requireNotNull(body["token"]?.bytes), remote)) { "Invalid DHT token" }
        val hash = requireNotNull(body["info_hash"]?.bytes).toByteString()
        require(hash.size == 20)
        val port = if (body["implied_port"]?.integer == 1L) remote.port.toLong()
          else requireNotNull(body["port"]?.integer)
        require(port in 1..65535)
        if (hash !in stored && stored.size >= 128) stored.remove(stored.keys.first())
        val entries = stored.getOrPut(hash) { linkedMapOf() }
        val endpoint = remote.copy(port = port.toInt())
        entries.remove(endpoint)
        if (entries.size >= 64) entries.remove(entries.keys.first())
        entries[endpoint] = nowMs()
      }
      else -> return DhtCodec.error(message.transaction, 204)
    }
    return DhtCodec.response(message.transaction, values, remote)
  }

  private fun validContact(contact: DhtContact): Boolean = validEndpoint(contact.endpoint) &&
    (allowLocalAddresses || DhtIdentity.valid(contact.id, numericAddress(contact.endpoint.host)!!))

  private fun validEndpoint(endpoint: PeerEndpoint): Boolean {
    val address = numericAddress(endpoint.host) ?: return false
    return endpoint.port != 0 && (address.size == 16) == ipv6 &&
      (allowLocalAddresses || publicTorrentAddress(address))
  }

  private fun canonical(endpoint: PeerEndpoint): PeerEndpoint =
    endpoint.copy(host = numericHost(requireNotNull(numericAddress(endpoint.host))))
}

internal fun publicTorrentAddress(address: ByteArray): Boolean {
  if (address.size == 16) {
    return address[0].toInt() and 224 == 32 &&
      !(address[0] == 0x20.toByte() && address[1] == 1.toByte() &&
        address[2] == 0x0d.toByte() && address[3] == 0xb8.toByte())
  }
  if (address.size != 4) return false
  val a = address[0].toInt() and 255
  val b = address[1].toInt() and 255
  val c = address[2].toInt() and 255
  return a !in listOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
    !(a == 169 && b == 254) && !(a == 172 && b in 16..31) &&
    !(a == 192 && (b == 168 || b == 0 && c in listOf(0, 2))) &&
    !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
    !(a == 203 && b == 0 && c == 113)
}
