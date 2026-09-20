package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath

/** Version 2 state references authenticated catalog content; piece bits never prove progress. */
internal class TorrentV2Checkpoint private constructor(
  val taskId: String,
  val identity: TorrentIdentity,
  val output: String,
  val selected: Set<String>,
  val owned: List<Owned>,
  val verifiedHint: ByteString,
  val layoutGeneration: Long,
  val selectionGeneration: Long,
  val receivedBytes: Long,
  val uploadedBytes: Long,
) {
  data class Owned(val components: List<String>, val identity: String, val directory: Boolean)
  val mappingVersion: Int get() = 1
  val catalogKey: V2InfoHash get() = checkNotNull(identity.v2)

  /** Required after catalog resolution, before a store considers any ownership claim. */
  fun validateContent(document: TorrentV2Document) {
    require(document.identity == identity) { "Checkpoint content identity mismatch" }
    val mapping = TorrentOutputMapping.from(document)
    val fileIds = mapping.files.map { it.id }.toSet()
    require(selected.all { it in fileIds }) { "Unknown selected file" }
    val files = mapping.files.map { it.components }.toSet()
    val directories = buildSet {
      add(emptyList<String>())
      for (path in files) {
        var parent = path.dropLast(1)
        while (add(parent) && parent.isNotEmpty()) parent = parent.dropLast(1)
      }
    }
    for (entry in owned) {
      require(entry.components in if (entry.directory) directories else files) {
        "Ownership record is outside the authenticated mapping"
      }
    }
    val count = TorrentContentLayout.from(document.info, document.hybrid).pieceCount
    require(count <= 1_000_000 && verifiedHint.size.toLong() == (count + 7) / 8)
    if (count % 8 != 0L) {
      val unused = (1 shl (8 - (count % 8).toInt())) - 1
      require(verifiedHint[verifiedHint.size - 1].toInt() and unused == 0) {
        "Invalid hint padding"
      }
    }
  }

  fun encode(): ByteArray {
    validateStructure()
    val sorted = owned.sortedBy { it.components.size }
    val indices = sorted.mapIndexed { index, entry -> entry.components to index }.toMap()
    val rows = sorted.map { entry ->
      val parent = if (entry.components.isEmpty()) 0 else
        indices.getValue(entry.components.dropLast(1)) + 1
      listOf(if (entry.directory) parent.toLong() else -parent.toLong(),
        entry.components.lastOrNull() ?: "", entry.identity)
    }
    val values = buildMap<String, Any> {
      put("kind", KIND)
      put("version", 2L)
      put("mapping", mappingVersion.toLong())
      put("task", taskId)
      put("v2", catalogKey.toBytes())
      identity.v1?.let { put("v1", it.toBytes()) }
      put("output", output)
      put("selected", selected.sorted())
      put("owned", rows)
      put("verified", verifiedHint)
      put("layout generation", layoutGeneration)
      put("selection generation", selectionGeneration)
      put("received", receivedBytes)
      put("uploaded", uploadedBytes)
    }
    return MAGIC + Bencode.encode(values, maxBytes = MAX_BYTES - MAGIC.size)
  }

  private fun validateStructure() {
    require(identity.v2 != null)
    require(taskId.length in 1..128 && taskId.all { it.isLetterOrDigit() || it == '-' })
    require(output.encodeToByteArray().size <= 8192 && '\u0000' !in output)
    val path = output.toPath()
    require(path.isAbsolute && path.parent != null && path == output.toPath(normalize = true))
    require(selected.size in 1..100_000 && selected.all { it.isNotEmpty() && it.length <= 128 })
    require(verifiedHint.size <= 125_000)
    require(layoutGeneration >= 0 && selectionGeneration >= 0 &&
      receivedBytes >= 0 && uploadedBytes >= 0)
    require(owned.size in 1..220_000)
    val entries = owned.associateBy { it.components }
    require(entries.size == owned.size && entries[emptyList()]?.directory == true)
    for (entry in owned) {
      require(entry.components.size <= 64 && entry.identity.length in 1..512)
      entry.components.forEach(TorrentMetadata::validatePathComponent)
      if (entry.components.isNotEmpty()) {
        require(entries[entry.components.dropLast(1)]?.directory == true) { "Missing owned parent" }
      }
    }
  }

  companion object {
    const val MAX_BYTES = 32 * 1024 * 1024
    private val MAGIC = "KETCH-TORRENT\n".encodeToByteArray()
    private const val KIND = "ketch-kotlin-torrent"

    fun create(
      document: TorrentV2Document,
      taskId: String,
      output: String,
      selected: Set<String>,
      owned: List<Owned>,
      verifiedHint: ByteString,
      layoutGeneration: Long = 0,
      selectionGeneration: Long = 0,
      receivedBytes: Long = 0,
      uploadedBytes: Long = 0,
    ): TorrentV2Checkpoint = TorrentV2Checkpoint(taskId, document.identity, output,
      selected.ifEmpty { TorrentOutputMapping.from(document).files.map { it.id }.toSet() }.toSet(),
      owned.map { it.copy(components = it.components.toList()) }, verifiedHint,
      layoutGeneration, selectionGeneration, receivedBytes, uploadedBytes).also {
      it.validateStructure()
      it.validateContent(document)
    }

    /** Null routes legacy/native and version 1 state to their existing decoders. */
    fun decode(bytes: ByteArray): TorrentV2Checkpoint? {
      if (bytes.size < MAGIC.size ||
        !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
      require(bytes.size <= MAX_BYTES)
      val root = Bencode.parse(bytes.copyOfRange(MAGIC.size, bytes.size), MAX_BYTES, 1_000_000)
      require(root["kind"]?.text() == KIND)
      val version = requireNotNull(root["version"]?.integer)
      if (version == 1L) return null
      require(version == 2L) { "Unsupported torrent checkpoint version: $version" }
      require(root["mapping"]?.integer == 1L) { "Unsupported output mapping version" }
      fun text(node: Bencode.Node?, max: Int): String {
        val raw = requireNotNull(node?.bytes)
        require(raw.size <= max)
        return raw.decodeToString(throwOnInvalidSequence = true)
      }
      val v2 = V2InfoHash.fromBytes(requireNotNull(root["v2"]?.bytes))
      val v1 = root["v1"]?.let { InfoHash.fromBytes(requireNotNull(it.bytes)) }
      val selection = requireNotNull(root["selected"]?.list)
      require(selection.size in 1..100_000)
      val selected = selection.map { text(it, 512) }.toSet()
      require(selected.size == selection.size)
      val rows = requireNotNull(root["owned"]?.list)
      require(rows.size in 1..220_000)
      val owned = mutableListOf<Owned>()
      for ((index, row) in rows.withIndex()) {
        val values = requireNotNull(row.list)
        require(values.size == 3)
        val parent = requireNotNull(values[0].integer)
        val name = text(values[1], 255)
        val id = text(values[2], 2048)
        val path = if (index == 0) {
          require(parent == 0L && name.isEmpty())
          emptyList()
        } else {
          require(parent != 0L && parent in -index.toLong()..index.toLong())
          val previous = owned[kotlin.math.abs(parent).toInt() - 1]
          require(previous.directory && previous.components.size < 64 && name.isNotEmpty())
          previous.components + name
        }
        owned += Owned(path, id, parent >= 0)
      }
      return TorrentV2Checkpoint(text(root["task"], 512), TorrentIdentity(v1, v2),
        text(root["output"], 8192), selected, owned,
        requireNotNull(root["verified"]?.bytes).toByteString(),
        requireNotNull(root["layout generation"]?.integer),
        requireNotNull(root["selection generation"]?.integer),
        requireNotNull(root["received"]?.integer), requireNotNull(root["uploaded"]?.integer)).also {
        it.validateStructure()
      }
    }
  }
}
