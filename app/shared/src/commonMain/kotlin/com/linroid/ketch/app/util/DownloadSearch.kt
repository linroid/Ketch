package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.StatusFilter
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.file_type_app
import ketch.app.shared.generated.resources.file_type_archive
import ketch.app.shared.generated.resources.file_type_audio
import ketch.app.shared.generated.resources.file_type_doc
import ketch.app.shared.generated.resources.file_type_image
import ketch.app.shared.generated.resources.file_type_other
import ketch.app.shared.generated.resources.file_type_torrent
import ketch.app.shared.generated.resources.file_type_video
import ketch.app.shared.generated.resources.origin_agent
import ketch.app.shared.generated.resources.origin_app
import ketch.app.shared.generated.resources.origin_browser
import ketch.app.shared.generated.resources.origin_cli
import ketch.app.shared.generated.resources.origin_discover
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Where a task was added from, recorded in its request property [TaskOrigin.PROPERTY].
 *
 * @property id value of the property and of the `origin:` search token.
 */
enum class TaskOrigin(val id: String, private val resource: StringResource) {
  Browser("browser", Res.string.origin_browser),
  Discover("discover", Res.string.origin_discover),
  Agent("agent", Res.string.origin_agent),
  App("app", Res.string.origin_app),
  Cli("cli", Res.string.origin_cli);

  /** Name shown in the Origin column. */
  val label: UiText get() = resource.text()

  companion object {
    /**
     * Request property naming where a task was added from: `browser`, `discover`, `agent`,
     * `app` or `cli`. Tasks without it have no origin.
     */
    const val PROPERTY: String = "ketch.origin"

    /** Origin of the task downloading [request], or `null` when it is unknown. */
    fun of(request: DownloadRequest): TaskOrigin? = fromId(request.properties[PROPERTY])

    /** The origin whose [id] is [value], ignoring case. */
    fun fromId(value: String?): TaskOrigin? {
      val id = value?.trim() ?: return null
      return entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
  }
}

/**
 * Broad file types of the `type:` search token and of grouping by type, each folding several
 * [FileKind]s.
 *
 * @property id value of the `type:` search token.
 */
enum class FileType(val id: String, private val resource: StringResource) {
  Video("video", Res.string.file_type_video),
  Audio("audio", Res.string.file_type_audio),
  Image("image", Res.string.file_type_image),
  Doc("doc", Res.string.file_type_doc),
  Archive("archive", Res.string.file_type_archive),
  App("app", Res.string.file_type_app),
  Torrent("torrent", Res.string.file_type_torrent),
  Other("other", Res.string.file_type_other);

  /** Group title. */
  val label: UiText get() = resource.text()

  companion object {
    /** The type [kind] belongs to. */
    fun of(kind: FileKind): FileType = when (kind) {
      FileKind.Video, FileKind.Subtitle -> Video
      FileKind.Audio -> Audio
      FileKind.Image, FileKind.Design -> Image
      FileKind.Document, FileKind.Pdf, FileKind.Ebook, FileKind.Spreadsheet,
      FileKind.Presentation, FileKind.Text, FileKind.Email -> Doc
      FileKind.Archive, FileKind.DiskImage -> Archive
      FileKind.App -> App
      FileKind.Torrent -> Torrent
      FileKind.Code, FileKind.Data, FileKind.Database, FileKind.Web, FileKind.Model3d,
      FileKind.Font, FileKind.Key, FileKind.Unknown -> Other
    }

    /** The type whose [id] is [value], ignoring case. */
    fun fromId(value: String): FileType? =
      entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
  }
}

/**
 * What a search looks at in a task. Free text matches the decoded [name], [host],
 * [refererHost], [outputPath] and [errorTitle]; tokens match the other fields.
 */
interface SearchTarget {
  /** Display name of the task, from [displayName]. */
  val name: String

  /** Host of the task's URL, or `null` for magnets and other host-less links. */
  val host: String?

  /** Host of the page the link was found on, from its `Referer` header. */
  val refererHost: String?

  /** Where the file is or will be saved, decoded; `null` when not known yet. */
  val outputPath: String?

  /** Title of the error of a failed task. */
  val errorTitle: String?

  /** Current state of the task. */
  val state: DownloadState

  /**
   * Whether the task is queued but already holds a slot, which [state] alone does not tell; see
   * [com.linroid.ketch.app.state.isStarting].
   */
  val isStarting: Boolean
    get() = false

