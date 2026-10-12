package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * A folder under [DownloadConfig.defaultDirectory] for downloads that choose none (a `null` or
 * bare-name destination). A download matches when its type and its site both do:
 * - type: its name ends with one of [extensions] or its `Content-Type` is one of [mimeTypes]
 *   (any type when both are empty)
 * - site: its host is one of [hosts] or their subdomains (any site when empty)
 *
 * With all three lists empty it matches nothing. Rules ignore case and surrounding whitespace.
 *
 * @property folder relative to the default directory, nested with `/` or `\`; names are made
 *   safe, and missing folders created
 * @property extensions such as `mp4` or `tar.gz`; a leading `.` or `*.` is ignored
 * @property mimeTypes such as `application/pdf`, or video/\* for a whole kind
 * @property hosts such as `example.com`, covering its subdomains; a leading `.` or `*.` is ignored
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

  /** Whether a download named [fileName], of [contentType], from [host] belongs here. */
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

/** The first of [DownloadConfig.categories] the download matches, or `null`. */
fun DownloadConfig.categoryFor(
  fileName: String,
  contentType: String?,
  host: String?,
): DownloadCategory? = categories.firstOrNull { it.matches(fileName, contentType, host) }
