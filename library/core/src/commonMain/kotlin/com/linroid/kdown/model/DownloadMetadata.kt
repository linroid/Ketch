package com.linroid.kdown.model

import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

internal object PathSerializer : KSerializer<Path> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("kotlinx.io.files.Path", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Path) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): Path {
    return Path(decoder.decodeString())
  }
}

@Serializable
data class DownloadMetadata(
  val taskId: String,
  val url: String,
  @Serializable(with = PathSerializer::class)
  val destPath: Path,
  val totalBytes: Long,
  val acceptRanges: Boolean,
  val etag: String?,
  val lastModified: String?,
  val segments: List<Segment>,
  val createdAt: Long,
  val updatedAt: Long
) {
  val downloadedBytes: Long
    get() = segments.sumOf { it.downloadedBytes }

  val isComplete: Boolean
    get() = segments.all { it.isComplete }

  fun withUpdatedSegment(segmentIndex: Int, downloadedBytes: Long, currentTime: Long): DownloadMetadata {
    val updatedSegments = segments.mapIndexed { index, segment ->
      if (index == segmentIndex) segment.copy(downloadedBytes = downloadedBytes)
      else segment
    }
    return copy(
      segments = updatedSegments,
      updatedAt = currentTime
    )
  }
}