  /** Queue priority of the task. */
  val priority: DownloadPriority

  /** Whether the task downloads but has received no data for a while. */
  val isStalled: Boolean

  /** Whether a per-task limit, the global limit or Slow lane caps the task. */
  val isLimited: Boolean

  /** Broad type of the file. */
  val fileType: FileType

  /** Whether the task downloads a torrent. */
  val isTorrent: Boolean

  /** Where the task was added from, if known. */
  val origin: TaskOrigin?

  /** Name of the device that runs the task. */
  val deviceName: String

  /** Size of the file in bytes, or `null` while unknown. */
  val sizeBytes: Long?

  /** When the task was added. */
  val createdAt: Instant
}

/**
 * Values of the `is:` search token.
 *
 * @property id the value as typed after `is:`.
 */
enum class SearchStatus(val id: String) {
  Downloading("downloading"),
  Waiting("waiting"),
  Paused("paused"),
  Done("done"),
  Failed("failed"),
  Scheduled("scheduled"),
  Urgent("urgent"),
  Stalled("stalled"),
  Limited("limited");

  /** Whether [target] has this status. Status words use the [StatusFilter] definitions. */
  fun matches(target: SearchTarget): Boolean = when (this) {
    Downloading -> StatusFilter.Downloading.matches(target)
    Waiting -> StatusFilter.Waiting.matches(target)
    Paused -> StatusFilter.Paused.matches(target)
    Done -> StatusFilter.Done.matches(target)
    Failed -> StatusFilter.Failed.matches(target)
    Scheduled -> target.state is DownloadState.Scheduled
    Urgent -> target.priority == DownloadPriority.URGENT
    Stalled -> target.isStalled
    Limited -> target.isLimited
  }

  /** Whether this word names a state; a task is in one state but may have every flag. */
  internal val isState: Boolean
    get() = ordinal <= Scheduled.ordinal
}

/**
 * How a `size:` token compares, written before the size: `>`, `>=`, `<` or `<=`.
 *
 * @property symbol the operator as typed.
 */
enum class SizeComparison(val symbol: String) {
  Greater(">"),
  AtLeast(">="),
  Less("<"),
  AtMost("<=");

  internal fun test(size: Long, bound: Long): Boolean = when (this) {
    Greater -> size > bound
    AtLeast -> size >= bound
    Less -> size < bound
    AtMost -> size <= bound
  }
}

/** The span of an `added:` token. */
sealed class AddedSpan {
  /** Added on the current local day. */
  data object Today : AddedSpan()

  /** Added on the previous local day. */
  data object Yesterday : AddedSpan()

  /** Added at most [duration] ago, as in `added:<7d`. */
  data class Within(val duration: Duration) : AddedSpan()

