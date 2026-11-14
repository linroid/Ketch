package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a download does when a file already has the name Ketch chose for it, in the folder it is
 * saved to ([DownloadConfig.fileConflict], or [DownloadRequest.fileConflict] for one download).
 * Written as `rename` or `overwrite`; unknown values decode as [RENAME].
 *
 * [DownloadConfig.fileConflict] applies to names Ketch chooses: a request with a file
 * [Destination] and no choice of its own names the file it writes, and always replaces it. With
 * [DownloadRequest.fileConflict] set, the named file is treated like a name Ketch chose: [RENAME]
 * saves beside an existing file, [OVERWRITE] replaces it.
 *
 * Sources that write their own files, such as BitTorrent, and Android `content://` folders
 * always behave as [RENAME]. Needs [KetchFeatures.FILE_CONFLICT].
 */
@Serializable(with = FileConflictSerializer::class)
enum class FileConflict {
  /** Keep the existing file and save beside it, as `name (1).ext`. */
  RENAME,

  /**
   * Replace the existing file once the download completes and is verified; until then, and when
   * the download fails or is canceled, the existing file stays as it is. A name another
   * unfinished download saves to is never taken: the download is then saved as with [RENAME],
   * as it is when the name is a folder's or too long for a partial file beside it.
   */
  OVERWRITE,
}

internal object FileConflictSerializer : KSerializer<FileConflict> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.api.FileConflict", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: FileConflict) {
    encoder.encodeString(value.name.lowercase())
  }

  override fun deserialize(decoder: Decoder): FileConflict {
    val name = decoder.decodeString()
    return FileConflict.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
      ?: FileConflict.RENAME
  }
}
