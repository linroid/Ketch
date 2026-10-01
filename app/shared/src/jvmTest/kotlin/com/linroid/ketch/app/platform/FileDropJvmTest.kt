package com.linroid.ketch.app.platform

import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.net.URI
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDropJvmTest {

  /** A drag that offers [data] in each of its flavors. */
  private class FakeTransferable(private val data: Map<DataFlavor, Any>) : Transferable {
    override fun getTransferDataFlavors() = data.keys.toTypedArray()
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in data
    override fun getTransferData(flavor: DataFlavor): Any =
      data[flavor] ?: throw UnsupportedFlavorException(flavor)
  }

  private val uriList = DataFlavor("text/uri-list;class=java.lang.String")
  private val url = DataFlavor("application/x-java-url;class=java.net.URL")

  @Test
  fun droppedFiles_fileList_skipsDirectoriesAndBoundsReads() = runTest {
    withTempDir { dir ->
      val torrent = File(dir, "a.torrent").apply { writeBytes(byteArrayOf(1, 2, 3)) }
      val folder = File(dir, "folder").apply { mkdir() }

      val dropped = fileList(folder, torrent).droppedFiles()

      assertEquals(listOf("a.torrent"), dropped.map { it.name })
      assertContentEquals(byteArrayOf(1, 2, 3), dropped.single().readBytes(maxBytes = 3))
      assertFailsWith<IllegalArgumentException> { dropped.single().readBytes(maxBytes = 2) }
    }
  }

  @Test
  fun droppedFiles_fileUriList_readsTheFiles() = runTest {
    withTempDir { dir ->
      val torrent = File(dir, "b c.torrent").apply { writeBytes(byteArrayOf(4)) }
      val list = "# from a file manager\r\n${torrent.toURI()}\r\n"

      val dropped = FakeTransferable(mapOf(uriList to list)).droppedFiles()

      assertEquals(listOf("b c.torrent"), dropped.map { it.name })
      assertContentEquals(byteArrayOf(4), dropped.single().readBytes(maxBytes = 1))
    }
  }

  @Test
  fun droppedFiles_textTransfer_isEmpty() {
    assertTrue(StringSelection("magnet:?xt=urn:btih:abc").droppedFiles().isEmpty())
  }

  @Test
  fun droppedText_plainText_returnsIt() {
    val text = "Ubuntu 24.04 https://releases.ubuntu.com/ubuntu.iso"

    assertEquals(text, StringSelection(text).droppedText())
  }

  @Test
  fun droppedText_uriList_returnsItsLinksBeforeThePlainText() {
    val transfer = FakeTransferable(
      mapOf(
        uriList to "# Ubuntu\r\nhttps://a.org/u.iso\r\nhttps://a.org/d.iso\r\n",
        DataFlavor.stringFlavor to "Ubuntu",
      ),
    )

    assertEquals("https://a.org/u.iso\nhttps://a.org/d.iso", transfer.droppedText())
  }

  @Test
  fun droppedText_url_returnsItsAddress() {
    val link = URI("https://a.org/u.iso").toURL()
    val transfer = FakeTransferable(mapOf(url to link, DataFlavor.stringFlavor to "Ubuntu"))

    assertEquals("https://a.org/u.iso", transfer.droppedText())
  }

  @Test
  fun droppedText_filesOnly_isNull() {
    withTempDir { dir ->
      val file = File(dir, "a.torrent").apply { writeBytes(byteArrayOf(1)) }

      assertNull(fileList(file).droppedText())
      assertNull(FakeTransferable(mapOf(uriList to file.toURI().toString())).droppedText())
      assertNull(StringSelection("  ").droppedText())
    }
  }

  @Test
  fun droppedText_unreadableData_isNull() {
    val broken = object : Transferable {
      override fun getTransferDataFlavors() = arrayOf(DataFlavor.stringFlavor)
      override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.stringFlavor
      override fun getTransferData(flavor: DataFlavor): Any =
        throw UnsupportedFlavorException(flavor)
    }

    assertNull(broken.droppedText())
  }

  @Test
  fun isDroppable_filesOrText_isTrue() {
    assertTrue(fileList().isDroppable())
    assertTrue(FakeTransferable(mapOf(uriList to "https://a.org/u.iso")).isDroppable())
    assertTrue(FakeTransferable(mapOf(url to URI("https://a.org/u.iso").toURL())).isDroppable())
    assertTrue(StringSelection("https://a.org/u.iso").isDroppable())
  }

  @Test
  fun isDroppable_imageOnly_isFalse() {
    assertFalse(FakeTransferable(mapOf(DataFlavor.imageFlavor to Any())).isDroppable())
  }

  @Test
  fun isDroppable_rowsDraggedOutOfTheList_isFalse() {
    val keys = DataFlavor("application/x-ketch-tasks;class=java.lang.String")
    val rows = FakeTransferable(
      mapOf(
        uriList to "https://a.org/u.iso\r\n",
        DataFlavor.stringFlavor to "https://a.org/u.iso",
        keys to "ketch-task://local/1",
      ),
    )

    assertFalse(rows.isDroppable())
  }

  private fun fileList(vararg files: File) =
    FakeTransferable(mapOf(DataFlavor.javaFileListFlavor to files.toList()))

  private inline fun withTempDir(block: (File) -> Unit) {
    val dir = createTempDirectory().toFile()
    try {
      block(dir)
    } finally {
      dir.deleteRecursively()
    }
  }
}
