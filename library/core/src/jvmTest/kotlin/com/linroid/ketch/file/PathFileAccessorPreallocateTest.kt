package com.linroid.ketch.file

import com.linroid.ketch.core.file.PathFileAccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for [PathFileAccessor.preallocate] — particularly
 * around files larger than 2 GB. okio 3.16.4's `JvmFileHandle.protectedResize`
 * allocates a `ByteArray(delta.toInt())` when growing a file, which throws
 * `NegativeArraySizeException` once `delta` exceeds `Int.MAX_VALUE`. We
 * extend via a single sparse write instead.
 *
 * Files are created sparse, so the disk footprint is one filesystem block,
 * not the logical size.
 */
class PathFileAccessorPreallocateTest {

  private fun tempPath(): String {
    val dir = Files.createTempDirectory("ketch-preallocate-test")
    return dir.resolve("file.bin").absolutePathString()
  }

  @Test
  fun preallocate_smallFile() = runTest {
    val path = tempPath()
    val accessor = PathFileAccessor(path, Dispatchers.IO)
    try {
      accessor.preallocate(1024L)
      assertEquals(1024L, java.nio.file.Path.of(path).fileSize())
    } finally {
      accessor.close()
      java.nio.file.Path.of(path).deleteIfExists()
    }
  }

  @Test
  fun preallocate_above2GB_doesNotOverflow() = runTest {
    val path = tempPath()
    val size = 2_918_598_656L  // ~2.72 GB, matches the reported failure
    val accessor = PathFileAccessor(path, Dispatchers.IO)
    try {
      accessor.preallocate(size)
      assertEquals(size, java.nio.file.Path.of(path).fileSize())
    } finally {
      accessor.close()
      java.nio.file.Path.of(path).deleteIfExists()
    }
  }

  @Test
  fun preallocate_zero_isNoOp() = runTest {
    val path = tempPath()
    val accessor = PathFileAccessor(path, Dispatchers.IO)
    try {
      accessor.preallocate(0L)
      // No file written; size() opens the handle lazily — verify the
      // file system path has no file or has size zero.
      val p = java.nio.file.Path.of(path)
      if (java.nio.file.Files.exists(p)) {
        assertEquals(0L, p.fileSize())
      }
    } finally {
      accessor.close()
      java.nio.file.Path.of(path).deleteIfExists()
    }
  }
}
