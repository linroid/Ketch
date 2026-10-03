package com.linroid.ketch.app.util

/** Most links one pattern such as `part[001-500].rar` expands to; the rest are left out. */
const val MAX_EXPANDED_LINKS: Int = 200

/** Something found in text typed or pasted into the add sheet. */
sealed interface IntakeItem {
  /**
   * A link to download.
   *
   * @property url the link, with `https://` added when the scheme was missing and a bare info
   *   hash written as a magnet link.
   * @property headers request headers that came with the link, such as a cURL command's cookies.
   */
  data class Link(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
  ) : IntakeItem {
    /** What kind of source downloads [url]. */
    val kind: LinkKind get() = LinkKind.of(url)
  }

  /**
   * A link with ranges, such as `part[01-12].rar` or `img{a,b,c}.png`, that stands for every
   * link it expands to. Ranges are numbers (zero-padded like the first one), single letters, or
   * comma-separated choices; several in one link expand in order, the last one fastest.
   *
   * @property pattern the link as written, with its ranges.
   * @property urls the links the pattern expands to, at most [MAX_EXPANDED_LINKS].
   * @property truncated whether the pattern expands to more links than [urls] holds.
   * @property headers request headers that came with the pattern, sent with every link.
   */
  data class Range(
    val pattern: String,
    val urls: List<String>,
    val truncated: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
  ) : IntakeItem {
    /** "Expanded to the first 200 links" when the pattern was cut short, else `null`. */
    val warning: String?
      get() = if (truncated) "Expanded to the first ${urls.size} links" else null

    /** The links the pattern expands to. */
    val links: List<Link> get() = urls.map { Link(it, headers) }
  }

  /**
   * Text that holds no link, offered to Discover as a search.
   *
   * @property query the text, trimmed, with each run of whitespace made one space.
   */
  data class Discover(val query: String) : IntakeItem
}

/** What kind of source downloads a link. */
enum class LinkKind {
  /** An HTTP or HTTPS link. */
  Http,

  /** An FTP or FTPS link. */
  Ftp,

  /** A magnet link. */
  Magnet,

  /** An HTTP or HTTPS link to a `.torrent` file. */
  TorrentFile,

  /** A link of another kind, which the device that resolves it may not support. */
  Other;

  /** Whether a torrent downloads it: a magnet or a `.torrent` file link. */
  val isTorrent: Boolean get() = this == Magnet || this == TorrentFile

  companion object {
    /** The kind of [url], judged by its scheme and, for HTTP, its path. */
    fun of(url: String): LinkKind {
      val lower = url.trim().lowercase()
      return when {
        lower.startsWith("magnet:") -> Magnet
        lower.startsWith("http://") || lower.startsWith("https://") ->
          if (lower.substringBefore('#').substringBefore('?').endsWith(".torrent")) {
            TorrentFile
          } else {
            Http
          }
        lower.startsWith("ftp://") || lower.startsWith("ftps://") -> Ftp
        else -> Other
      }
    }
  }
}

/** Every link in these items, ranges expanded; Discover searches are left out. */
fun List<IntakeItem>.links(): List<IntakeItem.Link> = flatMap { item ->
  when (item) {
    is IntakeItem.Link -> listOf(item)
    is IntakeItem.Range -> item.links
    is IntakeItem.Discover -> emptyList()
  }
}