  /** Added more than [duration] ago, as in `added:>7d`. */
  data class OlderThan(val duration: Duration) : AddedSpan()
}

/**
 * A `key:value` filter of a search, shown as a removable chip.
 *
 * Tokens of the same facet widen a search: `type:video type:audio` finds either. Size and date
 * bounds and the `urgent`, `stalled` and `limited` flags narrow it: every one must hold.
 */
sealed class SearchToken(
  /** The key before the colon, such as `is`. */
  val key: String,
  /** Tokens with the same non-null facet widen the search instead of narrowing it. */
  internal val facet: String?,
) {
  /** The value after the colon, as typed. */
  abstract val value: String

  /** The token as typed, quoting a value with spaces: `device:"This Mac"`. */
  val label: String
    get() = "$key:${quoteIfNeeded(value)}"

  /** Whether [target] passes this token at [now] in [timeZone]. */
  abstract fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean

  /** `is:` with a state or a flag. */
  data class Is(val status: SearchStatus) : SearchToken(IS, IS.takeIf { status.isState }) {
    override val value: String get() = status.id

    override fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean =
      status.matches(target)
  }

  /** `type:`, such as `type:video`. A torrent also matches `type:torrent`. */
  data class Type(val type: FileType) : SearchToken(TYPE, TYPE) {
    override val value: String get() = type.id

    override fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean =
      target.fileType == type || (type == FileType.Torrent && target.isTorrent)
  }

  /** `host:`, matching the link's host or the page it came from, subdomains included. */
  data class Host(val host: String) : SearchToken(HOST, HOST) {
    override val value: String get() = host

    override fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean =
      hostMatches(target.host) || hostMatches(target.refererHost)

    private fun hostMatches(candidate: String?): Boolean =
      candidate != null && (candidate == host || candidate.endsWith(".$host"))
  }

  /** `origin:`, such as `origin:browser`. Tasks of unknown origin never match. */
  data class Origin(val origin: TaskOrigin) : SearchToken(ORIGIN, ORIGIN) {
    override val value: String get() = origin.id

    override fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean =
      target.origin == origin
  }

  /** `device:`, matching device names that contain [name], ignoring case. */
  data class Device(val name: String) : SearchToken(DEVICE, DEVICE) {
    override val value: String get() = name

    override fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean =
      target.deviceName.contains(name, ignoreCase = true)
  }

  /**
   * `size:`, such as `size:>1gb`. Without a comparison it finds files of at least that size.
   * Units are powers of 1024, like the sizes shown. Tasks of unknown size never match.
   *
   * @property comparison how the size compares with [bytes].
   * @property bytes the bound in bytes.
   */
  data class Size(
    val comparison: SizeComparison,
    val bytes: Long,
    override val value: String,
  ) : SearchToken(SIZE, facet = null) {

    override fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean {
      val size = target.sizeBytes ?: return false
      return comparison.test(size, bytes)
    }
  }

  /** `added:`, such as `added:today` or `added:<7d`; a bare span means "within". */
  data class Added(val span: AddedSpan, override val value: String) :
    SearchToken(ADDED, facet = null) {

    override fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean {
      val age = now - target.createdAt
      return when (span) {
        AddedSpan.Today -> localDate(target.createdAt, timeZone) == localDate(now, timeZone)
        AddedSpan.Yesterday -> localDate(target.createdAt, timeZone) ==
          localDate(now, timeZone).minus(1, DateTimeUnit.DAY)
        is AddedSpan.Within -> age <= span.duration
        is AddedSpan.OlderThan -> age > span.duration
      }
    }
  }

  companion object {
    /** Reads `key:value`, or returns `null` when the key or value is not recognized. */
    fun parse(key: String, value: String): SearchToken? {
      val text = value.trim()
      if (text.isEmpty()) return null
      return when (key.lowercase()) {
        IS -> SearchStatus.entries.firstOrNull { it.id.equals(text, ignoreCase = true) }
          ?.let(::Is)
        TYPE -> FileType.fromId(text)?.let(::Type)
        HOST -> parseHost(text)?.let(::Host)
        ORIGIN -> TaskOrigin.fromId(text)?.let(::Origin)
        DEVICE -> Device(text)
        SIZE -> parseSize(text)
        ADDED -> parseAdded(text)?.let { Added(it, text.lowercase()) }
        else -> null
      }
    }

    private fun parseHost(value: String): String? {
      val host = if ("://" in value) urlHost(value) else value.substringBefore('/')
      return host?.lowercase()?.trimEnd('.')?.ifEmpty { null }
    }

    private fun parseSize(value: String): Size? {
      val match = SIZE_PATTERN.matchEntire(value.lowercase()) ?: return null
      val (symbol, number, unit) = match.destructured
      val comparison = SizeComparison.entries.firstOrNull { it.symbol == symbol }
        ?: SizeComparison.AtLeast
      val multiplier = when (unit.firstOrNull()) {
        'k' -> 1L shl 10
        'm' -> 1L shl 20
        'g' -> 1L shl 30
        't' -> 1L shl 40
        else -> 1L
      }
      val bytes = number.toDoubleOrNull()?.times(multiplier) ?: return null
      if (bytes >= Long.MAX_VALUE.toDouble()) return null
      return Size(comparison, bytes.toLong(), value.lowercase())
    }

    private fun parseAdded(value: String): AddedSpan? {
      when (value.lowercase()) {
        "today" -> return AddedSpan.Today
        "yesterday" -> return AddedSpan.Yesterday
      }
      val match = ADDED_PATTERN.matchEntire(value.lowercase()) ?: return null
      val (symbol, number, unit) = match.destructured
      val count = number.toIntOrNull() ?: return null
      val duration = when (unit) {
        "h" -> count.hours
        "d" -> count.days
        else -> (count * 7).days
      }
      return if (symbol == ">") AddedSpan.OlderThan(duration) else AddedSpan.Within(duration)
    }

    private const val IS = "is"
    private const val TYPE = "type"
    private const val HOST = "host"
    private const val ORIGIN = "origin"
    private const val DEVICE = "device"
    private const val SIZE = "size"
    private const val ADDED = "added"
    private val SIZE_PATTERN = Regex("""(>=|<=|>|<)?(\d+(?:\.\d+)?)(b|[kmgt]i?b?)?""")
    private val ADDED_PATTERN = Regex("""([<>])?(\d{1,5})([hdw])""")
  }
}

/**
 * A parsed search: free text plus [SearchToken]s.
 *
 * Every word of free text must appear, ignoring case, in one of the decoded fields of
 * [SearchTarget]; double quotes keep a phrase together. A word such as `is:paused` whose key and
 * value are recognized becomes a token; any other word is free text.
 *
 * @property terms words and quoted phrases of free text.
 * @property tokens the filters, in the order typed.
 */
data class SearchQuery(
  val terms: List<String> = emptyList(),
  val tokens: List<SearchToken> = emptyList(),
) {
  /** Whether the query matches every task. */
  val isEmpty: Boolean
    get() = terms.isEmpty() && tokens.isEmpty()

  /** The free text, for the search field next to the token chips. */
  val text: String
    get() = terms.joinToString(" ") { quoteIfNeeded(it) }

  /** Whether [target] matches at [now] in [timeZone]. */
  fun matches(target: SearchTarget, now: Instant, timeZone: TimeZone): Boolean {
    if (terms.isNotEmpty()) {
      val fields = listOfNotNull(
        target.name,
        target.host,
        target.refererHost,
        target.outputPath,
        target.errorTitle
      )
      if (!terms.all { term -> fields.any { it.contains(term, ignoreCase = true) } }) {
        return false
      }
    }
    return tokens.groupBy { it.facet }.all { (facet, group) ->
      if (facet == null) {
        group.all { it.matches(target, now, timeZone) }
      } else {
        group.any { it.matches(target, now, timeZone) }
      }
    }
  }

  /** This query with [token] added, as when `⌥`-clicking a host; a duplicate is ignored. */
  operator fun plus(token: SearchToken): SearchQuery =
    if (token in tokens) this else copy(tokens = tokens + token)

  /** This query without [token], as when its chip is removed. */
  operator fun minus(token: SearchToken): SearchQuery = copy(tokens = tokens - token)

  /** The query as it would be typed: tokens first, then the free text. */
  fun format(): String = (tokens.map { it.label } + terms.map(::quoteIfNeeded)).joinToString(" ")

  companion object {
    /** Matches every task. */
    val Empty: SearchQuery = SearchQuery()

    /** Parses what was typed in the search field. */
    fun parse(input: String): SearchQuery {
      val terms = mutableListOf<String>()
      val tokens = mutableListOf<SearchToken>()
      for (word in words(input)) {
        val colon = word.text.indexOf(':')
        val token = if (colon > 0 && !word.isPhrase) {
          SearchToken.parse(word.text.substring(0, colon), word.text.substring(colon + 1))
        } else {
          null
        }
        when {
          token == null -> terms += word.text
          token !in tokens -> tokens += token
        }
      }
      return SearchQuery(terms, tokens)
    }
  }
}

/** [path] with the escapes of an Android `content://` URI decoded; other paths are kept. */
internal fun decodePath(path: String): String =
  if (path.startsWith("content://", ignoreCase = true)) percentDecode(path) else path

private class Word(val text: String, val isPhrase: Boolean)

/**
 * Splits [input] at whitespace outside double quotes and drops the quotes. A word that starts
 * with a quote is a phrase, never a token, even when it contains a colon.
 */
private fun words(input: String): List<Word> {
  val words = mutableListOf<Word>()
  val current = StringBuilder()
  var quoted = false
  var startsQuoted = false
  var started = false
  fun flush() {
    if (started && current.isNotBlank()) words += Word(current.toString().trim(), startsQuoted)
    current.clear()
    started = false
    startsQuoted = false
  }
  for (char in input) {
    when {
      char == '"' -> {
        if (!started) startsQuoted = true
        started = true
        quoted = !quoted
      }
      char.isWhitespace() && !quoted -> flush()
      else -> {
        started = true
        current.append(char)
      }
    }
  }
  flush()
  return words
}

private fun quoteIfNeeded(value: String): String =
  if (value.any { it.isWhitespace() || it == ':' }) "\"$value\"" else value

private fun localDate(instant: Instant, timeZone: TimeZone) = instant.toLocalDateTime(timeZone).date
