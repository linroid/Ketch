package com.linroid.ketch.app.platform

import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileDropJvmTest {

  private class FileListTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) =
      flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any = files
  }

  @Test
  fun droppedFiles_fileList_skipsDirectoriesAndBoundsReads() = runTest {
    val dir = createTempDirectory().toFile()
    try {
      val torrent = File(dir, "a.torrent").apply { writeBytes(byteArrayOf(1, 2, 3)) }
      val folder = File(dir, "folder").apply { mkdir() }

      val dropped = FileListTransferable(listOf(folder, torrent)).droppedFiles()

      assertEquals(listOf("a.torrent"), dropped.map { it.name })
      assertContentEquals(byteArrayOf(1, 2, 3), dropped.single().readBytes(maxBytes = 3))
      assertFailsWith<IllegalArgumentException> { dropped.single().readBytes(maxBytes = 2) }
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun droppedFiles_textTransfer_isEmpty() {
    assertTrue(StringSelection("magnet:?xt=urn:btih:abc").droppedFiles().isEmpty())
  }
}