/** Finds what to download in text typed, pasted, shared or dropped into the app. */
object LinkParser {
  /**
   * Finds the links in [text], in the order they appear:
   * - http(s), ftp(s) and magnet links anywhere, one per line, in prose or in HTML (`&amp;` is
   *   decoded, and punctuation that ends a sentence or closes a bracket is left out);
   * - a bare 40-hex or 32-base32 info hash, as a magnet link;
   * - `host.tld/path` without a scheme, with `https://` added;
   * - a line that is a single link of another scheme, which the target device then reports as
   *   unsupported;
   * - `curl` commands, read by [CurlParser] with their headers; one that cannot be read is
   *   searched for links like other text;
   * - ranges such as `part[01-12].rar` and `img{a,b}.png`, as [IntakeItem.Range].
   *
   * A link that repeats an earlier one (see [duplicateKeys]) is left out. Text without any link
   * becomes a single [IntakeItem.Discover] search.
   *
   * @param fileName name of the file [text] was read from, such as a dropped `.txt` or `.csv`
   *   list. Each cell of a `.csv` file is read on its own, only the values of a `.webloc`
   *   property list are read (not its DTD link), and a file without links yields nothing rather
   *   than a search.
   */
  fun parseIntake(text: String, fileName: String? = null): List<IntakeItem> {
    val csv = fileName?.endsWith(".csv", ignoreCase = true) == true
    val read = if (fileName?.endsWith(".webloc", ignoreCase = true) == true) {
      PLIST_STRING.findAll(text).joinToString("\n") { it.groupValues[1] }
    } else {
      text
    }
    val lines = read.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val collector = Collector()
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      when {
        csv -> csvCells(line).forEach(collector::addLinksIn)
        CurlParser.isCurl(line) -> {
          var command = line
          val last = minOf(lines.lastIndex, i + MAX_CURL_LINES - 1)
          while (i < last && CurlParser.isIncomplete(command)) {
            i++
            command += "\n" + lines[i]
          }
          val curl = CurlParser.parse(command)
          if (curl != null) collector.addCurl(curl) else collector.addLinksIn(command)
        }
        else -> collector.addLinksIn(line)
      }
      i++
    }
    if (collector.items.isNotEmpty() || fileName != null) return collector.items
    val query = text.trim().replace(WHITESPACE, " ")
    return if (query.isEmpty()) emptyList() else listOf(IntakeItem.Discover(query))
  }

  /**
   * Whether a file named [fileName] is a list of links for [parseIntake]: a `.txt` or `.csv`
   * file, or a `.url` or `.webloc` shortcut.
   */
  fun isLinkList(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in LINK_LIST_EXTENSIONS

  /**
   * The text the add sheet takes for a list of links read from [fileName]: the links of a `.csv`
   * file or a `.webloc` shortcut one per line, which the sheet could not tell apart from their
   * other text, and any other list as it is, so its `curl` commands keep their headers.
   */
  fun listText(text: String, fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
      "csv", "webloc" -> parseIntake(text, fileName).links().joinToString("\n") { it.url }
      else -> text
    }

  /**
   * The link to add at once, without the add sheet, when [text] holds exactly one HTTP(S) or
   * FTP(S) link that is not a `.torrent` file. Several links, a range, a magnet, a `.torrent`, a
   * cURL command or text without a link return `null`, so the sheet opens.
   */
  fun quickAddLink(text: String): IntakeItem.Link? {
    if (text.lines().any(CurlParser::isCurl)) return null
    val link = parseIntake(text).singleOrNull() as? IntakeItem.Link ?: return null
    return link.takeIf { it.kind == LinkKind.Http || it.kind == LinkKind.Ftp }
  }

  /**
   * [token] as a link: one with a supported scheme and a host, or `host.tld/path` with
   * `https://` added. With [pathOptional], a bare `host.tld` also counts, as it does for curl.
   */
  internal fun linkFromToken(token: String, pathOptional: Boolean = false): String? {
    if (token.isEmpty() || token.any { it.isWhitespace() }) return null
    if (token.startsWith("magnet:?", ignoreCase = true)) return token.takeIf { it.length > 8 }
    if (SUPPORTED_SCHEME.containsMatchIn(token)) return token.takeIf(::hasHost)
    val schemeless = if (pathOptional) SCHEMELESS_HOST else SCHEMELESS_LINK
    return if (schemeless.matches(token)) "https://$token" else null
  }

  /** Gathers items in order, leaving out links that repeat an earlier one. */
  private class Collector {
    val items = mutableListOf<IntakeItem>()
    private val seen = mutableSetOf<String>()

    fun addCurl(command: CurlCommand) {
      command.urls.forEach { add(it, command.headers, expandRanges = !command.globOff) }
    }

    fun addLinksIn(segment: String) {
      findLinks(segment).forEach { add(it, emptyMap(), expandRanges = true) }
    }

    private fun add(url: String, headers: Map<String, String>, expandRanges: Boolean) {
      val range = if (expandRanges && LinkKind.of(url) != LinkKind.Magnet) expand(url) else null
      if (range != null) {
        if (seen.add("range:" + range.pattern)) items += range.copy(headers = headers)
        return
      }
      val keys = duplicateKeys(url)
      if (keys.any { it in seen }) return
      seen += keys
      items += IntakeItem.Link(url, headers)
    }
  }
}

