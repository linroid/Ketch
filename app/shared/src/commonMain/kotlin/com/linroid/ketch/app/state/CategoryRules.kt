package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.util.urlHost
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_categories_from_sites
import ketch.app.shared.generated.resources.settings_categories_more
import ketch.app.shared.generated.resources.settings_categories_no_rules
import ketch.app.shared.generated.resources.settings_category_archives
import ketch.app.shared.generated.resources.settings_category_documents
import ketch.app.shared.generated.resources.settings_category_music
import ketch.app.shared.generated.resources.settings_category_pictures
import ketch.app.shared.generated.resources.settings_category_programs
import ketch.app.shared.generated.resources.settings_category_video
import org.jetbrains.compose.resources.StringResource

/**
 * A category the Downloads settings suggest, whose folder is named in the app's language when it
 * is added. Each extension belongs to one of them only.
 */
internal enum class SuggestedCategory(
  val folder: StringResource,
  val extensions: List<String>,
  val mimeTypes: List<String> = emptyList(),
) {
  Video(
    Res.string.settings_category_video,
    listOf(
      "mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "3gp"
    ),
    listOf("video/*"),
  ),
  Music(
    Res.string.settings_category_music,
    listOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff", "ape"),
    listOf("audio/*"),
  ),
  Pictures(
    Res.string.settings_category_pictures,
    listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "bmp", "tif", "tiff", "svg"),
    listOf("image/*"),
  ),
  Documents(
    Res.string.settings_category_documents,
    listOf(
      "pdf", "doc", "docx", "odt", "rtf", "txt", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp",
      "epub", "mobi"
    ),
    listOf("application/pdf"),
  ),
  Archives(
    Res.string.settings_category_archives,
    listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst"),
    listOf("application/zip", "application/x-7z-compressed", "application/vnd.rar"),
  ),
  Programs(
    Res.string.settings_category_programs,
    listOf("exe", "msi", "dmg", "pkg", "deb", "rpm", "apk", "appimage", "iso", "img"),
  );

  /** This suggestion as a category saved in [folder], its name in the app's language. */
  fun toCategory(folder: String): DownloadCategory =
    DownloadCategory(folder = folder, extensions = extensions, mimeTypes = mimeTypes)
}

/**
 * Extensions typed as a list separated by commas or spaces, in lower case without a leading `.`
 * or `*.`, each once.
 */
internal fun parseExtensions(text: String): List<String> =
  ruleWords(text).map { it.lowercase().removePrefix("*").removePrefix(".") }.cleaned()

/** Media types typed as a list separated by commas or spaces, in lower case, each once. */
internal fun parseMimeTypes(text: String): List<String> =
  ruleWords(text).map { it.lowercase() }.cleaned()

/** Whether [type] reads as a media type rule: `type/subtype`, with `*` as the subtype of a kind. */
internal fun isMimeTypeRule(type: String): Boolean {
  val parts = type.split('/')
  return parts.size == 2 && parts.all { it.isNotEmpty() }
}

/**
 * Sites typed as a list separated by commas or spaces, as host names in lower case, each once:
 * a link pasted as a site keeps only its host, and a leading `*.` or `.` is dropped.
 */
internal fun parseHosts(text: String): List<String> = ruleWords(text).map { word ->
  val host = if ("://" in word) urlHost(word).orEmpty() else word.substringBefore('/')
  host.lowercase().removePrefix("*").removePrefix(".").trimEnd('.')
}.cleaned()

/**
 * [name], the folder of a category just added, or `name 2`, `name 3` and so on when another
 * category already has that folder.
 */
internal fun newCategoryFolder(name: String, categories: List<DownloadCategory>): String {
  val taken = categories.map { it.folder.lowercase() }.toSet()
  return generateSequence(1) { it + 1 }
    .map { if (it == 1) name else "$name $it" }
    .first { it.lowercase() !in taken }
}

/** Rules as the fields show them, separated by commas. */
internal fun ruleText(rules: List<String>): String = rules.joinToString(", ")

/**
 * A category folder as typed, with spaces around its folder names and empty names dropped, such
 * as `Video/Clips` for ` Video / Clips/`; `null` when it is no folder inside the download folder.
 */
internal fun normalizeCategoryFolder(text: String): String? {
  val folder = text.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/")
  if (folder.isEmpty() || text.trimStart().startsWith('/') || text.trimStart().startsWith('\\')) {
    return null
  }
  return folder.takeIf { runCatching { DownloadCategory(folder = it) }.isSuccess }
}

/**
 * What a category's row says under its folder: its first extensions and media types, and the
 * sites it is limited to, such as "mp4, mkv, webm +3 · From github.com".
 */
internal fun categorySummary(category: DownloadCategory): UiText {
  val types = category.extensions + category.mimeTypes
  val typesText = if (types.size > SUMMARY_TYPES) {
    listOf(
      verbatim(types.take(SUMMARY_TYPES).joinToString(", ")),
      Res.string.settings_categories_more.text(types.size - SUMMARY_TYPES),
    ).joinText(" ")
  } else {
    types.takeIf { it.isNotEmpty() }?.let { verbatim(it.joinToString(", ")) }
  }
  val sitesText = category.hosts.takeIf { it.isNotEmpty() }
    ?.let { Res.string.settings_categories_from_sites.text(it.joinToString(", ")) }
  val parts = listOfNotNull(typesText, sitesText)
  return if (parts.isEmpty()) Res.string.settings_categories_no_rules.text() else parts.joinText()
}

/** How many extensions and media types a category's summary names before "+N". */
private const val SUMMARY_TYPES = 4

private fun ruleWords(text: String): List<String> =
  text.split(',', ' ', '\t', '\n', ';').map { it.trim() }

private fun List<String>.cleaned(): List<String> = filter { it.isNotEmpty() }.distinct()
