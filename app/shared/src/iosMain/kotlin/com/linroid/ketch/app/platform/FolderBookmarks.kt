package com.linroid.ketch.app.platform

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults

/**
 * Keeps folders picked outside Ketch's container writable after a restart. The picker grants
 * access only until the app quits, so each picked folder is saved as a bookmark and opened again,
 * with its access, when the app starts.
 *
 * @param defaults where the bookmarks are kept.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FolderBookmarks(
  private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) {
  private val log = KetchLogger("FolderBookmarks")

  /** Saves a bookmark to [url], a folder the app can access now, under its path. */
  fun save(url: NSURL) {
    val path = url.path ?: return
    val bookmark = url.bookmark()
    if (bookmark == null) {
      log.w { "Couldn't keep access to $path after a restart" }
      return
    }
    val others = entries().filterNot { it.first == path }.take(MAX_FOLDERS - 1)
    write(listOf(path to bookmark) + others)
  }

  /**
   * Opens every saved folder again and keeps it accessible while the app runs. Folders that are
   * gone are forgotten.
   *
   * @return the paths of the folders that are accessible again, as they were saved.
   */
  fun restore(): List<String> = memScoped {
    val stale = alloc<BooleanVar>()
    val kept = mutableListOf<Pair<String, NSData>>()
    for ((path, bookmark) in entries()) {
      stale.value = false
      val url = NSURL.URLByResolvingBookmarkData(
        bookmark,
        options = 0u,
        relativeToURL = null,
        bookmarkDataIsStale = stale.ptr,
        error = null,
      )
      if (url == null) {
        log.d { "Forgot the folder $path, which is gone" }
        continue
      }
      url.startAccessingSecurityScopedResource()
      val renewed = if (stale.value) url.bookmark() else null
      kept += path to (renewed ?: bookmark)
    }
    write(kept)
    kept.map { it.first }
  }

  private fun NSURL.bookmark(): NSData? = bookmarkDataWithOptions(
    0u,
    includingResourceValuesForKeys = null,
    relativeToURL = null,
    error = null,
  )

  private fun entries(): List<Pair<String, NSData>> =
    defaults.arrayForKey(KEY).orEmpty().mapNotNull { entry ->
      val map = entry as? Map<*, *> ?: return@mapNotNull null
      val path = map[PATH] as? String ?: return@mapNotNull null
      val bookmark = map[BOOKMARK] as? NSData ?: return@mapNotNull null
      path to bookmark
    }

  private fun write(entries: List<Pair<String, NSData>>) {
    val stored = entries.map { (path, bookmark) -> mapOf(PATH to path, BOOKMARK to bookmark) }
    defaults.setObject(stored, forKey = KEY)
  }

  private companion object {
    const val KEY = "ketch.folderBookmarks"
    const val PATH = "path"
    const val BOOKMARK = "bookmark"

    /** Folders kept at most; the oldest picked goes first. */
    const val MAX_FOLDERS = 20
  }
}
