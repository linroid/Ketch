package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ChecksumAlgorithm
import com.linroid.ketch.core.digest.newDigest
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.yield

/** Bytes read from a file per step while hashing it. */
private const val READ_SIZE = 256 * 1024

/**
 * The [algorithm] digest of the first [size] bytes of the file [file] opened, or of fewer when
 * the file ends sooner. Reads it in steps, yielding between them, so cancellation stops it
 * and other work on the same dispatcher keeps running.
 *
 * @throws UnsupportedOperationException when [file] cannot be read
 */
internal suspend fun fileDigest(
  file: FileAccessor,
  algorithm: ChecksumAlgorithm,
  size: Long,
): ByteArray {
  val digest = algorithm.newDigest()
  val buffer = ByteArray(READ_SIZE)
  var offset = 0L
  while (offset < size) {
    yield()
    val count = minOf(buffer.size.toLong(), size - offset).toInt()
    val read = file.readAt(offset, buffer, 0, count)
    if (read <= 0) break
    digest.update(buffer, 0, read)
    offset += read
  }
  return digest.digest()
}