// Most lines one curl command may span, so a quote left open does not swallow a long list.
private const val MAX_CURL_LINES = 200
private val LINK_LIST_EXTENSIONS = setOf("txt", "csv", "url", "webloc")

private val WHITESPACE = Regex("\\s+")
private val PLIST_STRING = Regex("<string>([^<]*)</string>")
private val TOKEN = Regex("\\S+")
private val SCHEME_URL = Regex(
  "(?:https?|ftps?)://[^\\s\"'<>`]+|magnet:\\?[^\\s\"'<>`]+",
  RegexOption.IGNORE_CASE,
)
private val SUPPORTED_SCHEME = Regex("^(?:https?|ftps?)://", RegexOption.IGNORE_CASE)
private val ANY_SCHEME_URL = Regex("^[a-z][a-z0-9+.-]*://\\S+$", RegexOption.IGNORE_CASE)
private val HEX_INFO_HASH = Regex("^[0-9a-f]{40}$", RegexOption.IGNORE_CASE)
private val BASE32_INFO_HASH = Regex("^[a-z2-7]{32}$", RegexOption.IGNORE_CASE)
private const val HOST =
  "(?:(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}|\\d{1,3}(?:\\.\\d{1,3}){3})" +
    "(?::\\d{1,5})?"
private val SCHEMELESS_LINK = Regex("^$HOST/\\S*$", RegexOption.IGNORE_CASE)
private val SCHEMELESS_HOST = Regex("^$HOST(?:/\\S*)?$", RegexOption.IGNORE_CASE)

private val LINK_STARTS = listOf("http://", "https://", "ftp://", "ftps://", "magnet:?")
private val AUTHORITY_ENDS = charArrayOf('/', '?', '#')

// HTML escapes that cannot be part of a link, so one ends the link before it.
private val HTML_STOPS = listOf("&quot;", "&#34;", "&lt;", "&gt;", "&#39;", "&apos;")
private val HTML_AMPERSANDS = listOf("&amp;", "&#38;", "&#x26;")

// Ends of sentences, Markdown emphasis and CJK punctuation are never the last character of a
// link that someone typed.
private const val TRAILING_PUNCTUATION = ".,;:!?*\"'>…。，、；：！？）」』】"
private const val LEADING_WRAPPERS = "\"'(<[{*（「『【"

