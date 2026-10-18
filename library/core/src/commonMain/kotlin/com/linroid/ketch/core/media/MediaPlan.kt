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
 * [heights] and [bandwidths]; names that would repeat get a number.
 */
fun mediaVariantNames(heights: List<Int?>, bandwidths: List<Long>): List<String> {
  require(heights.size == bandwidths.size)
  val names = heights.indices.map { index ->
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
  val seen = mutableMapOf<String, Int>()
  return names.map { name ->
    val count = names.count { it == name }
    if (count == 1) return@map name
    val number = (seen[name] ?: 0) + 1
    seen[name] = number
    "$name ($number)"
  }
}
