package com.linroid.ketch.torrent

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** Versioned destination components, separate from authenticated raw names and caller roots. */
internal class TorrentOutputMapping private constructor(val files: List<File>) {
  val version: Int get() = 1
  data class File(val id: String, val v2Index: Int, val components: List<String>)

  private class Node {
    val children = linkedMapOf<ByteString, Node>()
    var file: Int? = null
  }

  companion object {
    /** Maps the whole document so selection changes never rename a file or its parents. */
    fun from(document: TorrentV2Document): TorrentOutputMapping {
      val root = Node()
      for ((index, file) in document.info.files.withIndex()) {
        var node = root
        for (component in file.path) {
          check(node.file == null)
          node = node.children.getOrPut(component) { Node() }
        }
        check(node.file == null && node.children.isEmpty())
        node.file = index
      }
      val paths = arrayOfNulls<List<String>>(document.info.files.size)
      fun visit(node: Node, path: List<String>) {
        node.file?.let { paths[it] = path }
        val candidates = node.children.keys.associateWith(::component)
        val groups = candidates.values.groupingBy(::collisionKey).eachCount()
        val used = hashSetOf<String>()
        for ((raw, child) in node.children) {
          val candidate = candidates.getValue(raw)
          val name = if (groups.getValue(collisionKey(candidate)) > 1) {
            val suffix = "~" + digest(raw)
            if (candidate.encodeUtf8().size + suffix.length <= 240) candidate + suffix else suffix
          } else candidate
          check(used.add(collisionKey(name))) { "Unresolvable output-name collision" }
          visit(child, path + name)
        }
      }
      visit(root, emptyList())
      return TorrentOutputMapping(document.info.files.indices.map { index ->
        File((document.hybrid?.files?.get(index)?.v1Index ?: index).toString(), index,
          checkNotNull(paths[index]))
      })
    }

    private fun component(raw: ByteString): String {
      require(raw.size > 0)
      // Avoid expanding an attacker-controlled long byte string to three times its size.
      if (raw.size > 240) return "~" + digest(raw)
      val text = raw.utf8()
      val valid = text.encodeUtf8() == raw && text.none { it == '%' || it == '~' } &&
        !reservedDevice(text) && try {
          TorrentMetadata.validatePathComponent(text)
          true
        } catch (_: IllegalArgumentException) {
          false
        }
      if (valid) return text
      if (raw.size > 80) return "~" + digest(raw)
      return buildString {
        for (byte in raw.toByteArray()) {
          val value = byte.toInt() and 255
          append('%')
          append("0123456789abcdef"[value ushr 4])
          append("0123456789abcdef"[value and 15])
        }
      }
    }

    private fun reservedDevice(value: String): Boolean {
      val stem = value.substringBefore('.').trimEnd(' ').uppercase()
      return stem in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") ||
        stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) &&
        stem.last() in "123456789¹²³"
    }

    private fun collisionKey(value: String): String =
      canonicalTorrentName(canonicalTorrentName(value).lowercase().uppercase().lowercase())

    private fun digest(raw: ByteString): String =
      sha256Digest(raw.toByteArray()).toByteString().hex()
  }
}
