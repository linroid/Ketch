package com.linroid.ketch.torrent

import okio.ByteString.Companion.toByteString
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TorrentV2CheckpointTest {
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L,
    "file tree" to mapOf("dir" to mapOf("file" to mapOf("" to mapOf("length" to 1L,
      "pieces root" to sha256Digest(byteArrayOf(1))))))
  ), "piece layers" to emptyMap<String, Any>())))
  private val output = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "checkpoint-output").toString()
  private val owned = listOf(TorrentV2Checkpoint.Owned(emptyList(), "root-id", true),
    TorrentV2Checkpoint.Owned(listOf("dir"), "dir-id", true),
    TorrentV2Checkpoint.Owned(listOf("dir", "file"), "file-id", false))
  private fun checkpoint() = TorrentV2Checkpoint.create(document, "task", output, setOf("0"), owned,
    byteArrayOf(0x80.toByte()).toByteString(), layoutGeneration = 7, selectionGeneration = 9,
    receivedBytes = 123, uploadedBytes = 45)

  private fun mutate(change: (MutableMap<String, Any>) -> Unit): ByteArray {
    val values = mutableMapOf<String, Any>("kind" to "ketch-kotlin-torrent", "version" to 2L,
      "mapping" to 1L, "task" to "task", "v2" to document.info.hash.toBytes(),
      "output" to output, "selected" to listOf("0"),
      "owned" to listOf(listOf(0L, "", "root-id"), listOf(1L, "dir", "dir-id"),
        listOf(-2L, "file", "file-id")), "verified" to byteArrayOf(0x80.toByte()),
      "layout generation" to 7L, "selection generation" to 9L,
      "received" to 123L, "uploaded" to 45L)
    change(values)
    return "KETCH-TORRENT\n".encodeToByteArray() + Bencode.encode(values)
  }

  @Test
  fun roundTripBindsCatalogMappingAndCompactParentReferences() {
    val decoded = assertNotNull(TorrentV2Checkpoint.decode(checkpoint().encode()))
    decoded.validateContent(document)
    assertEquals(document.identity, decoded.identity)
    assertEquals(document.info.hash, decoded.catalogKey)
    assertEquals(owned, decoded.owned)
    assertEquals(setOf("0"), decoded.selected)
    assertEquals(7L, decoded.layoutGeneration)
    assertEquals(9L, decoded.selectionGeneration)
    assertEquals(123L, decoded.receivedBytes)
    assertEquals(45L, decoded.uploadedBytes)
    // A bit remains explicitly a hint: decoding exposes no committed-progress value.
    assertEquals(byteArrayOf(0x80.toByte()).toByteString(), decoded.verifiedHint)
  }

  @Test
  fun contentValidationRejectsForeignOwnershipSelectionsAndHintPadding() {
    for (bytes in listOf(
      mutate { it["selected"] = listOf("999") },
      mutate { it["verified"] = byteArrayOf(0x81.toByte()) },
      mutate { it["owned"] = listOf(listOf(0L, "", "root-id"), listOf(-1L, "foreign", "id")) },
      mutate { it["v2"] = ByteArray(32) }
    )) {
      val decoded = assertNotNull(TorrentV2Checkpoint.decode(bytes))
      assertFailsWith<IllegalArgumentException> { decoded.validateContent(document) }
    }
  }

  @Test
  fun rejectsMalformedReferencesTraversalDuplicatesAndFutureVersions() {
    val invalid = listOf(
      mutate { it["version"] = 3L },
      mutate { it["mapping"] = 2L },
      mutate { it["selection generation"] = -1L },
      mutate { it["selected"] = listOf("0", "0") },
      mutate { it["output"] = "relative/output" },
      mutate { it["owned"] = listOf(listOf(0L, "", "id"), listOf(-2L, "file", "id")) },
      mutate { it["owned"] = listOf(listOf(0L, "", "id"), listOf(-1L, "..", "id")) },
      mutate { it["owned"] = listOf(listOf(0L, "", "id"), listOf(-1L, "file", "id"),
        listOf(-1L, "file", "other")) },
      mutate { it["owned"] = listOf(listOf(0L, "", "id"), listOf(-1L, "file", "id"),
        listOf(-2L, "child", "id")) }
    )
    for (bytes in invalid) {
      assertFailsWith<IllegalArgumentException> { TorrentV2Checkpoint.decode(bytes) }
    }
    assertFails {
      TorrentV2Checkpoint.decode(mutate { it["task"] = byteArrayOf(0xff.toByte()) })
    }
  }

  @Test
  fun retainsBothHybridIdentitiesAndResolvesDefaultSelection() {
    val payload = byteArrayOf(1)
    val doc = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
      "meta version" to 2L, "piece length" to 16_384L, "name" to "file", "length" to 1L,
      "pieces" to sha1Digest(payload), "file tree" to mapOf("file" to mapOf("" to mapOf(
        "length" to 1L, "pieces root" to sha256Digest(payload))))
    ), "piece layers" to emptyMap<String, Any>())))
    val checkpoint = TorrentV2Checkpoint.create(doc, "task", output, emptySet(),
      listOf(owned.first(), TorrentV2Checkpoint.Owned(listOf("file"), "file-id", false)),
      byteArrayOf(0).toByteString())
    val decoded = assertNotNull(TorrentV2Checkpoint.decode(checkpoint.encode()))
    decoded.validateContent(doc)
    assertNotNull(decoded.identity.v1)
    assertEquals(doc.identity, decoded.identity)
    assertEquals(setOf("0"), decoded.selected)
  }

  @Test
  fun preservesVersionOneDecoderAndNeverTreatsVersionTwoAsNativeState() {
    assertNull(TorrentV2Checkpoint.decode(byteArrayOf(1, 2, 3)))
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "file", "piece length" to 16_384L, "length" to 0L, "pieces" to byteArrayOf()
    ))))
    val v1 = TorrentCheckpoint("task", metadata, output, emptySet(), BooleanArray(0),
      emptyList(), emptyList()).encode()
    assertNull(TorrentV2Checkpoint.decode(v1))
    assertNotNull(TorrentCheckpoint.decode(v1))
    assertFailsWith<IllegalArgumentException> { TorrentCheckpoint.decode(checkpoint().encode()) }
  }
}
