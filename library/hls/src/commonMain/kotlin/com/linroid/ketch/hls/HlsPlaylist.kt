package com.linroid.ketch.hls

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.media.MediaKey
import com.linroid.ketch.core.media.MediaPart
import com.linroid.ketch.core.media.MediaPlan
import com.linroid.ketch.core.media.mediaUrl

/** An `EXT-X-STREAM-INF` variant of a master playlist and its attributes. */
internal data class HlsVariant(
  val url: String,
  val bandwidth: Long,
  val attributes: Map<String, String> = emptyMap(),
) {
  /** The `GROUP-ID` of the audio renditions it plays with, if any. */
  val audioGroup: String? get() = attributes["AUDIO"]

  /** Whether it declares audio codecs only and no picture size. */
  val audioOnly: Boolean
    get() {
      if (attributes["RESOLUTION"] != null) return false
      val codecs = attributes["CODECS"]?.split(',')?.map { it.trim().lowercase() }
        ?: return false
      return codecs.isNotEmpty() && codecs.all { codec -> AUDIO_CODECS.any(codec::startsWith) }
    }

  private companion object {
    val AUDIO_CODECS = listOf("mp4a", "ac-3", "ec-3", "ac-4", "opus", "flac", "alac", "mp3")
  }
}

/** An `EXT-X-MEDIA` rendition; [url] is `null` when its media is in the variant itself. */
internal data class HlsRendition(
  val type: String,
  val groupId: String,
  val url: String?,
  val name: String?,
  val language: String?,
  val default: Boolean,
  val autoselect: Boolean,
)

internal data class HlsPlaylist(
  val plan: MediaPlan?,
  val variants: List<HlsVariant>,
  val renditions: List<HlsRendition> = emptyList(),
) {
  /**
   * The separate audio [variant] plays with: the group's default rendition, else the first it
   * selects automatically, else its first; `null` when its audio is in the variant itself.
   */
  fun audioFor(variant: HlsVariant): HlsRendition? {
    val group = variant.audioGroup ?: return null
    val renditions = renditions.filter { it.type == "AUDIO" && it.groupId == group }
    val chosen = renditions.firstOrNull { it.default }
      ?: renditions.firstOrNull { it.autoselect }
      ?: renditions.firstOrNull()
    return chosen?.takeIf { it.url != null }
  }
}

