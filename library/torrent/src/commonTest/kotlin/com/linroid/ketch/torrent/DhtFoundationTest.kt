package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DhtFoundationTest {
  @Test
  fun nodeIdentity_matchesPublishedBep42VectorsAndCrc32cVector() {
    val vectors = listOf(
      "124.31.75.21" to "5fbfbff10c5d6a4ec8a88e4c6ab4c28b95eee401",
      "21.75.31.124" to "5a3ce9c14e7a08645677bbd1cfe7d8f956d53256",
      "65.23.51.170" to "a5d43220bc8f112a3d426c84764f8c2a1150e616",
      "84.124.73.14" to "1b0321dd1bb1fe518101ceef99462b947a01ff41",
      "43.213.53.83" to "e56f6cbf5b7c4be0237986d5243b87aa6d51305a"
    )
    for ((host, hex) in vectors) {
      val address = assertNotNull(numericAddress(host))
      assertTrue(DhtIdentity.valid(hex.decodeHex(), address))
      val invalid = hex.decodeHex().toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
      assertFalse(DhtIdentity.valid(invalid.toByteString(), address))
    }
    assertEquals(0xe3069283.toInt(), DhtIdentity.crc32c("123456789".encodeToByteArray()))
    val ipv6 = assertNotNull(numericAddress("2001:4860:4860::8888"))
    assertTrue(DhtIdentity.valid(DhtIdentity.create(ipv6), ipv6))
  }

  @Test
  fun tokens_bindToIpAllowPortChangesAndExpireAcrossIdleWindows() {
    var now = 0L
    val tokens = DhtTokens { now }
    val address = PeerEndpoint("127.0.0.1", 1)
    val token = tokens.issue(address)
    assertTrue(tokens.valid(token, address.copy(port = 2)))
    assertFalse(tokens.valid(token, PeerEndpoint("127.0.0.2", 1)))
    now = 300_000
    assertTrue(tokens.valid(token, address))
    now = 600_000
    assertFalse(tokens.valid(token, address))
    val next = tokens.issue(address)
    now = 1_800_000
    assertFalse(tokens.valid(next, address))
  }

  @Test
  fun numericAddressesAndCompactContacts_preserveBothFamilies() {
    assertContentEquals(ByteArray(15) + byteArrayOf(1), numericAddress("::1"))
    assertNull(numericAddress("1::2::3"))
    assertNull(numericAddress("999.1.2.3"))
    assertNull(numericAddress("tracker.example"))
    assertEquals(16, numericAddress("::ffff:192.0.2.1")?.size)
    for (host in listOf("127.0.0.1", "0:0:0:0:0:0:0:1")) {
      val node = DhtContact(ByteArray(20).toByteString(), PeerEndpoint(host, 6881))
      assertEquals(listOf(node), DhtCodec.nodes(DhtCodec.compactNodes(listOf(node)), ':' in host))
    }
    assertFailsWith<IllegalArgumentException> { DhtCodec.nodes(ByteArray(27), false) }
    assertFailsWith<IllegalArgumentException> { DhtCodec.parse(ByteArray(4097)) }
  }

  @Test
  fun routing_splitsOwnRangeKeepsHealthyIncumbentsAndReplacesFailedOnes() = runTest {
    var now = 0L
    val table = DhtRoutingTable(ByteArray(20).toByteString(), nowMs = { now })
    fun node(value: Int) = DhtContact(ByteArray(20).also { it[0] = value.toByte() }.toByteString(),
      PeerEndpoint("127.0.0.1", value + 1))
    for (value in 128..135) table.verified(node(value))
    assertNull(table.verified(node(136)))
    assertEquals(8, table.closest(node(136).id).size)
    assertFalse(node(136) in table.closest(node(136).id))
    now = 900_000
    val questionable = assertNotNull(table.verified(node(136)))
    table.failed(questionable)
    table.failed(questionable)
    table.verified(node(136))
    assertEquals(node(136), table.closest(node(136).id).first())
    table.verified(node(1))
    assertEquals(node(1), table.closest(node(1).id).first())
    val restored = DhtRoutingTable.restore(table.snapshot())
    assertEquals(9, restored.second.size)
    assertEquals(table.localId, restored.first)
    now += 900_000
    assertTrue(table.refreshTargets().isNotEmpty())
    assertTrue(table.refreshTargets().isEmpty())
  }

  @Test
  fun responseQuota_boundsSourceAndGlobalReflectionTraffic() {
    var now = 0L
    val quota = DhtReplyQuota(nowMs = { now })
    repeat(20) { assertTrue(quota.admitQuery("source")) }
    assertFalse(quota.admitQuery("source"))
    repeat(4) { assertTrue(quota.admitReply("source", 1024)) }
    assertFalse(quota.admitReply("source", 1))
    now = 1000
    assertTrue(quota.admitQuery("source"))
    assertTrue(quota.admitReply("source", 1024))
  }
}