/** Links in [segment], in the order they appear. */
private fun findLinks(segment: String): List<String> {
  val found = mutableListOf<Pair<Int, String>>()
  val taken = mutableListOf<IntRange>()
  for (match in SCHEME_URL.findAll(segment)) {
    // The ftp:// of sftp:// belongs to another scheme.
    if (segment.getOrNull(match.range.first - 1)?.isSchemeChar() == true) continue
    taken += match.range
    for ((offset, part) in splitJoined(cutAtHtmlEscape(match.value))) {
      val link = trimTrailing(decodeHtmlAmpersands(part))
      if (LinkParser.linkFromToken(link) != null) found += (match.range.first + offset) to link
    }
  }
  // Both are in order, so one pass finds the words that overlap a link found above.
  var next = 0
  for (match in TOKEN.findAll(segment)) {
    while (next < taken.size && taken[next].last < match.range.first) next++
    if (next < taken.size && taken[next].first <= match.range.last) continue
    val word = trimTrailing(match.value.trimStart { it in LEADING_WRAPPERS })
    val link = when {
      HEX_INFO_HASH.matches(word) -> "magnet:?xt=urn:btih:" + word.lowercase()
      BASE32_INFO_HASH.matches(word) -> "magnet:?xt=urn:btih:" + word.uppercase()
      SCHEMELESS_LINK.matches(word) -> "https://$word"
      segment.trim() == match.value && ANY_SCHEME_URL.matches(word) -> word
      else -> null
    }
    if (link != null) found += match.range.first to link
  }
  return found.sortedBy { it.first }.map { it.second }
}

/**
 * Splits links joined by a comma or semicolon, or by the `](` of a Markdown link whose text is
 * a link too, with each part's offset in [value].
 */
private fun splitJoined(value: String): List<Pair<Int, String>> {
  val parts = mutableListOf<Pair<Int, String>>()
  var start = 0
  for (i in 1 until value.length) {
    val next = when {
      value[i] == ',' || value[i] == ';' -> i + 1
      value.startsWith("](", startIndex = i) -> i + 2
      else -> continue
    }
    if (LINK_STARTS.any { value.startsWith(it, startIndex = next, ignoreCase = true) }) {
      parts += start to value.substring(start, i)
      start = next
    }
  }
  parts += start to value.substring(start)
  return parts
}

private fun cutAtHtmlEscape(value: String): String {
  val end = HTML_STOPS.minOf { stop ->
    value.indexOf(stop, ignoreCase = true).let { if (it < 0) value.length else it }
  }
  return value.substring(0, end)
}

private fun decodeHtmlAmpersands(value: String): String =
  HTML_AMPERSANDS.fold(value) { link, escape -> link.replace(escape, "&", ignoreCase = true) }

/** Drops trailing punctuation and closing brackets that have no opening one in [value]. */
private fun trimTrailing(value: String): String {
  var end = value.length
  while (end > 0) {
    val last = value[end - 1]
    val strip = when (last) {
      ')' -> isUnbalanced(value, end, '(', ')')
      ']' -> isUnbalanced(value, end, '[', ']')
      '}' -> isUnbalanced(value, end, '{', '}')
      else -> last in TRAILING_PUNCTUATION
    }
    if (!strip) break
    end--
  }
  return value.substring(0, end)
}

private fun isUnbalanced(value: String, end: Int, open: Char, close: Char): Boolean {
  var depth = 0
  for (i in 0 until end) {
    if (value[i] == open) depth++ else if (value[i] == close) depth--
  }
  return depth < 0
}

/**
 * Positions in a URL's authority, which starts after `://`.
 *
 * @property start where the authority starts.
 * @property hostStart where the host starts, after the user info if there is any.
 * @property end where the host and port end.
 */
internal class Authority(val start: Int, val hostStart: Int, val end: Int)

/** The authority of [url], or `null` when it has no `scheme://`. */
internal fun authorityOf(url: String): Authority? {
  val start = url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
  // Like FtpUrl, only a slash ends an FTP link's user info: an unencoded password may hold ? or
  // #. Elsewhere they end the authority, so an @ in a query is not taken for user info.
  val ends = if (url.startsWith("ftp", ignoreCase = true)) charArrayOf('/') else AUTHORITY_ENDS
  val userInfoEnd = url.indexOfAny(ends, start).let { if (it < 0) url.length else it }
  val hostStart = url.lastIndexOf('@', userInfoEnd - 1).let { if (it < start) start else it + 1 }
  val end = url.indexOfAny(AUTHORITY_ENDS, hostStart)
  return Authority(start, hostStart, if (end < 0) url.length else end)
}

