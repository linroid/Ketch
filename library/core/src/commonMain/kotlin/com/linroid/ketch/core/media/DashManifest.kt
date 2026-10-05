package com.linroid.ketch.core.media

import kotlin.math.ceil

/** Static, single-adaptation MP4 DASH. Separate tracks must not silently lose their audio. */
internal fun parseDash(text: String, url: String): MediaPlan {
  val root = manifestXml(text)
  mediaRequire(root.name == "MPD", "Invalid DASH manifest")
  mediaRequire(root.attributes["type"] in listOf(null, "static"), "Live streams are not supported")
  mediaRequire(!root.contains("ContentProtection"), "Encrypted media is not supported")
  val periods = root.children.filter { it.name == "Period" }
  mediaRequire(periods.size == 1, "DASH requires exactly one period")
  val period = periods.single()
  val adaptations = period.children.filter { it.name == "AdaptationSet" }
  mediaRequire(adaptations.size == 1, "Separate DASH tracks require a media merger")
  val adaptation = adaptations.single()
  val representations = adaptation.children.filter { it.name == "Representation" }
  val representation = representations.maxByOrNull {
    it.attributes["bandwidth"]?.toLongOrNull() ?: 0
  }
  mediaRequire(representation != null, "DASH contains no representation")
  val levels = listOf(root, period, adaptation, representation!!)
  var base = url
  for (level in levels) {
    val bases = level.children.filter { it.name == "BaseURL" }
    mediaRequire(bases.size <= 1, "Multiple DASH base URLs are not supported")
    bases.singleOrNull()?.let { base = mediaUrl(base, it.text.toString().trim()) }
  }
  val mime = representation.attributes["mimeType"] ?: adaptation.attributes["mimeType"]
  mediaRequire(mime in setOf("video/mp4", "audio/mp4", "application/mp4"),
    "Only MP4 DASH representations are supported")
  val lists = levels.mapNotNull { it.child("SegmentList") }
  if (lists.isNotEmpty()) {
    val list = lists.last()
    val init = list.child("Initialization")
    mediaRequire(init?.attributes?.get("sourceURL") != null,
      "DASH requires an initialization segment")
    val parts = mutableListOf(MediaPart(mediaUrl(base, init!!.attributes.getValue("sourceURL")),
      dashRange(init.attributes["range"])))
    for (segment in list.children.filter { it.name == "SegmentURL" }) {
      val media = segment.attributes["media"]
      mediaRequire(media != null, "DASH segment has no URL")
      parts += MediaPart(mediaUrl(base, media!!), dashRange(segment.attributes["mediaRange"]))
      mediaRequire(parts.size <= 10_000, "Too many media segments")
    }
    mediaRequire(parts.size > 1, "DASH contains no segments")
    return MediaPlan(parts, "mp4")
  }
  val templates = levels.mapNotNull { it.child("SegmentTemplate") }
  mediaRequire(templates.isNotEmpty(), "DASH requires SegmentList or SegmentTemplate")
  val attrs = templates.fold(emptyMap<String, String>()) { values, element ->
    values + element.attributes
  }
  val initialization = attrs["initialization"]
  val media = attrs["media"]
  mediaRequire(initialization != null && media != null, "Incomplete DASH segment template")
  val scale = attrs["timescale"]?.toLongOrNull() ?: 1
  mediaRequire(scale > 0, "Invalid DASH timescale")
  val seconds = durationSeconds(
    period.attributes["duration"] ?: root.attributes["mediaPresentationDuration"]
  )
  val offset = attrs["presentationTimeOffset"]?.toLongOrNull() ?: 0
  mediaRequire(offset >= 0, "Invalid DASH presentation offset")
  val endTime = seconds?.let { duration ->
    val end = ceil(duration * scale) + offset
    mediaRequire(end.isFinite() && end < Long.MAX_VALUE.toDouble(), "DASH duration is too large")
    end.toLong()
  }
  var number = attrs["startNumber"]?.toLongOrNull() ?: 1
  mediaRequire(number >= 0, "Invalid DASH start number")
  val parts = mutableListOf(MediaPart(mediaUrl(base,
    expand(initialization!!, representation.attributes, number, 0))))
  fun add(time: Long) {
    mediaRequire(parts.size < 10_000 && number < Long.MAX_VALUE, "Too many media segments")
    parts += MediaPart(mediaUrl(base, expand(media!!, representation.attributes, number++, time)))
  }
  val timeline = templates.lastOrNull { it.child("SegmentTimeline") != null }
    ?.child("SegmentTimeline")
  if (timeline != null) {
    val entries = timeline.children.filter { it.name == "S" }
    var time = 0L
    for ((index, entry) in entries.withIndex()) {
      val duration = entry.attributes["d"]?.toLongOrNull()
      mediaRequire(duration != null && duration > 0, "Invalid DASH segment duration")
      entry.attributes["t"]?.let {
        val next = it.toLongOrNull()
        mediaRequire(next != null && next >= time, "Invalid DASH segment timeline")
        time = next!!
      }
      val repeatCount = entry.attributes["r"]?.toLongOrNull() ?: 0
      mediaRequire(repeatCount >= -1, "Invalid DASH repeat count")
      val count = if (repeatCount >= 0) {
        mediaRequire(repeatCount < 10_000, "Too many media segments")
        repeatCount + 1
      } else {
        val bound = entries.getOrNull(index + 1)?.attributes?.get("t")?.toLongOrNull() ?: endTime
        mediaRequire(bound != null && bound > time, "Unbounded DASH timeline")
        ceil((bound!! - time).toDouble() / duration!!).toLong()
      }
      mediaRequire(count <= 10_000 - parts.size && duration!! <= (Long.MAX_VALUE - time) / count,
        "DASH timeline is too large")
      repeat(count.toInt()) { add(time); time += duration!! }
    }
  } else {
    val duration = attrs["duration"]?.toLongOrNull()
    mediaRequire(duration != null && duration > 0 && endTime != null && endTime > offset,
      "DASH template requires a finite duration")
    val count = ceil((endTime!! - offset).toDouble() / duration!!).toLong()
    mediaRequire(count <= 9999 && duration <= Long.MAX_VALUE / count, "Too many media segments")
    repeat(count.toInt()) { index -> add(index * duration) }
  }
  mediaRequire(parts.size > 1, "DASH contains no segments")
  return MediaPlan(parts, "mp4")
}