/** Finite HLS playlists, per RFC 8216: a master playlist, or one media stream of one container. */
internal fun parseHls(text: String, base: String): HlsPlaylist {
  val lines = text.removePrefix("﻿").lineSequence().map(String::trim).toList()
  mediaRequire(lines.firstOrNull() == "#EXTM3U", "Invalid HLS playlist")
  val parts = mutableListOf<MediaPart>()
  val variants = mutableListOf<HlsVariant>()
  val renditions = mutableListOf<HlsRendition>()
  var stream: Map<String, String>? = null
  var nextRange: String? = null
  var previous: MediaPart? = null
  var init: MediaPart? = null
  var duration: Double? = null
  var elapsed = 0.0
  var sequence = 0L
  var segments = 0L
  // Keys of the segments that follow; consecutive EXT-X-KEY tags offer the same segments in
  // several formats, of which only `identity` AES-128 can be read.
  var key: MediaKey? = null
  // Whether each segment's media sequence number is its IV, as the key names none.
  var implicitIv = false
  var drm = false
  var keysSince = -1L
  for (line in lines.drop(1)) {
    when {
      line.startsWith("#EXT-X-STREAM-INF:") -> stream = attributes(line.substringAfter(':'))
      line.startsWith("#EXT-X-MEDIA:") -> {
        val attrs = attributes(line.substringAfter(':'))
        val type = attrs["TYPE"]
        if (type == "AUDIO" || type == "VIDEO") {
          val group = attrs["GROUP-ID"]
          mediaRequire(group != null, "HLS rendition has no group")
          renditions += HlsRendition(
            type = type,
            groupId = group!!,
            url = attrs["URI"]?.let { mediaUrl("hls", base, it) },
            name = attrs["NAME"],
            language = attrs["LANGUAGE"],
            default = attrs["DEFAULT"] == "YES",
            autoselect = attrs["AUTOSELECT"] == "YES",
          )
          mediaRequire(renditions.size <= 100, "Too many HLS renditions")
        }
      }
      line.startsWith("#EXT-X-SESSION-KEY:") -> {
        val method = attributes(line.substringAfter(':'))["METHOD"]
        mediaRequire(method == "NONE" || method == "AES-128",
          "DRM-protected media is not supported")
      }
      line.startsWith("#EXT-X-KEY:") -> {
        if (keysSince != segments) {
          key = null
          drm = false
          keysSince = segments
        }
        val attrs = attributes(line.substringAfter(':'))
        val method = attrs["METHOD"]
        val identity = attrs["KEYFORMAT"] == null || attrs["KEYFORMAT"] == "identity"
        when {
          method == "NONE" -> Unit
          method == "AES-128" && identity -> {
            val uri = attrs["URI"]
            mediaRequire(uri != null, "Missing HLS key URL")
            val iv = attrs["IV"]?.let(::hlsIv)
            key = MediaKey(mediaUrl("hls", base, uri!!), iv ?: ByteArray(16))
            implicitIv = iv == null
          }
          identity -> mediaRequire(false, "Encrypted media other than AES-128 is not supported")
          else -> drm = true
        }
      }
      line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
        mediaRequire(segments == 0L, "Late HLS media sequence")
        val value = line.substringAfter(':').toLongOrNull()
        mediaRequire(value != null && value >= 0, "Invalid HLS media sequence")
        sequence = value!!
      }
      line == "#EXT-X-DISCONTINUITY" || line == "#EXT-X-GAP" ||
        line.startsWith("#EXT-X-PART:") || line == "#EXT-X-I-FRAMES-ONLY" ->
        mediaRequire(false, "This HLS playlist requires media processing")
      line.startsWith("#EXT-X-MAP:") -> {
        val attrs = attributes(line.substringAfter(':'))
        val uri = attrs["URI"]
        mediaRequire(uri != null, "Missing HLS initialization URL")
        mediaRequire(key != null || !drm, "DRM-protected media is not supported")
        val mapKey = key?.let { current ->
          // An encrypted initialization segment has no media sequence number to use as its IV.
          mediaRequire(!implicitIv, "Encrypted HLS initialization needs an IV")
          current
        }
        val part = MediaPart(
          url = mediaUrl("hls", base, uri!!),
          range = attrs["BYTERANGE"]?.let { hlsRange(it, null) },
          init = true,
          key = mapKey,
        )
        mediaRequire(previous == null || init != null, "Late HLS initialization is not supported")
        mediaRequire(init == null || init == part, "Changing HLS initialization is not supported")
        if (init == null) {
          parts += part
          init = part
        }
      }
      line.startsWith("#EXT-X-BYTERANGE:") -> nextRange = line.substringAfter(':')
      line.startsWith("#EXTINF:") -> {
        val seconds = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
        mediaRequire(seconds != null && seconds.isFinite() && seconds >= 0,
          "Invalid HLS segment duration")
        duration = seconds
      }
      line.isEmpty() || line.startsWith('#') -> Unit
      else -> {
        val url = mediaUrl("hls", base, line)
        val variant = stream
        if (variant != null) {
          variants += HlsVariant(url, variant["BANDWIDTH"]?.toLongOrNull() ?: 0, variant)
          mediaRequire(variants.size <= 100, "Too many HLS variants")
          stream = null
        } else {
          val seconds = duration
          mediaRequire(seconds != null, "Missing HLS segment duration")
          mediaRequire(key != null || !drm, "DRM-protected media is not supported")
          val range = nextRange?.let { value ->
            hlsRange(value, previous?.takeIf { it.url == url }?.range?.last?.plus(1))
          }
          val segmentKey = key?.let { current ->
            if (implicitIv) MediaKey(current.url, sequenceIv(sequence + segments))
            else current
          }
          val part = MediaPart(url, range, key = segmentKey, startSeconds = elapsed)
          parts += part
          previous = part
          elapsed += seconds!!
          segments++
          nextRange = null
          duration = null
          mediaRequire(parts.size <= 10_000, "Too many media segments")
        }
      }
    }
  }
  mediaRequire(stream == null && duration == null && nextRange == null, "Incomplete HLS playlist")
  if (variants.isNotEmpty()) {
    mediaRequire(parts.isEmpty(), "Mixed HLS master and media playlist")
    return HlsPlaylist(null, variants, renditions)
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

/** A 128-bit IV written as a hexadecimal sequence of any width up to 32 digits, per RFC 8216. */
private fun hlsIv(value: String): ByteArray {
  mediaRequire(value.startsWith("0x") || value.startsWith("0X"), "Invalid HLS key IV")
  val digits = value.drop(2)
  mediaRequire(digits.length in 1..32 &&
    digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }, "Invalid HLS key IV")
  val hex = digits.padStart(32, '0')
  return ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** The IV of the segment with media sequence number [number]: the number, big-endian. */
private fun sequenceIv(number: Long): ByteArray =
  ByteArray(16) { if (it < 8) 0 else (number ushr (8 * (15 - it))).toByte() }

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
