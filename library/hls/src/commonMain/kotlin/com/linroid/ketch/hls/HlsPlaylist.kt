package com.linroid.ketch.hls

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.media.MediaPart
import com.linroid.ketch.core.media.MediaPlan
import com.linroid.ketch.core.media.mediaUrl
internal data class HlsVariant(val url: String, val bandwidth: Long)
internal data class HlsPlaylist(val plan: MediaPlan?, val variants: List<HlsVariant>)

/** Finite HLS playlists with one compatible media stream, per RFC 8216. */
internal fun parseHls(text: String, base: String): HlsPlaylist {
  val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).toList()
  mediaRequire(lines.firstOrNull() == "#EXTM3U", "Invalid HLS playlist")
  val parts = mutableListOf<MediaPart>()
  val variants = mutableListOf<HlsVariant>()
  var stream: Map<String, String>? = null
  var nextRange: String? = null
  var previous: MediaPart? = null
  var init: MediaPart? = null
  var duration = false
  for (line in lines.drop(1)) {
    when {
      line.startsWith("#EXT-X-STREAM-INF:") -> stream = attributes(line.substringAfter(':'))
      line.startsWith("#EXT-X-MEDIA:") -> {
        val attrs = attributes(line.substringAfter(':'))
        mediaRequire(attrs["URI"] == null || attrs["TYPE"] !in setOf("AUDIO", "VIDEO"),
          "Separate audio and video renditions require a media merger")
      }
      line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> {
        mediaRequire(attributes(line.substringAfter(':'))["METHOD"] == "NONE",
          "Encrypted media is not supported")
      }
      line == "#EXT-X-DISCONTINUITY" || line == "#EXT-X-GAP" ||
        line.startsWith("#EXT-X-PART:") || line == "#EXT-X-I-FRAMES-ONLY" ->
        mediaRequire(false, "This HLS playlist requires media processing")
      line.startsWith("#EXT-X-MAP:") -> {
        val attrs = attributes(line.substringAfter(':'))
        val uri = attrs["URI"]
        mediaRequire(uri != null, "Missing HLS initialization URL")
        val part = MediaPart(mediaUrl("hls", base, uri!!), attrs["BYTERANGE"]?.let {
          hlsRange(it, null)
        })
        mediaRequire(previous == null || init != null, "Late HLS initialization is not supported")
        mediaRequire(init == null || init == part, "Changing HLS initialization is not supported")
        if (init == null) { parts += part; init = part }
      }
      line.startsWith("#EXT-X-BYTERANGE:") -> nextRange = line.substringAfter(':')
      line.startsWith("#EXTINF:") -> duration = true
      line.isEmpty() || line.startsWith('#') -> Unit
      else -> {
        val url = mediaUrl("hls", base, line)
        val variant = stream
        if (variant != null) {
          variants += HlsVariant(url, variant["BANDWIDTH"]?.toLongOrNull() ?: 0)
          mediaRequire(variants.size <= 100, "Too many HLS variants")
          stream = null
        } else {
          mediaRequire(duration, "Missing HLS segment duration")
          val range = nextRange?.let { value ->
            hlsRange(value, previous?.takeIf { it.url == url }?.range?.last?.plus(1))
          }
          val part = MediaPart(url, range)
          parts += part
          previous = part
          nextRange = null
          duration = false
          mediaRequire(parts.size <= 10_000, "Too many media segments")
        }
      }
    }
  }
  mediaRequire(stream == null && !duration && nextRange == null, "Incomplete HLS playlist")
  if (variants.isNotEmpty()) {
    mediaRequire(parts.isEmpty(), "Mixed HLS master and media playlist")
    return HlsPlaylist(null, variants)
  }
  mediaRequire("#EXT-X-ENDLIST" in lines, "Live streams are not supported")
  mediaRequire(previous != null, "HLS playlist contains no segments")
  val extension = if (init != null) "mp4" else {
    val suffix = previous!!.url.substringBefore('?').substringAfterLast('.').lowercase()
    mediaRequire(suffix in setOf("ts", "aac", "mp3"),
      "HLS requires transport-stream, AAC, MP3 or initialized MP4 segments")
    mediaRequire(parts.all {
      it.url.substringBefore('?').substringAfterLast('.').lowercase() == suffix
    }, "Mixed HLS containers require a media merger")
    suffix
  }
  return HlsPlaylist(MediaPlan(parts, extension), emptyList())
}

private fun hlsRange(value: String, implicit: Long?): LongRange {
  val length = value.substringBefore('@').toLongOrNull()
  val start = if ('@' in value) value.substringAfter('@').toLongOrNull() else implicit
  mediaRequire(length != null && length > 0 && start != null && start >= 0 &&
    length <= Long.MAX_VALUE - start, "Invalid HLS byte range")
  return start!! until start + length!!
}

private fun attributes(value: String): Map<String, String> {
  val result = mutableMapOf<String, String>()
  val pattern = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]+)(?:,|$)")
  var offset = 0
  while (offset < value.length) {
    val match = pattern.matchAt(value, offset)
    mediaRequire(match != null, "Invalid HLS attributes")
    val key = match!!.groupValues[1]
    mediaRequire(key !in result, "Duplicate HLS attribute")
    result[key] = match.groupValues[2].removeSurrounding("\"")
    offset = match.range.last + 1
  }
  return result
}

internal fun mediaRequire(value: Boolean, message: String) {
  if (!value) throw KetchError.SourceError("hls", detail = message)
}
