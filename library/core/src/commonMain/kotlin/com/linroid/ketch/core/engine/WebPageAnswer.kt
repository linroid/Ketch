package com.linroid.ketch.core.engine

import com.linroid.ketch.core.file.DefaultFileNameResolver

/**
 * Tells a web page a server sends in place of the file a link names, as a file host does for a
 * link that expired or needs the cookies of a signed-in browser: it redirects to, or answers
 * with, its landing or sign-in page, which would otherwise be saved under the file's name and
 * reported as a completed download.
 *
 * Two steps, so a server that only labels a file wrongly is never refused: the headers must
 * [suspect] it, and the content must then start as a page does ([isMarkup]).
 */
internal object WebPageAnswer {
  /** What [KetchError.SourceError.detail][com.linroid.ketch.api.KetchError.SourceError] says. */
  const val DETAIL =
    "The server sent a web page instead of the file. " +
      "The link may have expired or need you to be signed in."

  /** How much of the content [isMarkup] reads. */
  const val SNIFF_BYTES = 1024

  /**
   * Whether [info], the answer to [url], looks like a web page where a file was asked for: the
   * link is named like a file no page is served as ([FILE_EXTENSIONS]), while the answer is
   * HTML and names no download (`Content-Disposition`). A redirect is not required: an expired
   * link is often answered with a page at its own address.
   */
  fun suspect(url: String, info: ServerInfo): Boolean {
    if (!info.contentDisposition.isNullOrBlank()) return false
    val mediaType = info.contentType?.substringBefore(';')?.trim()?.lowercase()
    if (mediaType !in PAGE_TYPES) return false
    val name = DefaultFileNameResolver.fromUrl(url) ?: return false
    return name.substringAfterLast('.', "").lowercase() in FILE_EXTENSIONS
  }

  /** Whether content starting with [head] is an HTML document rather than a file. */
  fun isMarkup(head: ByteArray): Boolean {
    val text = head.decodeToString(endIndex = minOf(head.size, SNIFF_BYTES))
      .trimStart('﻿', ' ', '\t', '\r', '\n')
      .lowercase()
    return text.startsWith('<') && MARKERS.any { it in text }
  }

  private val PAGE_TYPES = setOf("text/html", "application/xhtml+xml")

  private val MARKERS =
    listOf("<!doctype html", "<html", "<head", "<body", "<script", "<title", "<meta")

  /**
   * Extensions of files no server means to answer with a page: none of these formats starts
   * with markup. Names that pages have too, such as `Node.js` or `release-1.2`, are left out.
   */
  private val FILE_EXTENSIONS = setOf(
    // Video
    "mkv", "mp4", "m4v", "mov", "avi", "wmv", "flv", "webm", "mpg", "mpeg", "m2ts", "3gp", "ogv",
    // Audio
    "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma",
    // Archives
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "cab",
    // Installers and disk images
    "iso", "img", "dmg", "pkg", "exe", "msi", "apk", "aab", "ipa", "deb", "rpm", "appimage",
    "jar",
    // Documents
    "pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    // Images
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "heic", "psd",
    "torrent",
  )
}
