package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerHashExchangeTest {
  private val hashes = (sha256Digest(ByteArray(16_384)) + sha256Digest(byteArrayOf(1)))
    .toByteString()
  private val root = sha256Digest(hashes.toByteArray()).toByteString()
  private val selector = PeerHashSelector(root, 0, 0, 2, 0)
  private val response = PeerHashMessage.Hashes(selector, hashes)

  @Test
  fun authenticatedResultsRetainCreditUntilConsumerClose() {
    val budget = TorrentBufferBudget(32_768)
    val exchange = PeerHashExchange(budget, { if (it == root) 16_385L else null })
    assertNotNull(exchange.request(selector))
    val reserved = budget.allocated
    assertTrue(reserved > hashes.size)
    val verified = exchange.receive(response)
    assertEquals(selector, verified.selector)
    assertEquals(hashes, verified.hashes)
    assertEquals(0, exchange.pendingCount)
    assertEquals(reserved, budget.allocated)
    exchange.close()
    assertEquals(reserved, budget.allocated)
    assertFailsWith<IllegalStateException> { exchange.request(selector) }
    verified.close()
    verified.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun admissionRejectsUnknownBoundsDuplicatesAndAggregateExhaustion() {
    val budget = TorrentBufferBudget(10_000)
    val otherRoot = sha256Digest(byteArrayOf(2)).toByteString()
    val first = PeerHashExchange(budget,
      { if (it == root || it == otherRoot) 16_385L else null }, maxPending = 1)
    val second = PeerHashExchange(budget, { if (it == root) 16_385L else null })
    assertFailsWith<IllegalArgumentException> {
      first.request(selector.copy(root = ByteArray(32).toByteString()))
    }
    assertFailsWith<IllegalArgumentException> { first.request(selector.copy(index = 2)) }
    assertEquals(0, budget.allocated)
    val ticket = assertNotNull(first.request(selector))
    val reserved = budget.allocated
    assertNull(first.request(selector))
    assertNull(first.request(selector.copy(root = otherRoot)))
    assertNull(second.request(selector))
    assertEquals(reserved, budget.allocated)
    first.cancel(ticket)
    assertNotNull(second.request(selector))
    first.close()
    second.close()
    assertEquals(0, budget.allocated)
  }

  @Test
  fun invalidUnsolicitedAndRejectedRepliesCannotPublishOrLeakCredit() {
    val budget = TorrentBufferBudget(32_768)
    val exchange = PeerHashExchange(budget, { if (it == root) 16_385L else null })
    assertFailsWith<IllegalArgumentException> { exchange.receive(response) }
    assertNotNull(exchange.request(selector))
    val wrong = response.copy(selector = selector.copy(index = 2))
    assertFailsWith<IllegalArgumentException> { exchange.receive(wrong) }
    assertEquals(1, exchange.pendingCount)
    assertFailsWith<IllegalArgumentException> {
      exchange.receive(response.copy(hashes = ByteArray(64).toByteString()))
    }
    assertEquals(0, budget.allocated)
    assertNotNull(exchange.request(selector))
    assertTrue(exchange.reject(PeerHashMessage.Reject(selector)))
    assertFalse(exchange.reject(PeerHashMessage.Reject(selector)))
    assertEquals(0, budget.allocated)
    exchange.close()
  }

  @Test
  fun timersAndStaleCallbacksCannotReleaseReplacementRequests() {
    var now = 0L
    val budget = TorrentBufferBudget(32_768)
    val exchange = PeerHashExchange(budget, { if (it == root) 16_385L else null },
      timeoutMs = 10, clock = { now })
    val old = assertNotNull(exchange.request(selector))
    now = 9
    assertTrue(exchange.expire().isEmpty())
    now = 10
    assertEquals(listOf(old), exchange.expire())
    assertEquals(0, budget.allocated)
    assertNotNull(exchange.request(selector))
    exchange.cancel(old)
    assertEquals(1, exchange.pendingCount)
    now = 20
    // Enforce the deadline even if the periodic timer has not fired yet.
    assertFailsWith<IllegalArgumentException> { exchange.receive(response) }
    assertEquals(0, budget.allocated)
    assertNotNull(exchange.request(selector))
    exchange.close()
    assertEquals(0, budget.allocated)
  }
}