private fun hasHost(url: String): Boolean {
  val authority = authorityOf(url) ?: return false
  val host = url.substring(authority.hostStart, authority.end)
  return host.isNotEmpty() && !host.startsWith(':')
}

/** Reads a `.csv` line into cells, split at commas, semicolons or tabs outside quotes. */
private fun csvCells(line: String): List<String> {
  val cells = mutableListOf<String>()
  val cell = StringBuilder()
  var quoted = false
  var i = 0
  while (i < line.length) {
    val char = line[i]
    when {
      quoted && char == '"' && line.getOrNull(i + 1) == '"' -> {
        cell.append('"')
        i++
      }
      char == '"' -> quoted = !quoted
      !quoted && (char == ',' || char == ';' || char == '\t') -> {
        cells += cell.toString()
        cell.clear()
      }
      else -> cell.append(char)
    }
    i++
  }
  cells += cell.toString()
  return cells
}

/** One part of a pattern: literal text has a single choice. */
private class Glob(val size: Long, val valueAt: (Long) -> String)

/** Expands the ranges in [url], or returns `null` when it has none. */
private fun expand(url: String): IntakeItem.Range? {
  val globs = mutableListOf<Glob>()
  val literal = StringBuilder()
  var hasRange = false
  fun flush() {
    if (literal.isEmpty()) return
    val text = literal.toString()
    globs += Glob(1) { text }
    literal.clear()
  }
  var i = 0
  while (i < url.length) {
    val char = url[i]
    val close = when (char) {
      '[' -> url.indexOf(']', i + 1)
      '{' -> url.indexOf('}', i + 1)
      else -> -1
    }
    val glob = if (close > i) parseGlob(char, url.substring(i + 1, close)) else null
    if (glob == null) {
      literal.append(char)
      i++
      continue
    }
    flush()
    globs += glob
    hasRange = true
    i = close + 1
  }
  if (!hasRange) return null
  flush()
  val total = globs.fold(1L) { product, glob ->
    if (product > Long.MAX_VALUE / glob.size) Long.MAX_VALUE else product * glob.size
  }
  val count = minOf(total, MAX_EXPANDED_LINKS.toLong())
  val urls = (0 until count).map { index ->
    var remaining = index
    val values = globs.asReversed().map { glob ->
      glob.valueAt(remaining % glob.size).also { remaining /= glob.size }
    }
    values.asReversed().joinToString("")
  }
  return IntakeItem.Range(url, urls, truncated = total > count)
}

/**
 * The range inside `[...]` (numbers or single letters, ascending) or `{...}` (choices separated
 * by commas), or `null` when [body] is not one, such as the `[::1]` of an IPv6 host.
 */
private fun parseGlob(open: Char, body: String): Glob? {
  if (open == '{') {
    if (',' !in body || '{' in body) return null
    val choices = body.split(',')
    return Glob(choices.size.toLong()) { choices[it.toInt()] }
  }
  val start = body.substringBefore('-', "")
  val end = body.substringAfter('-', "")
  if (start.isEmpty() || end.isEmpty()) return null
  if (start.all { it.isAsciiDigit() } && end.all { it.isAsciiDigit() }) {
    if (start.length > 18 || end.length > 18) return null
    val first = start.toLong()
    val last = end.toLong()
    if (first > last) return null
    val width = if (start.length > 1 && start[0] == '0') start.length else 1
    return Glob(last - first + 1) { (first + it).toString().padStart(width, '0') }
  }
  val sameCase = start.length == 1 && end.length == 1 && start[0] <= end[0] &&
    ((start[0] in 'a'..'z' && end[0] in 'a'..'z') || (start[0] in 'A'..'Z' && end[0] in 'A'..'Z'))
  if (!sameCase) return null
  val first = start[0]
  return Glob((end[0] - first + 1).toLong()) { (first + it.toInt()).toString() }
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isSchemeChar(): Boolean =
  this in 'a'..'z' || this in 'A'..'Z' || isAsciiDigit() || this == '+' || this == '-'
