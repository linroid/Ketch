package com.linroid.ketch.app.state

import com.linroid.ketch.app.util.LinkKind
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

/**
 * Takes a URL that iOS opened Ketch with: a `.torrent` file ([offerFile]) or a link Ketch is
 * registered to open, such as a `magnet:` link, which is offered as [IncomingDownload.Links].
 *
 * @return `false` when [url] is neither, such as a `ketch:` link, so the caller can route it.
 */
fun IncomingDownloads.offerUrl(url: NSURL): Boolean {
  if (url.fileURL) {
    offerFile(url)
    return true
  }
  val link = url.absoluteString ?: return false
  if (LinkKind.of(link) == LinkKind.Other) return false
  offerLinks(listOf(link), LinkSource.OpenUrl)
  return true
}

/**
 * Reads a `.torrent` file opened in Ketch from Files, AirDrop or another app. Files opened in
 * place need security-scoped access; copies iOS placed in the app's Inbox are removed once read.
 */
@OptIn(ExperimentalForeignApi::class)
fun IncomingDownloads.offerFile(url: NSURL) {
  val name = url.lastPathComponent ?: "torrent"
  val scoped = url.startAccessingSecurityScopedResource()
  try {
    // Mapped data is paged in on access, so an oversized file is rejected without loading it.
    val data = NSData.dataWithContentsOfURL(url, NSDataReadingMappedIfSafe, null)
    if (data == null) {
      offer(IncomingDownload.Failed(name, "The file could not be read"))
      return
    }
    val length = minOf(data.length.toLong(), MAX_TORRENT_FILE_BYTES + 1L).toInt()
    val bytes = ByteArray(length)
    if (length > 0) {
      bytes.usePinned { memcpy(it.addressOf(0), data.bytes, length.convert()) }
    }
    offerTorrentFile(name, bytes)
  } finally {
    if (scoped) url.stopAccessingSecurityScopedResource()
    removeInboxCopy(url)
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun removeInboxCopy(url: NSURL) {
  val manager = NSFileManager.defaultManager
  val documents = manager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
    .firstOrNull() as? NSURL ?: return
  // Resolving symlinks drops the /private prefix that only some of these URLs carry.
  val inbox = documents.URLByAppendingPathComponent("Inbox")?.URLByResolvingSymlinksInPath?.path
  val parent = url.URLByDeletingLastPathComponent?.URLByResolvingSymlinksInPath?.path
  if (inbox != null && inbox == parent) {
    manager.removeItemAtURL(url, null)
  }
}
