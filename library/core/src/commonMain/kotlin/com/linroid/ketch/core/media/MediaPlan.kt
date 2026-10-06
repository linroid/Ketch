package com.linroid.ketch.core.media

/** One initialization or media segment, with an optional inclusive byte range. */
data class MediaPart(val url: String, val range: LongRange? = null)

/** Validated parts in output order and the resulting file extension, without a leading dot. */
data class MediaPlan(val parts: List<MediaPart>, val extension: String)

/** Manifest text, its final response URL, and request headers safe for that URL's origin. */
data class MediaManifest(val text: String, val url: String, val headers: Map<String, String>)
