package com.linroid.ketch.engine

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.core.engine.ServerInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublishedChecksumTest {
  private val sha256 = "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0="
  private val sha256Hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  private val md5 = "kAFQmDzST7DWlj99KOF/cg=="

  @Test
  fun reprDigest_readsStructuredDictionary() {
    val checksum = ServerInfo.publishedChecksum(
      reprDigest = "sha-256=:$sha256:",
      digest = null,
      contentEncoding = null,
    )
    assertEquals(Checksum(ChecksumAlgorithm.SHA_256, sha256Hex), checksum)
  }

  @Test
  fun reprDigest_picksStrongestAndSkipsUnknown() {
    val checksum = ServerInfo.publishedChecksum(
      reprDigest = "unixsum=:AAAA:, md5=:$md5:;param=1, sha-256=:$sha256:",
      digest = null,
      contentEncoding = "identity",
    )
    assertEquals(ChecksumAlgorithm.SHA_256, checksum?.algorithm)
  }

  @Test
  fun legacyDigest_readsBase64AndShaMeansSha1() {
    assertEquals(
      Checksum(ChecksumAlgorithm.SHA_256, sha256Hex),
      ServerInfo.publishedChecksum(null, "SHA-256=$sha256", null),
    )
    assertEquals(
      ChecksumAlgorithm.SHA_1,
      ServerInfo.publishedChecksum(null, "SHA=qZk+NkcGgWq6PiVxeFDCbJzQ2J0=", null)?.algorithm,
    )
  }

  @Test
  fun reprDigest_preferredOverLegacyDigest() {
    val checksum = ServerInfo.publishedChecksum("md5=:$md5:", "SHA-256=$sha256", null)
    assertEquals(ChecksumAlgorithm.MD5, checksum?.algorithm)
  }

  @Test
  fun contentEncoding_publishesNothing() {
    assertNull(ServerInfo.publishedChecksum("sha-256=:$sha256:", "SHA-256=$sha256", "gzip"))
  }

  @Test
  fun malformedValues_publishNothing() {
    assertNull(ServerInfo.publishedChecksum("sha-256=:AAAA:", "SHA-256", null))
    assertNull(ServerInfo.publishedChecksum("", "  ", null))
  }
}
