package com.linroid.ketch.core.media

import com.linroid.ketch.core.digest.sha256Digest

/**
 * One initialization or media segment, with an optional inclusive byte range.
 *
 * @property track the track the part belongs to in a [MediaPlan] of several, 0 for the first
 * @property init whether it is the track's initialization segment (an MP4 `ftyp` and `moov`)
 * @property key the AES-128 key its bytes are encrypted with, if any
 * @property startSeconds when the part starts playing, if the manifest says; [mergeTracks] uses
 *   it to interleave the tracks
 */
data class MediaPart(
  val url: String,
  val range: LongRange? = null,
  val track: Int = 0,
  val init: Boolean = false,
  val key: MediaKey? = null,
  val startSeconds: Double? = null,
)

/**
 * AES-128 in CBC mode with PKCS#7 padding over a whole part, as HLS `METHOD=AES-128` encrypts
 * segments: the 16-byte key at [url] and the 16-byte [iv].
 */
class MediaKey(val url: String, val iv: ByteArray) {
  init {
    require(iv.size == 16) { "AES-128 needs a 16-byte IV" }
  }

  override fun equals(other: Any?): Boolean =
    other is MediaKey && other.url == url && other.iv.contentEquals(iv)

  override fun hashCode(): Int = 31 * url.hashCode() + iv.contentHashCode()

  override fun toString(): String = "MediaKey"
}

/**
 * Validated parts in output order and the resulting file extension, without a leading dot.
 *
 * @property tracks how many tracks [parts] hold: 1 for a single stream, which is written as it
 *   is, or 2 for separate video and audio fragmented MP4 tracks, which are merged into one MP4
 *   (see [mergeTracks])
 */
data class MediaPlan(val parts: List<MediaPart>, val extension: String, val tracks: Int = 1)

/**
 * One choice of a stream offering several, such as an HLS variant or a DASH representation.
 *
 * @property id unique within the manifest and kept when it is refreshed ([mediaVariantIds])
 * @property name what users see, such as `1080p · 5.1 Mbps`
 * @property bandwidth bits per second the manifest declares
 * @property metadata values for [com.linroid.ketch.api.SourceFile.metadata], such as
 *   `resolution` and `codecs`
 */
data class MediaVariant(
  val id: String,
  val name: String,
  val bandwidth: Long,
  val metadata: Map<String, String> = emptyMap(),
)

/** Manifest text, its final response URL, and request headers safe for that URL's origin. */
data class MediaManifest(val text: String, val url: String, val headers: Map<String, String>)

/**
 * Merges a [video] and an [audio] plan, both fragmented MP4 that start with their initialization
 * segment, into one plan of two tracks: both initialization segments, then the media parts
 * interleaved by [MediaPart.startSeconds] (or by their place in each track, when a manifest gives
 * no times), so a player finds audio near the video it plays with.
 *
 * @throws IllegalArgumentException when either is not initialized fragmented MP4
 */
fun mergeTracks(video: MediaPlan, audio: MediaPlan): MediaPlan {
  require(video.tracks == 1 && audio.tracks == 1) { "Only single tracks can be merged" }
  val videoInit = video.parts.firstOrNull()?.takeIf { it.init }
  val audioInit = audio.parts.firstOrNull()?.takeIf { it.init }
  require(videoInit != null && audioInit != null && video.parts.drop(1).none { it.init } &&
    audio.parts.drop(1).none { it.init }) { "Separate tracks must be fragmented MP4" }
  val videoMedia = video.parts.drop(1)
  val audioMedia = audio.parts.drop(1)
  val timed = (videoMedia + audioMedia).all { it.startSeconds != null }
  fun keys(parts: List<MediaPart>, track: Int) = parts.mapIndexed { index, part ->
    val at = if (timed) part.startSeconds!! else index.toDouble() / parts.size
    Triple(at, track, part.copy(track = track))
  }
  val media = (keys(videoMedia, 0) + keys(audioMedia, 1))
    .sortedWith(compareBy<Triple<Double, Int, MediaPart>>({ it.first }, { it.second }))
    .map { it.third }
  return MediaPlan(
    listOf(videoInit!!.copy(track = 0), audioInit!!.copy(track = 1)) + media,
    extension = "mp4",
    tracks = 2,
  )
}

/**
 * IDs for variants from what identifies each in its manifest ([keys], such as its URL path and
 * attributes, never a signed query), so a refreshed manifest that reorders or adds variants keeps
 * each one's ID. Keys that repeat get a number.
 */
