package com.linroid.ketch.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class FolderBookmarksTest {
  private val suite = "ketch.folderBookmarksTest.${NSUUID().UUIDString}"
  private val defaults = NSUserDefaults(suiteName = suite)

  @AfterTest
  fun forgetSuite() {
    NSUserDefaults.standardUserDefaults.removePersistentDomainForName(suite)
  }

  @Test
  fun restore_savedFolder_opensItAgain() {
    val folder = newFolder()

    FolderBookmarks(defaults).save(NSURL.fileURLWithPath(folder, isDirectory = true))

    assertEquals(listOf(folder), FolderBookmarks(defaults).restore())
  }

  @Test
  fun save_sameFolderTwice_keepsOneBookmark() {
    val first = newFolder()
    val second = newFolder()
    val bookmarks = FolderBookmarks(defaults)

    bookmarks.save(NSURL.fileURLWithPath(first, isDirectory = true))
    bookmarks.save(NSURL.fileURLWithPath(second, isDirectory = true))
    bookmarks.save(NSURL.fileURLWithPath(first, isDirectory = true))

    assertEquals(listOf(first, second), bookmarks.restore())
  }

  private fun newFolder(): String {
    val path = NSTemporaryDirectory() + "ketch-bookmark-" + NSUUID().UUIDString
    NSFileManager.defaultManager.createDirectoryAtPath(
      path,
      withIntermediateDirectories = true,
      attributes = null,
      error = null,
    )
    return path
  }
}