private fun dashRange(value: String?): LongRange? {
  if (value == null) return null
  val bounds = value.split('-')
  val start = bounds.getOrNull(0)?.toLongOrNull()
  val end = bounds.getOrNull(1)?.toLongOrNull()
  mediaRequire(bounds.size == 2 && start != null && end != null && start >= 0 &&
    end >= start && end < Long.MAX_VALUE, "Invalid DASH byte range")
  return start!!..end!!
}

private fun durationSeconds(value: String?): Double? {
  if (value == null) return null
  val match = Regex("PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?")
    .matchEntire(value)
  mediaRequire(match != null, "Unsupported DASH duration")
  val values = match!!.groupValues.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
  val duration = values[0] * 3600 + values[1] * 60 + values[2]
  mediaRequire(duration.isFinite() && duration > 0, "Invalid DASH duration")
  return duration
}

private fun expand(template: String, attrs: Map<String, String>, number: Long, time: Long): String {
  val escaped = template.replace("$$", "\u0000")
  val expanded = Regex("\\$(RepresentationID|Bandwidth|Number|Time)(?:%0([1-9][0-9]?)d)?\\$")
    .replace(escaped) { match ->
      val value = when (match.groupValues[1]) {
        "RepresentationID" -> attrs["id"]
        "Bandwidth" -> attrs["bandwidth"]
        "Number" -> number.toString()
        else -> time.toString()
      }
      mediaRequire(value != null, "Missing DASH template value")
      val width = match.groupValues[2].toIntOrNull() ?: 0
      mediaRequire(width <= 20, "Invalid DASH number format")
      value!!.padStart(width, '0')
    }
  mediaRequire('$' !in expanded, "Unsupported DASH template variable")
  return expanded.replace('\u0000', '$')
}
