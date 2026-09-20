package com.linroid.ketch.torrent

import io.ktor.http.Url
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Validated replacement, captured before lifecycle operations suspend. */
internal class TrackerConfiguration private constructor(val tiers: List<List<String>>) {
  // UTF-8 encoding and bencode's temporary copies coexist with the retained UTF-16 strings.
  // The base session lease covers the unchanged metadata/ownership portion of the checkpoint.
  val checkpointWorkspaceBytes: Int
    get() = 64 * 1024 + tiers.sumOf { tier -> tier.sumOf { it.length * 32 + 512 } }

  /** Single-owner reservation. Transfer invalidates the old handle without releasing credit. */
  @OptIn(ExperimentalAtomicApi::class)
  class Owned private constructor() {
    private class Allocation(
      val configuration: TrackerConfiguration,
      val lease: TorrentBufferBudget.Lease,
    )
    private val allocation = AtomicReference<Allocation?>(null)

    internal constructor(configuration: TrackerConfiguration, lease: TorrentBufferBudget.Lease) :
      this() { allocation.store(Allocation(configuration, lease)) }

    /** Borrow on the owner; do not retain this reference after close or transfer. */
    val configuration: TrackerConfiguration
      get() = checkNotNull(allocation.load()) {
        "Tracker configuration is no longer owned"
      }.configuration

    fun transfer(): Owned {
      // Allocate the destination before removing the only owner of the reservation.
      val moved = Owned()
      val taken = checkNotNull(allocation.exchange(null)) {
        "Tracker configuration is no longer owned"
      }
      moved.allocation.store(taken)
      return moved
    }

    fun close() { allocation.exchange(null)?.lease?.close() }
  }

  companion object {
    /** Admission precedes URL parsing and snapshots; failed validation returns all credit. */
    fun admit(tiers: List<List<String>>, budget: TorrentBufferBudget): Owned? {
      val lease = budget.reserve(reservationBytes(tiers)) ?: return null
      try { return Owned(prepare(tiers), lease) } catch (error: Throwable) {
        lease.close()
        throw error
      }
    }

    private fun reservationBytes(tiers: List<List<String>>): Int {
      require(tiers.size <= 256 && tiers.sumOf { it.size.toLong() } <= 256) {
        "Tracker configuration exceeds limit"
      }
      var bytes = 4096L + tiers.size * 64L
      for (tier in tiers) for (url in tier) {
        require(url.length in 1..8192) { "Invalid tracker URL length" }
        // UTF-16 backing plus URL/list/map/status overhead; shared strings are charged in full.
        bytes += url.length * 2L + 512
      }
      return bytes.toInt()
    }

    fun prepare(tiers: List<List<String>>): TrackerConfiguration {
      reservationBytes(tiers)
      val snapshot = tiers.map { tier ->
        tier.map { url ->
          require(url.length in 1..8192) { "Invalid tracker URL length" }
          val valid = try {
            val scheme = url.substringBefore("://", "").lowercase()
            val parsed = Url(url)
            scheme in setOf("http", "https", "udp") &&
              parsed.host.isNotEmpty() && parsed.port in 1..65535 &&
              (scheme != "udp" || (parsed.user == null && parsed.password == null))
          } catch (_: Exception) { false }
          require(valid) { "Invalid tracker URL" }
          url
        }.distinct()
      }.filter { it.isNotEmpty() }
      return TrackerConfiguration(snapshot)
    }
  }
}

/** A consistent configuration snapshot; revision zero represents metainfo or legacy overrides. */
internal data class TrackerConfigurationSnapshot(val tiers: List<List<String>>, val revision: Long)

/** A stale command is rejected before changing the active session or its persisted state. */
internal class TrackerRevisionConflict(val expected: Long, val actual: Long) :
  IllegalStateException("Tracker revision conflict: expected $expected, actual $actual")
