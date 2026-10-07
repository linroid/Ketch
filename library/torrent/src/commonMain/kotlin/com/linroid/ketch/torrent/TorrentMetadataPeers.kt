package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val log = KetchLogger("TorrentEngine")

/** Waits before asking a peer that failed again: the first retry after 5 s, the last after 30. */
internal val METADATA_RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L)

/** Distinct peers one metadata lookup asks at most. */
internal const val MAX_METADATA_PEERS = 4096

/**
 * Asks the peers [discovered] sends for metadata with [fetch], one at a time, until one answers.
 *
 * Each peer is asked as soon as it is found. One that fails is asked again after each of
 * [retryDelaysMs], behind any peers found meanwhile, so a swarm of one or two peers that are
 * briefly busy or unreachable still resolves; a peer found again while it waits is not queued
 * twice. [onPeer] gets the number of distinct peers found so far. A
 * [PrivateTorrentMagnetException] ends the lookup.
 *
 * @param label names the lookup in logs, such as "Metadata for 45b3e332a3b9".
 */
internal suspend fun <T> fetchFromMetadataPeers(
  label: String,
  discovered: ReceiveChannel<PeerEndpoint>,
  onPeer: (Int) -> Unit = {},
  retryDelaysMs: List<Long> = METADATA_RETRY_DELAYS_MS,
  fetch: suspend (PeerEndpoint) -> T,
): T = coroutineScope {
  // A peer and how many times it has been asked.
  val ready = Channel<Pair<PeerEndpoint, Int>>(Channel.UNLIMITED)
  launch {
    val seen = mutableSetOf<PeerEndpoint>()
    for (endpoint in discovered) {
      if (!seen.add(endpoint)) continue
      check(seen.size <= MAX_METADATA_PEERS) { "Metadata peer limit exceeded" }
      onPeer(seen.size)
      ready.send(endpoint to 0)
    }
  }
  fun failed(endpoint: PeerEndpoint, asked: Int, reason: String) {
    if (asked > retryDelaysMs.size) {
      log.d { "$label from $endpoint failed $asked times, giving up: $reason" }
      return
    }
    val wait = retryDelaysMs[asked - 1]
    log.v { "$label from $endpoint failed, asking again in ${wait / 1000}s: $reason" }
    launch {
      delay(wait)
      ready.send(endpoint to asked)
    }
  }
  try {
    for ((endpoint, previous) in ready) {
      val asked = previous + 1
      try {
        return@coroutineScope fetch(endpoint).also {
          log.d { "$label received from $endpoint" }
        }
      } catch (e: PrivateTorrentMagnetException) {
        throw e
      } catch (e: CancellationException) {
        // The exchange's own timeout; when the lookup is cancelled, this rethrows.
        currentCoroutineContext().ensureActive()
        failed(endpoint, asked, "timed out")
      } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        failed(endpoint, asked, e.describeWithoutUrls())
      }
    }
    error("No metadata peers")
  } finally {
    coroutineContext.cancelChildren()
  }
}
