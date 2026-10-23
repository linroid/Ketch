package com.linroid.ketch.file

import com.linroid.ketch.core.file.PathFileAccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class PathFileAccessorDirectoriesTest {
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  @Test
  fun writeAt_pathThroughMissingFoldersAndParentSegments_createsThemAsMkdirDoes() = runTest {
    val root = Files.createTempDirectory("ketch-directories-test")
    // As `ketch <url> ./sub/../up/` asks, from a folder holding neither.
    val path = root.resolve("sub/../up/./deeper/file.bin").toString()
    val accessor = PathFileAccessor(path, Dispatchers.IO)
    try {
      accessor.writeAt(0, byteArrayOf(1, 2, 3))
      accessor.flush()

      assertContentEquals(byteArrayOf(1, 2, 3), root.resolve("up/deeper/file.bin").readBytes())
      assertTrue(root.resolve("sub").exists())
    } finally {
      accessor.close()
      root.deleteRecursively()
    }
  }
}