fun mediaVariantIds(keys: List<String>): List<String> {
  val seen = mutableMapOf<String, Int>()
  return keys.map { key ->
    val id = sha256Digest(key.encodeToByteArray()).take(6)
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    val count = (seen[id] ?: 0) + 1
    seen[id] = count
    if (count == 1) id else "$id-$count"
  }
}

/**
 * Names variants as users pick them, such as `1080p · 5.1 Mbps`, from each one's picture
 * [heights] and [bandwidths]. Variants whose names would repeat are told apart by the codecs
 * that differ among them ([codecs], as manifests list them: `540p · 2.2 Mbps · E-AC-3`), else
 * get a number.
 */
fun mediaVariantNames(
  heights: List<Int?>,
  bandwidths: List<Long>,
  codecs: List<String?> = List(heights.size) { null },
): List<String> {
  require(heights.size == bandwidths.size && heights.size == codecs.size)
  val plain = heights.indices.map { index ->
    val height = heights[index]?.takeIf { it > 0 }?.let { "${it}p" }
    val bandwidth = bandwidths[index]
    val rate = when {
      bandwidth >= 1_000_000 ->
        ((bandwidth + 50_000) / 100_000).let { "${it / 10}.${it % 10} Mbps" }
      bandwidth > 0 -> "${(bandwidth + 500) / 1000} kbps"
      else -> null
    }
    listOfNotNull(height, rate).joinToString(" · ").ifEmpty { "Variant ${index + 1}" }
  }
  val formats = codecs.map(::codecNames)
  val names = plain.mapIndexed { index, name ->
    val alike = plain.indices.filter { plain[it] == name }
    if (alike.size == 1) return@mapIndexed name
    val shared = alike.map { formats[it].toSet() }.reduce { all, next -> all intersect next }
    val distinct = formats[index].filterNot { it in shared }
    if (distinct.isEmpty()) name else "$name · ${distinct.joinToString(" + ")}"
  }
  val seen = mutableMapOf<String, Int>()
  return names.map { name ->
    val count = names.count { it == name }
    if (count == 1) return@map name
    val number = (seen[name] ?: 0) + 1
    seen[name] = number
    "$name ($number)"
  }
}

/** The formats a `CODECS` value lists, as users know them: `avc1.64001f,ec-3` is H.264, E-AC-3. */
private fun codecNames(codecs: String?): List<String> =
  codecs.orEmpty().split(',').mapNotNull { codec ->
    val entry = codec.trim().lowercase()
    val tag = entry.substringBefore('.')
    when {
      tag.isEmpty() -> null
      tag == "avc1" || tag == "avc3" -> "H.264"
      tag == "hvc1" || tag == "hev1" -> "HEVC"
      tag == "dvh1" || tag == "dvhe" -> "Dolby Vision"
      tag == "av01" -> "AV1"
      tag == "vp09" || tag == "vp9" -> "VP9"
      tag == "vp8" -> "VP8"
      tag == "mp4a" -> mp4aName(entry)
      tag == "ac-3" -> "AC-3"
      tag == "ec-3" -> "E-AC-3"
      tag == "ac-4" -> "AC-4"
      tag == "opus" -> "Opus"
      tag in setOf("dtsc", "dtse", "dtsh", "dtsl", "dtsx") -> "DTS"
      tag == "flac" || tag == "fla" -> "FLAC"
      tag == "alac" -> "ALAC"
      else -> tag.uppercase()
    }
  }.distinct()

/**
 * The format an `mp4a.<OTI>[.<AOT>]` codec names, by its MP4 object type indication
 * (mp4ra.org): MPEG-4 audio (`40`) is AAC unless its audio object type says MP3 (`34`). Types
 * this does not name keep the manifest's spelling.
 */
private fun mp4aName(entry: String): String {
  val parts = entry.split('.')
  val objectType = parts.getOrNull(1)?.toIntOrNull(16) ?: return "AAC"
  return when (objectType) {
    0x40 -> if (parts.getOrNull(2) == "34") "MP3" else "AAC"
    0x66, 0x67, 0x68 -> "AAC"
    0x69, 0x6b -> "MP3"
    0xa5 -> "AC-3"
    0xa6 -> "E-AC-3"
    0xa9, 0xaa, 0xab, 0xac -> "DTS"
    0xad -> "Opus"
    0xae -> "AC-4"
    else -> entry
  }
}
