package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.net.PeerEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration

private val log = KetchLogger("TorrentEngine")

/**
 * How long a metadata exchange, or a lookup past its time, goes on without receiving any of the
 * metadata before it gives up.
 */
internal const val METADATA_IDLE_MS = 30_000L

/**
 * Counts the pieces of metadata an exchange receives (info blocks and piece layer hashes), so
 * [withTimeoutUnlessProgressing] lets an exchange, or the lookup it belongs to, go on while they
 * arrive. Each count is passed on to [then]: an exchange's to its lookup's.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class MetadataProgress(private val then: (() -> Unit)? = null) {
  private val count = AtomicLong(0)

  val value: Long get() = count.load()

  fun advance() {
    count.incrementAndFetch()
    then?.invoke()
  }
}

/**
 * Runs [block] for [time], and past it while [progress] advances at least once every [idle]: a
 * peer still sending a large torrent's metadata, however slowly, is not cut off at a fixed time.
 * Fails with [TimeoutCancellationException], as [withTimeout] does, once it may not go on.
 */
internal suspend fun <T> withTimeoutUnlessProgressing(
  time: Duration,
  idle: Duration,
  progress: MetadataProgress,
  block: suspend CoroutineScope.() -> T,
): T = coroutineScope { awaitWhileProgressing(async(block = block), time, idle, progress) }

private suspend fun <T> awaitWhileProgressing(
  work: Deferred<T>,
  time: Duration,
  idle: Duration,
  progress: MetadataProgress,
): T {
  var window = time
  while (true) {
    val before = progress.value
    try {
      return withTimeout(window) { work.await() }
    } catch (e: TimeoutCancellationException) {
      // The block's own timeout, not this window's.
      if (work.isCompleted) return work.await()
      if (progress.value == before) {
        work.cancel()
        throw e
      }
      window = idle
    }
  }
}

/** Waits before asking a peer that failed again: the first retry after 5 s, the last after 30. */
internal val METADATA_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L)

/** Distinct peers one metadata lookup asks at most; it ignores the peers found after them. */
internal const val MAX_METADATA_PEERS = 4096

/**
 * Peers one metadata lookup asks at once. Each holds a connection; only one at a time reserves
 * the metadata buffer, after its handshake, so peers that never answer cannot keep a live one
 * waiting. Most peers a swarm lists cannot be reached, so few at once leave most unasked.
 */
internal const val METADATA_PARALLEL_PEERS = 16

/**
 * Asks the peers [discovered] sends for metadata with [fetch], [parallelism] at a time, until
 * one answers.
 *
 * Each peer is asked as soon as it is found and a slot is free. One that fails is asked again
 * after each of [retryDelaysMs], behind any peers found meanwhile, so a swarm of one or two
 * peers that are briefly busy or unreachable still resolves; a peer found again while it waits
 * is not queued twice. Once [MAX_METADATA_PEERS] are found, further peers are ignored while the
 * lookup goes on with those. [onPeer] gets the number of distinct peers found so far, [onAsked]
 * the number asked so far. A peer [skip] matches when its turn comes is dropped, retries
 * included, as what it rules out may be learned after the peer was found. A
 * [MetadataLookupFailure], which every peer would cause alike, ends the lookup.
 *
 * @param label names the lookup in logs, such as "Metadata for 45b3e332a3b9".
 */
internal suspend fun <T> fetchFromMetadataPeers(
  label: String,
  discovered: ReceiveChannel<PeerEndpoint>,
  onPeer: (Int) -> Unit = {},
  onAsked: (Int) -> Unit = {},
  skip: (PeerEndpoint) -> Boolean = { false },
  retryDelaysMs: List<Long> = METADATA_RETRY_DELAYS_MS,
  parallelism: Int = METADATA_PARALLEL_PEERS,
  fetch: suspend (PeerEndpoint) -> T,
): T = coroutineScope {
  // A peer and how many times it has been asked.
  val ready = Channel<Pair<PeerEndpoint, Int>>(Channel.UNLIMITED)
  val found = CompletableDeferred<T>()
  val slots = Semaphore(parallelism)
  launch {
    val seen = mutableSetOf<PeerEndpoint>()
    var full = false
    for (endpoint in discovered) {
      if (endpoint in seen) continue
      if (seen.size == MAX_METADATA_PEERS) {
        if (!full) log.d { "$label: $MAX_METADATA_PEERS peers found, ignoring further peers" }
        full = true
        continue
      }
      seen += endpoint
      onPeer(seen.size)
      log.v { "$label: found peer $endpoint (${seen.size} so far)" }
      ready.send(endpoint to 0)
    }
  }
  fun failed(endpoint: PeerEndpoint, asked: Int, reason: String) {
    if (asked > retryDelaysMs.size) {
      log.v { "$label from $endpoint failed $asked times, giving up: $reason" }
      return
    }
    val wait = retryDelaysMs[asked - 1]
    log.v { "$label from $endpoint failed, asking again in ${wait / 1000}s: $reason" }
    launch {
      delay(wait)
      ready.send(endpoint to asked)
    }
  }
  suspend fun ask(endpoint: PeerEndpoint, asked: Int) {
    try {
      log.v { "$label: asking $endpoint (attempt $asked)" }
      if (found.complete(fetch(endpoint))) log.d { "$label received from a peer" }
    } catch (e: MetadataLookupFailure) {
      found.completeExceptionally(e)
    } catch (e: CancellationException) {
      // The exchange's own timeout; when the lookup is cancelled, this rethrows.
      currentCoroutineContext().ensureActive()
      failed(endpoint, asked, "timed out")
    } catch (e: Exception) {
      currentCoroutineContext().ensureActive()
      failed(endpoint, asked, e.describeWithoutUrls())
    }
  }
  launch {
    // Counted here, the one coroutine that hands out slots.
    var askedPeers = 0
    for ((endpoint, previous) in ready) {
      if (skip(endpoint)) continue
      slots.acquire()
      if (previous == 0) onAsked(++askedPeers)
      launch {
        try {
          ask(endpoint, previous + 1)
        } finally {
          slots.release()
        }
      }
    }
  }
  try {
    found.await()
  } finally {
    coroutineContext.cancelChildren()
  }
}
