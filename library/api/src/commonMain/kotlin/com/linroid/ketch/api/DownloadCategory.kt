package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * A folder under [DownloadConfig.defaultDirectory] that one kind of download is saved to.
 *
 * A download that does not choose its folder, because [DownloadRequest.destination] is `null` or
 * a bare file name, is saved in the [folder] of the first of [DownloadConfig.categories] it
 * matches, and in the default directory itself when it matches none. A destination that names a
 * folder or a full path is used as it is.
 *
 * A download matches when both its type and its site do:
 * - **Type**: its file name ends with one of [extensions], or the media type its server reports
 *   (`Content-Type`) is one of [mimeTypes]; either is enough. With both lists empty, every type
 *   matches.
 * - **Site**: the host of its URL is one of [hosts] or a subdomain of one. With the list empty,
 *   every site matches.
 *
 * A category whose three lists are all empty matches nothing. Rules ignore case and surrounding
 * whitespace.
 *
 * @property folder where the downloads go, relative to the default directory; `/` or `\`
 *   separates nested folders, as in `Video/Clips`. Each folder name is made safe to create the
 *   way file names from servers are, and missing folders are created when a download starts.
 * @property extensions file name extensions, such as `mp4` or `tar.gz`; a leading `.` or `*.` is
 *   ignored.
 * @property mimeTypes media types, such as `application/pdf`, or every type of a kind, such as
 *   video/\* for any video.
 * @property hosts host names, such as `example.com`, which also covers `cdn.example.com`; a
 *   leading `.` or `*.` is ignored. Links without a host, such as magnet links, match no host.
 */
@Serializable
data class DownloadCategory(
  val folder: String,
  val extensions: List<String> = emptyList(),
  val mimeTypes: List<String> = emptyList(),
  val hosts: List<String> = emptyList(),
) {
  init {
    require(folder.isNotBlank()) { "Category folder must not be blank" }
    require(!folder.isAbsoluteFolder()) {
      "Category folder must be relative to the download folder: $folder"
    }
    require(folder.split('/', '\\').none { it.trim() == ".." }) {
      "Category folder must stay inside the download folder: $folder"
    }
  }

  /**
   * Whether a download named [fileName], of media type [contentType] as its server reports it
   * (`null` when unknown), from [host] (`null` for links without one) belongs in this category.
   */
  fun matches(fileName: String, contentType: String?, host: String?): Boolean {
    val extensionRules = extensions.mapNotNull { it.normalized(prefixes = WILDCARD_PREFIXES) }
    val typeRules = mimeTypes.mapNotNull { it.normalized() }
    val hostRules = hosts.mapNotNull { it.normalized(prefixes = WILDCARD_PREFIXES)?.trimEnd('.') }
    if (extensionRules.isEmpty() && typeRules.isEmpty() && hostRules.isEmpty()) return false

    val name = fileName.trim().lowercase()
    val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    val typeMatches = extensionRules.isEmpty() && typeRules.isEmpty() ||
      extensionRules.any { name.length > it.length + 1 && name.endsWith(".$it") } ||
      type.isNotEmpty() && typeRules.any {
        if (it.endsWith("/*")) type.startsWith(it.dropLast(1)) else type == it
      }
    val site = host?.trim()?.trimEnd('.')?.lowercase().orEmpty()
    val siteMatches = hostRules.isEmpty() ||
      site.isNotEmpty() && hostRules.any { site == it || site.endsWith(".$it") }
    return typeMatches && siteMatches
  }

  private companion object {
    val WILDCARD_PREFIXES = listOf("*.", ".")

    /** This rule in lower case without surrounding whitespace or [prefixes]; `null` if blank. */
    fun String.normalized(prefixes: List<String> = emptyList()): String? {
      val rule = trim().lowercase()
      val bare = prefixes.firstOrNull { rule.startsWith(it) }?.let { rule.removePrefix(it) } ?: rule
      return bare.ifEmpty { null }
    }

    /** Whether this folder is a path from a root, a Windows drive or a URI. */
    fun String.isAbsoluteFolder(): Boolean =
      startsWith('/') || startsWith('\\') || "://" in this ||
        length >= 2 && this[1] == ':' && this[0].isLetter()
  }
}

/**
 * The first of [DownloadConfig.categories] that a download named [fileName], of media type
 * [contentType], from [host] matches, or `null` when it matches none and is saved in the default
 * directory. See [DownloadCategory] for how a download matches.
 */
fun DownloadConfig.categoryFor(
  fileName: String,
  contentType: String?,
  host: String?,
): DownloadCategory? = categories.firstOrNull { it.matches(fileName, contentType, host) }
