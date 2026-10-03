package com.linroid.ketch.app.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isName
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.components.startTimeText
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.priorityText
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.util.CurlParser
import com.linroid.ketch.app.util.DuplicateDetector
import com.linroid.ketch.app.util.IntakeItem
import com.linroid.ketch.app.util.IntakeProblem
import com.linroid.ketch.app.util.LinkKind
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.MAGNET_METADATA_TIMEOUT
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.app.util.links
import com.linroid.ketch.app.util.percentDecode
import com.linroid.ketch.app.util.toIntakeProblem
import com.linroid.ketch.app.util.urlHost
import com.linroid.ketch.app.util.withCredentials
import com.linroid.ketch.config.ClipboardMode
import com.linroid.ketch.config.IntakePreferences
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_retry
import ketch.app.shared.generated.resources.action_review
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.count_downloads
import ketch.app.shared.generated.resources.device_any_in_sentence
import ketch.app.shared.generated.resources.duration_minutes
import ketch.app.shared.generated.resources.intake_add_failed
import ketch.app.shared.generated.resources.intake_apply_failed
import ketch.app.shared.generated.resources.intake_choose_files
import ketch.app.shared.generated.resources.intake_file_list_failed
import ketch.app.shared.generated.resources.intake_file_list_failed_unnamed
import ketch.app.shared.generated.resources.intake_file_list_ready
import ketch.app.shared.generated.resources.intake_file_list_ready_unnamed
import ketch.app.shared.generated.resources.intake_hint_apply
import ketch.app.shared.generated.resources.intake_hint_download
import ketch.app.shared.generated.resources.intake_hint_retry
import ketch.app.shared.generated.resources.intake_hint_schedule
import ketch.app.shared.generated.resources.intake_hint_start_over
import ketch.app.shared.generated.resources.intake_notice_close_first
import ketch.app.shared.generated.resources.intake_notice_first_links
import ketch.app.shared.generated.resources.intake_notice_no_curl
import ketch.app.shared.generated.resources.intake_notice_no_links
import ketch.app.shared.generated.resources.intake_notice_read_failed
import ketch.app.shared.generated.resources.intake_notice_read_failed_reason
import ketch.app.shared.generated.resources.intake_notice_unsupported_files
import ketch.app.shared.generated.resources.intake_option_auto
import ketch.app.shared.generated.resources.intake_option_connections
import ketch.app.shared.generated.resources.intake_option_max_speed
import ketch.app.shared.generated.resources.intake_option_peers
import ketch.app.shared.generated.resources.intake_option_priority_high
import ketch.app.shared.generated.resources.intake_option_priority_low
import ketch.app.shared.generated.resources.intake_option_unlimited
import ketch.app.shared.generated.resources.intake_option_urgent
import ketch.app.shared.generated.resources.intake_outcome_eta
import ketch.app.shared.generated.resources.intake_outcome_eta_soon
import ketch.app.shared.generated.resources.intake_outcome_queued_behind
import ketch.app.shared.generated.resources.intake_outcome_queued_count
import ketch.app.shared.generated.resources.intake_outcome_queued_next
import ketch.app.shared.generated.resources.intake_outcome_slots_free
import ketch.app.shared.generated.resources.intake_outcome_start_now
import ketch.app.shared.generated.resources.intake_outcome_start_now_slots
import ketch.app.shared.generated.resources.intake_outcome_starts_now
import ketch.app.shared.generated.resources.intake_outcome_urgent
import ketch.app.shared.generated.resources.intake_outcome_waits_for_host
import ketch.app.shared.generated.resources.intake_restart_failed
import ketch.app.shared.generated.resources.intake_restarted
import ketch.app.shared.generated.resources.intake_retry_failed
import ketch.app.shared.generated.resources.intake_space_warning
import ketch.app.shared.generated.resources.intake_start_over_discard
import ketch.app.shared.generated.resources.intake_start_over_new
import ketch.app.shared.generated.resources.intake_submit_apply
import ketch.app.shared.generated.resources.intake_submit_download_files
import ketch.app.shared.generated.resources.intake_submit_download_files_size
import ketch.app.shared.generated.resources.intake_submit_download_items
import ketch.app.shared.generated.resources.intake_submit_download_items_size
import ketch.app.shared.generated.resources.intake_submit_download_name
import ketch.app.shared.generated.resources.intake_submit_no_files
import ketch.app.shared.generated.resources.intake_submit_nothing
import ketch.app.shared.generated.resources.intake_submit_schedule_files
import ketch.app.shared.generated.resources.intake_submit_schedule_items
import ketch.app.shared.generated.resources.intake_submit_schedule_name
import ketch.app.shared.generated.resources.intake_submit_start_over
import ketch.app.shared.generated.resources.intake_submit_waiting_files
import ketch.app.shared.generated.resources.intake_summary_attention
import ketch.app.shared.generated.resources.intake_summary_checking
import ketch.app.shared.generated.resources.intake_summary_duplicates
import ketch.app.shared.generated.resources.intake_summary_links
import ketch.app.shared.generated.resources.intake_summary_ready
import ketch.app.shared.generated.resources.intake_time_hours
import ketch.app.shared.generated.resources.intake_time_hours_minutes
import ketch.app.shared.generated.resources.intake_user_agent_custom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Most rows the add sheet holds; links past them are left out. */
const val MAX_INTAKE_ROWS: Int = 200

/** What one row of the add sheet downloads. */
sealed interface IntakeSource {
  /** Identifies the row while the input changes, so an unchanged line keeps its check. */
  val key: String

  /**
   * A link.
   *
   * @property headers request headers that came with it, such as a cURL command's cookies or a
   *   browser capture's.
   * @property fileName name to save it as; `null` lets the source decide.
   * @property properties bookkeeping for [DownloadRequest.properties], such as its origin.
   */
  data class Link(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val fileName: String? = null,
    val properties: Map<String, String> = emptyMap(),
  ) : IntakeSource {
    override val key: String get() = url

    /** What kind of source downloads [url]. */
    val kind: LinkKind get() = LinkKind.of(url)
  }

  /**
   * A pattern such as `part[01-04].rar`, added as one download per link it expands to.
   *
   * @property truncated whether the pattern expands to more links than [urls] holds.
   */
  data class Range(
    val pattern: String,
    val urls: List<String>,
    val truncated: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
  ) : IntakeSource {
    override val key: String get() = "range:$pattern"
  }

  /** A `.torrent` file picked or dropped, resolved from its content. */
  class File(val file: DroppedFile, override val key: String) : IntakeSource
}

/** How far the check of a row on the target device got. */
sealed interface IntakeStatus {
  /** Waiting for a free check; rows past the first 50 wait until they are shown. */
  data object Waiting : IntakeStatus

  /** Being checked since [since]; a magnet waits for peers to send its file list. */
  data class Checking(val since: Instant) : IntakeStatus

  /** Checked: the size, resume support and files the target device found. */
  data class Ready(val source: ResolvedSource) : IntakeStatus

  /** The check failed; [problem] says why and what the row offers. */
  data class Problem(val problem: IntakeProblem, val cause: Throwable? = null) : IntakeStatus
}

/** One row of the add sheet: what it downloads, how its check went and the user's choices. */
@Stable
class IntakeEntry internal constructor(source: IntakeSource) {
  /** What the row downloads. */
  var source: IntakeSource by mutableStateOf(source)
    internal set

  /** How far its check got. */
  var status: IntakeStatus by mutableStateOf(IntakeStatus.Waiting)
    internal set

  /** The task on the target device that already downloads the same thing, or `null`. */
  var duplicate: DownloadTask? by mutableStateOf(null)
    internal set

  /** Whether a [duplicate] is added again; duplicates stay out by default. */
  var downloadAgain: Boolean by mutableStateOf(false)

  /** Whether the row is added despite a problem, or a torrent without its file list. */
  var addAnyway: Boolean by mutableStateOf(false)

  /** Name typed for the file; blank lets the source decide. */
  var fileName: String by mutableStateOf("")

  /** Ids of the torrent files to download; `null` until the file list arrives. */
  var selectedFiles: Set<String>? by mutableStateOf(null)

  internal var job: Job? = null
  internal var wanted: Boolean = false

  /** See [IntakeSource.key]. */
  val key: String get() = source.key

  /** What the check found, once it succeeded. */
  val resolved: ResolvedSource? get() = (status as? IntakeStatus.Ready)?.source

  /** Downloads the row adds: the links a range expands to, otherwise one. */
  val linkCount: Int get() = (source as? IntakeSource.Range)?.urls?.size ?: 1

  /** Whether the row is a magnet link. */
  val isMagnet: Boolean get() = (source as? IntakeSource.Link)?.kind == LinkKind.Magnet

  /** Whether the row is a torrent: a magnet, a `.torrent` link or file. */
  val isTorrent: Boolean
    get() = when (val current = source) {
      is IntakeSource.File -> true
      is IntakeSource.Link -> current.kind.isTorrent || resolved?.sourceType == TORRENT_SOURCE
      is IntakeSource.Range -> false
    }

  /** The files of a torrent with several, once its file list arrived; otherwise empty. */
  val files: List<SourceFile> get() = resolved?.files?.takeIf { it.size > 1 }.orEmpty()

  /** The link the row stands for: a range's first link, or a file's resolved `torrent:` id. */
  val url: String?
    get() = when (val current = source) {
      is IntakeSource.Link -> current.url
      is IntakeSource.Range -> current.urls.first()
      is IntakeSource.File -> resolved?.url
    }

  /**
   * Name shown for the row, the one it is saved as: the typed one, the one it arrived with, the
   * source's, or one taken from the link.
   */
  val name: String
    get() {
      fileName.trim().takeIf { it.isNotEmpty() }?.let { return it }
      (source as? IntakeSource.Link)?.fileName?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { return it }
      val suggested = resolved?.suggestedFileName?.trim()?.takeIf { it.isNotEmpty() }
      if (suggested != null && source !is IntakeSource.Range) return suggested
      return when (val current = source) {
        is IntakeSource.Link -> displayName(DownloadRequest(current.url))
        is IntakeSource.Range ->
          "${extractFilename(current.urls.first())} … ${extractFilename(current.urls.last())}"
        is IntakeSource.File -> current.file.name.removeSuffix(".torrent")
      }
    }

  /** Bytes the row adds: the chosen files of a torrent, or the size times [linkCount]. */
  val bytes: Long?
    get() {
      val result = resolved ?: return null
      val selection = selectedFiles
      if (files.isNotEmpty() && selection != null) {
        return files.filter { it.id in selection }.sumOf { it.size.coerceAtLeast(0) }
      }
      return result.totalBytes.takeIf { it >= 0 }?.times(linkCount)
    }

  /** The problem that keeps the row out of the batch, or `null`. */
  val blockingProblem: IntakeProblem?
    get() = (status as? IntakeStatus.Problem)?.problem?.takeIf { it.blocksAdd && !addAnyway }

  /** Whether the row is a torrent that still waits for its file list. */
  val waitsForFiles: Boolean
    get() = isTorrent && (status is IntakeStatus.Waiting || status is IntakeStatus.Checking)

  /** Whether the row is a magnet whose peers sent no file list in time; it keeps waiting. */
  val isTimedOut: Boolean
    get() = (status as? IntakeStatus.Problem)?.problem == IntakeProblem.MagnetTimeout

  /**
   * Whether submitting waits for the row's check, which is under way, so that a link that fails
   * stays out of the batch. Torrents are not waited for: their peers may take minutes.
   */
  internal val checkPending: Boolean
    get() = wanted && !isTorrent && addable &&
      (status is IntakeStatus.Waiting || status is IntakeStatus.Checking)

  /** Whether submitting adds the row. */
  val addable: Boolean
    get() = when {
      duplicate != null && !downloadAgain -> false
      blockingProblem != null -> false
      isTorrent && status !is IntakeStatus.Ready && !addAnyway -> false
      files.isNotEmpty() && selectedFiles?.isEmpty() == true -> false
      else -> true
    }
}

/**
 * Counts of the add sheet's links, as its summary line shows them.
 *
 * @property links links in the sheet; a range counts each link it expands to.
 * @property ready links checked and ready to add.
 * @property checking links still being checked.
 * @property attention links with a problem that keeps them out.
 * @property duplicates links already in Ketch, left out unless added again.
 * @property bytes size of what will be added, of the links whose size is known.
 */
data class IntakeSummary(
  val links: Int,
  val ready: Int,
  val checking: Int,
  val attention: Int,
  val duplicates: Int,
  val bytes: Long,
) {
  /** "7 links · 5 ready · 1 checking · 1 needs attention · 29.1 GB". */
  val text: UiText
    get() = buildList {
      add(Res.plurals.intake_summary_links.text(links))
      add(Res.plurals.intake_summary_ready.text(ready))
      if (checking > 0) add(Res.plurals.intake_summary_checking.text(checking))
      if (attention > 0) add(Res.plurals.intake_summary_attention.text(attention))
      if (duplicates > 0) add(Res.plurals.intake_summary_duplicates.text(duplicates))
      if (bytes > 0) add(sizeText(bytes))
    }.joinText()
}

/** Counts [entries] for the summary line. */
internal fun intakeSummary(entries: List<IntakeEntry>): IntakeSummary {
  var links = 0
  var ready = 0
  var checking = 0
  var attention = 0
  var duplicates = 0
  var bytes = 0L
  for (entry in entries) {
    val count = entry.linkCount
    links += count
    when {
      entry.duplicate != null && !entry.downloadAgain -> duplicates += count
      entry.blockingProblem != null -> attention += count
      entry.status is IntakeStatus.Waiting || entry.status is IntakeStatus.Checking ->
        checking += count
      else -> ready += count
    }
    if (entry.addable) bytes += entry.bytes ?: 0
  }
  return IntakeSummary(links, ready, checking, attention, duplicates, bytes)
}

/**
 * A user agent the Advanced section offers. A `null` [value] sends no header, so the device's
 * engine sends Ketch's own, `Ketch/<version>`.
 */
enum class UserAgentChoice(val label: UiText, val value: String?) {
  Ketch(verbatim("Ketch"), null),
  Chrome(
    verbatim("Chrome"),
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/140.0.0.0 Safari/537.36",
  ),
  Firefox(
    verbatim("Firefox"),
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0",
  ),
  Safari(
    verbatim("Safari"),
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
      "Version/26.0 Safari/605.1.15",
  ),
  Custom(Res.string.intake_user_agent_custom.text(), null),
}

/** One extra header row of the Advanced section. */
@Stable
class HeaderRow(name: String = "", value: String = "") {
  /** Header name. */
  var name: String by mutableStateOf(name)

  /** Header value. */
  var value: String by mutableStateOf(value)
}

/**
 * Request headers typed in the add sheet's Advanced section, sent with every link: to the
 * device's check and with the download.
 */
@Stable
class IntakeHeaders {
  /** Page the links were found on. */
  var referer: String by mutableStateOf("")

  /** Which user agent to send. */
  var userAgent: UserAgentChoice by mutableStateOf(UserAgentChoice.Ketch)

  /** User agent sent with [UserAgentChoice.Custom]. */
  var customUserAgent: String by mutableStateOf("")

  /** Cookies, as a browser sends them. */
  var cookie: String by mutableStateOf("")

  /** Value of the `Authorization` header. */
  var authorization: String by mutableStateOf("")

  /** Further headers, in order. */
  val extra: MutableList<HeaderRow> = mutableStateListOf()

  /** The headers as a map; blank values are left out. */
  fun toMap(): Map<String, String> = buildMap {
    fun header(name: String, value: String) {
      val trimmed = value.trim()
      if (name.isNotBlank() && trimmed.isNotEmpty()) put(name.trim(), trimmed)
    }
    header(REFERER, referer)
    val agent = if (userAgent == UserAgentChoice.Custom) customUserAgent else userAgent.value
    agent?.let { header(USER_AGENT, it) }
    val cookies = cookie.lines().joinToString("; ") { it.trim().removeSuffix(";") }.trim(';', ' ')
    header(COOKIE_HEADER, cookies)
    header(AUTHORIZATION_HEADER, authorization)
    for (row in extra) header(row.name, row.value)
  }

  /** Fills the fields from [headers]; names it has no field for become extra rows. */
  fun fill(headers: Map<String, String>) {
    extra.clear()
    for ((name, value) in headers) {
      when (name.lowercase()) {
        REFERER.lowercase() -> referer = value
        COOKIE_HEADER.lowercase() -> cookie = value
        AUTHORIZATION_HEADER.lowercase() -> authorization = value
        USER_AGENT.lowercase() -> {
          val known = UserAgentChoice.entries.firstOrNull { it.value == value }
          userAgent = known ?: UserAgentChoice.Custom
          if (known == null) customUserAgent = value
        }
        else -> extra += HeaderRow(name, value)
      }
    }
  }

  internal companion object {
    /** Names of the headers the section has a field for, which also label the fields. */
    const val REFERER = "Referer"
    const val USER_AGENT = "User-Agent"
  }
}

/**
 * What an add holds, which picks the device it goes to by default: each kind goes where the
 * last add of that kind went this session, such as magnets to a NAS.
 */
enum class IntakeKind {
  /** Links to files, over HTTP(S) or FTP(S). */
  Links,

  /** Magnets and torrents only. */
  Torrents;

  companion object {
    /** [Torrents] when every one of [urls] and [files] is a torrent, else [Links]. */
    fun of(urls: List<String>, files: Int = 0): IntakeKind {
      if (urls.isEmpty() && files == 0) return Links
      return if (urls.all { LinkKind.of(it).isTorrent }) Torrents else Links
    }
  }
}

/** What the add sheet does on submit. */
enum class IntakeMode {
  /** Adds new downloads. */
  Add,

  /** Retries a failed task with changed options, or starts it over from a changed link. */
  Retry,

  /** Changes the options of a task that was just added. */
  Edit,
}

/** An option the add sheet's Options pill sums up. */
enum class IntakeOption {
  /** Speed limit of each download. */
  Speed,

  /** Priority of each download. */
  Priority,

  /** When the downloads start. */
  Start,

  /** Connections of each download, or a torrent's peer limit. */
  Connections,
}

/**
 * How [option] reads: "Unlimited", "Normal", "Now" and "Auto" at their defaults, or "Max
 * 2 MB/s", "⚡ Urgent", "Starts 23:00 tonight" and "8 connections" once [changed], which also
 * shows it as a chip that puts the default back.
 */
data class IntakeOptionValue(val option: IntakeOption, val text: UiText, val changed: Boolean)

/**
 * The values the Options pill sums up, in order: speed, priority, start (left out when
 * [schedule] is `null`) and connections, as a peer limit for [torrents]. A server that allows
 * one connection reads "1 connection" without counting as a change.
 */
internal fun intakeOptionValues(
  speedLimit: SpeedLimit,
  priority: DownloadPriority,
  schedule: DownloadSchedule?,
  connections: Int,
  torrents: Boolean,
  singleConnection: Boolean,
  now: Instant,
  zone: TimeZone,
): List<IntakeOptionValue> = buildList {
  val speedText = if (speedLimit.isUnlimited) {
    Res.string.intake_option_unlimited.text()
  } else {
    Res.string.intake_option_max_speed.text(speedLimitText(speedLimit))
  }
  add(IntakeOptionValue(IntakeOption.Speed, speedText, changed = !speedLimit.isUnlimited))
  val priorityValue = when (priority) {
    DownloadPriority.LOW -> Res.string.intake_option_priority_low.text()
    DownloadPriority.NORMAL -> priorityText(priority)
    DownloadPriority.HIGH -> Res.string.intake_option_priority_high.text()
    DownloadPriority.URGENT -> Res.string.intake_option_urgent.text()
  }
  add(IntakeOptionValue(IntakeOption.Priority, priorityValue, priority != DownloadPriority.NORMAL))
  if (schedule != null) {
    // A time that has passed starts at once, like no time.
    val later = when (schedule) {
      DownloadSchedule.Immediate -> false
      is DownloadSchedule.AfterDelay -> true
      is DownloadSchedule.AtTime -> schedule.startAt > now
    }
    add(IntakeOptionValue(IntakeOption.Start, startTimeText(schedule, now, zone), later))
  }
  val connectionText = when {
    torrents && connections > 0 -> Res.plurals.intake_option_peers.text(connections)
    singleConnection || connections == 1 -> Res.plurals.intake_option_connections.text(1)
    connections > 0 -> Res.plurals.intake_option_connections.text(connections)
    else -> Res.string.intake_option_auto.text()
  }
  val connectionsChanged = connections > 0 && (torrents || !singleConnection)
  add(IntakeOptionValue(IntakeOption.Connections, connectionText, connectionsChanged))
}

/** What the add sheet's main button does: its [verb] and the [hint] that names its key. */
private enum class SubmitAction(val verb: String, val hint: StringResource) {
  Download("download", Res.string.intake_hint_download),
  Schedule("schedule", Res.string.intake_hint_schedule),
  Retry("retry", Res.string.intake_hint_retry),
  StartOver("start over", Res.string.intake_hint_start_over),
  Apply("apply", Res.string.intake_hint_apply),
}

/** Whether a line of [text] starts a cURL command. */
internal fun holdsCurl(text: String): Boolean = text.lineSequence().any(CurlParser::isCurl)

/**
 * [text] shortened to [max] characters by an ellipsis in the middle, which keeps the start and
 * the end of a file name: "ubuntu-24.04-liv…server-amd64.iso".
 */
internal fun middleEllipsis(text: String, max: Int): String {
  if (text.length <= max || max < 2) return text
  val kept = max - 1
  val head = (kept + 1) / 2
  return text.take(head) + "…" + text.takeLast(kept - head)
}

/**
 * The add sheet's state for one [IntakeRequest]: the typed links, their checks on the target
 * device, the options that apply to all of them, and the submit.
 *
 * Rows are found in [text] by [LinkParser]; a typed change is read after a short pause, a paste
 * at once. Each row is checked on the target device, at most [PARALLEL_RESOLVES] at a time;
 * magnets wait for peers outside that limit. Every row is added on its own, so one failure
 * never stops the rest.
 */
@Stable
class IntakeSession internal constructor(
  /** What the sheet was opened with. */
  val request: IntakeRequest,
  private val state: AppState,
  private val scope: CoroutineScope,
  private val carry: (IntakeRequest, List<DroppedFile>) -> Unit = { _, _ -> },
) {
  private val log = KetchLogger("Intake")
  private val clock = state.clock
  private val limiter = Semaphore(PARALLEL_RESOLVES)
  private val seedsByUrl = mutableMapOf<String, IntakeSeed>()
  private var parseJob: Job? = null
  private var headersJob: Job? = null
  private var watchJob: Job? = null
  private var fileCount = 0

  // The retried task's headers as the Advanced fields give them back, to tell a real change.
  private var boundHeaders: Map<String, String> = emptyMap()

  /** Typed or pasted text. */
  var text: TextFieldValue by mutableStateOf(TextFieldValue())
    private set

  /** Whether [text] came from the clipboard, captioned "From clipboard". */
  var fromClipboard: Boolean by mutableStateOf(false)
    private set

  // Hash of the clipboard text this sheet was filled from, which its chip no longer offers.
  private var filledClip: String? by mutableStateOf(null)

  /** Whether [text] holds a cURL command, which the input then sets in the monospace style. */
  val isCurl: Boolean get() = holdsCurl(text.text)

  private var textEntries: List<IntakeEntry> by mutableStateOf(emptyList())
  private var fileEntries: List<IntakeEntry> by mutableStateOf(emptyList())

  /** Rows of the sheet: the links in [text], then the `.torrent` files. */
  val entries: List<IntakeEntry> get() = textEntries + fileEntries

  /** Text without links, offered to Discover; `null` when the text holds links. */
  var discoverQuery: String? by mutableStateOf(null)
    private set

  /** A note under the input, such as rows left out; `null` when there is none. */
  var notice: UiText? by mutableStateOf(null)

  /** Device the downloads go to. */
  var target: InstanceEntry? by mutableStateOf(null)
    private set

  // Whether the user or the request chose the target, so the kind of the rows no longer does.
  private var targetChosen = request.targetDeviceId != null

  /** What the rows hold, which picks the target until one is chosen. */
  val kind: IntakeKind
    get() {
      val (files, links) = entries.partition { it.source is IntakeSource.File }
      return IntakeKind.of(urls = links.mapNotNull { it.url }, files = files.size)
    }

  /** Status of [target], for its folders, free space and queue; `null` until it answers. */
  var targetStatus: KetchStatus? by mutableStateOf(null)
    private set

  /** Tasks of [target], without the ones a pending operation hides. */
  var targetTasks: List<DownloadTask> by mutableStateOf(emptyList())
    private set

  /** Folder to save in; `null` uses the target device's default directory. */
  var folder: String? by mutableStateOf(null)

  /** Speed limit of each download. */
  var speedLimit: SpeedLimit by mutableStateOf(SpeedLimit.Unlimited)

  /** Priority of each download. */
  var priority: DownloadPriority by mutableStateOf(DownloadPriority.NORMAL)

  /** When the downloads start. */
  var schedule: DownloadSchedule by mutableStateOf(DownloadSchedule.Immediate)

  /** Connections of each download; 0 uses the device default. */
  var connections: Int by mutableStateOf(0)

  /** Headers of the Advanced section. */
  val headers: IntakeHeaders = IntakeHeaders()

  /** Whether the Advanced section is open; remembered between sheets. */
  var advancedOpen: Boolean by mutableStateOf(state.appSettings.ui.intakeAdvancedOpen)
    private set

  /** The row whose torrent files are being picked, or `null`. */
  var torrentStage: IntakeEntry? by mutableStateOf(null)

  /**
   * [torrentStage] while its file list is there; a row checked again, such as on another
   * device, shows its waiting state until the list is back.
   */
  val activeStage: IntakeEntry? get() = torrentStage?.takeIf { it.files.isNotEmpty() }

  /** Whether the submit is running. */
  var submitting: Boolean by mutableStateOf(false)
    private set

  /** Whether the sheet asks before it closes and drops what was typed. */
  var confirmingClose: Boolean by mutableStateOf(false)
    private set

  /** Whether the sheet was closed while a torrent's file list still loads. */
  var inBackground: Boolean by mutableStateOf(false)
    internal set

  /** What the sheet does on submit. */
  val mode: IntakeMode = when {
    request.editTask != null -> IntakeMode.Edit
    request.retryOf != null -> IntakeMode.Retry
    else -> IntakeMode.Add
  }

  /** The task [IntakeMode.Retry] or [IntakeMode.Edit] works on, once it is found. */
  var task: DownloadTask? by mutableStateOf(null)
    private set

  /** Every device downloads can be added to. */
  val instances: StateFlow<List<InstanceEntry>> get() = state.instances

  /** What each device is doing, which the target menu sums up. */
  val presence: StateFlow<List<DevicePresence>> get() = state.instanceManager.presence

  /** What the sheet does with the clipboard; see [AppSettingsController.clipboardMode]. */
  val clipboardMode: ClipboardMode get() = state.appSettings.clipboardMode

  /** The default directory of [target]. */
  val defaultFolder: String? get() = targetStatus?.system?.downloadDirectory

  /** Path separator of [target]. */
  val separator: String get() = targetStatus?.system?.separator ?: "/"

  /** Whether [folder] is an Android folder picked through the system, as a `content://` tree. */
  val isSystemFolder: Boolean get() = folder?.startsWith(CONTENT_SCHEME, ignoreCase = true) == true

  /** Folders downloads on [target] were saved to lately, newest first, at most five. */
  val recentFolders: List<String>
    get() = recentFolders(targetTasks, separator).filter { it != defaultFolder }

  /** Folders pinned for [target]. */
  val pinnedFolders: List<String>
    get() = target?.let { state.appSettings.ui.intake[it.deviceId]?.favoriteFolders }.orEmpty()

  /** Counts for the summary line. */
  val summary: IntakeSummary get() = intakeSummary(entries)

  /** Links that submitting adds. */
  val addableLinks: Int get() = entries.filter { it.addable }.sumOf { it.linkCount }

  /** Whether every row failed its check, so the main action retries them. */
  val allFailed: Boolean
    get() = entries.isNotEmpty() && entries.all { it.blockingProblem != null }

  /** The only row, when there is exactly one: the sheet then shows it as a preview. */
  val single: IntakeEntry? get() = entries.singleOrNull()?.takeIf { it.linkCount == 1 }

  /**
   * Connections the links allow: up to the most segments a checked link supports, at most 32;
   * each download still uses no more than its own server allows. `null` when no link was
   * checked yet.
   */
  val maxConnections: Int?
    get() = (entries.mapNotNull { it.resolved } + listOfNotNull(boundRequest?.resolvedSource))
      .filter { it.sourceType != TORRENT_SOURCE }
      .maxOfOrNull { it.maxSegments }?.coerceIn(1, MAX_CONNECTIONS)

  /** Whether every download is a torrent, whose connections are a peer limit. */
  val torrentsOnly: Boolean
    get() {
      val bound = boundRequest
      if (mode == IntakeMode.Edit && bound != null) {
        return LinkKind.of(bound.url).isTorrent ||
          bound.resolvedSource?.sourceType == TORRENT_SOURCE
      }
      return entries.isNotEmpty() && entries.all { it.isTorrent }
    }

  private val boundRequest: DownloadRequest? get() = task?.requestState?.value

  /** Connections a download gets with Auto, from the target's settings. */
  val autoConnections: Int? get() = targetStatus?.config?.maxConnectionsPerDownload

  /** Bytes the selection needs beyond the target's free space, as a warning; else `null`. */
  val spaceWarning: UiText?
    get() {
      val free = targetStatus?.system?.usableSpace?.takeIf { it > 0 } ?: return null
      val needed = summary.bytes
      if (needed <= free) return null
      return Res.string.intake_space_warning.text(sizeText(needed), sizeText(free), targetName())
    }

  /**
   * A warning that cookies or credentials go to another device, which keeps them with the
   * task; `null` when the downloads stay on this device or send none.
   */
  val cookieWarning: UiText?
    get() {
      val remote = target as? RemoteInstance ?: return null
      return credentialWarningText(entries.map(::effectiveHeaders), remote.displayName)
    }

  /**
   * What happens once the rows are added, such as "Starts now · 1 of 2 slots free"; a scheduled
   * start is left to the Start chip.
   */
  val outcome: UiText?
    get() {
      if (mode == IntakeMode.Retry && startsOver) return startOverWarning()
      if (mode != IntakeMode.Add || schedule != DownloadSchedule.Immediate) return null
      val adding = entries.filter { it.addable }
      return intakeOutcome(
        hosts = adding.flatMap { entry ->
          val urls = (entry.source as? IntakeSource.Range)?.urls ?: listOfNotNull(entry.url)
          urls.map(::urlHost)
        },
        priority = priority,
        schedule = schedule,
        config = targetStatus?.config,
        tasks = targetTasks,
        bytes = summary.bytes.takeIf { it > 0 },
        now = clock.now(),
        zone = TimeZone.currentSystemDefault(),
      )
    }

  /** Whether a retry adds the task again, discarding its progress, because its link changed. */
  val startsOver: Boolean
    get() {
      val current = boundRequest ?: return false
      val entry = entries.singleOrNull() ?: return false
      return entry.url != current.url || effectiveHeaders(entry) != boundHeaders
    }

  /**
   * Whether the sheet shows its options and main button: once it holds a link or a torrent, and
   * always while it retries or edits a task. An empty sheet is only its input.
   */
  val showsOptions: Boolean get() = mode != IntakeMode.Add || entries.isNotEmpty()

  /**
   * Text of the main button, which says what it does: "Download ubuntu-24.04.iso", "Download 3
   * files · 1.2 GB", "Schedule 2 files", "Download 16 files · 9.3 GB" for a torrent's chosen
   * files, "Retry", "Start over" or "Apply changes".
   */
  val primaryLabel: UiText
    get() {
      val stage = activeStage?.takeIf { entries.size == 1 }
      return when {
        mode == IntakeMode.Edit -> Res.string.intake_submit_apply.text()
        mode == IntakeMode.Retry && startsOver -> Res.string.intake_submit_start_over.text()
        mode == IntakeMode.Retry || allFailed -> Res.string.action_retry.text()
        stage != null -> filesLabel(stage)
        single?.waitsForFiles == true && single?.addAnyway == false ->
          Res.string.intake_submit_waiting_files.text()
        else -> batchLabel()
      }
    }

  /** What ↩ does, after "↩ to": "download", "schedule", "retry", "start over" or "apply". */
  val submitVerb: String get() = submitAction.verb

  /** "↩ to download": what pressing [chord] does. */
  fun submitHint(chord: String): UiText = submitAction.hint.text(chord)

  private val submitAction: SubmitAction
    get() = when {
      mode == IntakeMode.Edit -> SubmitAction.Apply
      mode == IntakeMode.Retry -> if (startsOver) SubmitAction.StartOver else SubmitAction.Retry
      allFailed -> SubmitAction.Retry
      schedule != DownloadSchedule.Immediate -> SubmitAction.Schedule
      else -> SubmitAction.Download
    }

  private val scheduled: Boolean get() = schedule != DownloadSchedule.Immediate

  private fun filesLabel(stage: IntakeEntry): UiText {
    val count = stage.selectedFiles?.size ?: 0
    if (count == 0) return Res.string.intake_submit_no_files.text()
    return countLabel(count, items = false, stage.bytes)
  }

  private fun batchLabel(): UiText {
    val adding = entries.filter { it.addable }
    val count = adding.sumOf { it.linkCount }
    val only = adding.singleOrNull()?.takeIf { it.linkCount == 1 }
    return when {
      count == 0 -> Res.string.intake_submit_nothing.text()
      only != null -> {
        val name = middleEllipsis(only.name, MAX_LABEL_NAME)
        if (scheduled) {
          Res.string.intake_submit_schedule_name.text(name)
        } else {
          Res.string.intake_submit_download_name.text(name)
        }
      }
      // A torrent holds files of its own, so a batch with one counts items.
      else -> countLabel(count, items = adding.any { it.isTorrent }, adding.sumOf { it.bytes ?: 0 })
    }
  }

  // Scheduling says when, not how much; adding now says how much.
  private fun countLabel(count: Int, items: Boolean, bytes: Long?): UiText {
    val size = bytes?.takeIf { it > 0 && !scheduled }?.let(::sizeText)
    return when {
      scheduled && items -> Res.plurals.intake_submit_schedule_items.text(count)
      scheduled -> Res.plurals.intake_submit_schedule_files.text(count)
      size != null && items ->
        Res.plurals.intake_submit_download_items_size.text(count, count, size)
      size != null -> Res.plurals.intake_submit_download_files_size.text(count, count, size)
      items -> Res.plurals.intake_submit_download_items.text(count)
      else -> Res.plurals.intake_submit_download_files.text(count)
    }
  }

  /**
   * Speed, priority, start and connections as the Options pill sums them up; a retry offers no
   * start. Values changed from their defaults also show as chips that put them back.
   */
  val optionValues: List<IntakeOptionValue>
    get() = intakeOptionValues(
      speedLimit = speedLimit,
      priority = priority,
      schedule = schedule.takeIf { mode != IntakeMode.Retry },
      connections = connections,
      torrents = torrentsOnly,
      singleConnection = !torrentsOnly && (maxConnections ?: MAX_CONNECTIONS) <= 1,
      now = clock.now(),
      zone = TimeZone.currentSystemDefault(),
    )

  /** Puts [option] back to its default: no limit, Normal, now or Auto. */
  fun resetOption(option: IntakeOption) {
    when (option) {
      IntakeOption.Speed -> speedLimit = SpeedLimit.Unlimited
      IntakeOption.Priority -> priority = DownloadPriority.NORMAL
      IntakeOption.Start -> schedule = DownloadSchedule.Immediate
      IntakeOption.Connections -> connections = 0
    }
  }

  /** Whether the main button can be pressed. */
  val canSubmit: Boolean
    get() = !submitting && when (mode) {
      IntakeMode.Edit -> task != null
      IntakeMode.Retry -> task != null && entries.size == 1
      IntakeMode.Add -> allFailed || addableLinks > 0
    }

  /** Whether typed text would be lost by closing: more than one line. */
  val hasTypedInput: Boolean get() = text.text.trim().lines().size > 1

  /** Starts the session: picks the target, prefills the input and starts the checks. */
  internal fun start() {
    val devices = state.instances.value
    val seeds = request.seeds
    val prefill = (seeds.map { it.url } + request.text.ifBlank { null }).filterNotNull()
    val prefillKind = IntakeKind.of(
      LinkParser.parseIntake(prefill.joinToString("\n")).links().map { it.url },
    )
    val target = request.targetDeviceId?.let { id -> devices.firstOrNull { it.deviceId == id } }
      ?: state.lastTarget(prefillKind)
      ?: state.activeInstance.value
    this.target = target
    if (target != null) loadDefaults(target)
    seeds.forEach { seedsByUrl[it.url] = it }
    if (mode != IntakeMode.Add) bindTask()
    if (prefill.isNotEmpty()) text = TextFieldValue(prefill.joinToString("\n"))
    this.target?.let(::watch)
    reparse()
  }

  /** Replaces the input with [value]: a paste is read at once, typing after a short pause. */
  fun onTextChange(value: TextFieldValue) {
    val previous = text.text
    text = value
    if (value.text == previous) return
    fromClipboard = false
    parseJob?.cancel()
    val pasted = value.text.length - previous.length > PASTE_GROWTH
    if (pasted || value.text.isBlank()) {
      reparse()
    } else {
      parseJob = scope.launch {
        delay(TYPING_DEBOUNCE)
        reparse()
      }
    }
  }

  /**
   * Prefills the empty input with [clip] from the clipboard, selected, when it holds a link that
   * is not in Ketch and was not offered before. Returns whether it did.
   */
  fun offerClipboard(clip: String): Boolean {
    if (text.text.isNotEmpty() || mode != IntakeMode.Add) return false
    val trimmed = clip.trim()
    val hash = clipHash(trimmed)
    if (trimmed.isEmpty() || hash == state.appSettings.ui.lastClipHash) return false
    val links = LinkParser.parseIntake(trimmed.take(MAX_CLIP_CHARS)).links()
    if (links.isEmpty()) return false
    val detector = duplicateDetector()
    if (links.all { detector.find(it.url) != null }) return false
    rememberClip(hash)
    text = TextFieldValue(trimmed, TextRange(0, trimmed.length))
    fromClipboard = true
    reparse()
    return true
  }

  /**
   * Puts [clip], which the user asked to paste from the clipboard, after the input. Unlike
   * [offerClipboard] it takes a clip offered before.
   */
  fun pasteClipboard(clip: String) {
    val trimmed = clip.trim()
    if (trimmed.isEmpty() || mode != IntakeMode.Add) return
    rememberClip(clipHash(trimmed))
    val current = text.text.trimEnd()
    fromClipboard = current.isEmpty()
    replaceText(if (current.isEmpty()) trimmed else "$current\n$trimmed")
  }

  // The clipboard text the sheet was filled from, which neither it nor the next sheet offers.
  private fun rememberClip(hash: String) {
    state.appSettings.saveUi { it.copy(lastClipHash = hash) }
    filledClip = hash
  }

  // Puts updated in the input with the caret at its end, and reads it at once.
  private fun replaceText(updated: String) {
    text = TextFieldValue(updated, TextRange(updated.length))
    reparse()
  }

  /**
   * Whether the empty sheet offers to paste the clipboard text whose hash is [hash], `null` when
   * the platform cannot tell without reading it: not once the sheet was filled from that text,
   * or from any when it is unknown.
   */
  fun offersClipboard(hash: String?): Boolean {
    if (mode != IntakeMode.Add || text.text.isNotEmpty() || entries.isNotEmpty()) return false
    return if (hash == null) filledClip == null else hash != filledClip
  }

  /** Reads the input now when a typed change is still waiting to be read. */
  fun flush() {
    if (parseJob?.isActive != true) return
    parseJob?.cancel()
    reparse()
  }

  /**
   * Whether Discover can search for text that holds no link; before it is set up, the search
   * waits on its setup page.
   */
  val canDiscover: Boolean get() = state.aiSettings.supported

  /**
   * Closes the sheet and searches Discover for [query]: by default the text, when it holds no
   * link. Returns whether it did.
   */
  fun discover(query: String? = discoverQuery ?: text.text.trim()): Boolean {
    val search = query?.trim()
    if (!canDiscover || search.isNullOrEmpty()) return false
    state.closeAddDialog()
    state.openDiscover(DiscoverRequest(search))
    return true
  }

  /** Clears text that came from the clipboard. */
  fun dismissClipboard() {
    fromClipboard = false
    onTextChange(TextFieldValue())
  }

  /** Checks [entry] now; rows past the first [EAGER_RESOLVES] call this once they are shown. */
  fun request(entry: IntakeEntry) {
    if (entry.wanted) return
    entry.wanted = true
    launchResolve(entry)
  }

  /** Checks [entry] again, such as after a network failure. */
  fun retry(entry: IntakeEntry) {
    entry.wanted = true
    entry.addAnyway = false
    launchResolve(entry)
  }

  /** Checks every row with a problem again. */
  fun retryAll() {
    entries.filter { it.status is IntakeStatus.Problem }.forEach(::retry)
  }

  /** Keeps waiting for a magnet's file list after the timeout. */
  fun keepWaiting(entry: IntakeEntry) {
    if (entry.status is IntakeStatus.Problem && entry.job?.isActive == true) {
      entry.status = IntakeStatus.Checking(clock.now())
    } else {
      retry(entry)
    }
  }

  /** Removes [entry]: its link leaves the input. */
  fun remove(entry: IntakeEntry) {
    entry.job?.cancel()
    if (entry.source is IntakeSource.File) {
      fileEntries = fileEntries - entry
    } else {
      replaceText(replaceIntakeLink(text.text, entry.key, replacement = null))
    }
    if (torrentStage === entry) torrentStage = null
  }

  /** Signs [entry] in with [user] and [password] and checks it again. */
  fun signIn(entry: IntakeEntry, user: String, password: String) {
    val link = entry.source as? IntakeSource.Link ?: return
    val signed = IntakeItem.Link(link.url, link.headers).withCredentials(user, password)
    val seed = seedsByUrl[link.url]
    seedsByUrl[signed.url] = IntakeSeed(
      url = signed.url,
      fileName = seed?.fileName,
      headers = signed.headers,
      properties = seed?.properties.orEmpty(),
    )
    if (signed.url != link.url) {
      replaceText(replaceIntakeLink(text.text, link.url, signed.url))
    } else {
      reparse()
    }
    entries.firstOrNull { it.key == signed.url }?.let(::retry)
  }

  /**
   * Puts a cURL command copied from the browser in place of [entry]'s link, or after the input
   * when [entry] is `null`, so its cookies come along. Returns whether [clip] was a command.
   */
  fun pasteCurl(clip: String?, entry: IntakeEntry? = null): Boolean {
    val command = clip?.trim()
    if (command == null || !CurlParser.isCurl(command) || CurlParser.parse(command) == null) {
      notice = Res.string.intake_notice_no_curl.text()
      return false
    }
    notice = null
    val current = text.text
    val updated = if (entry != null && entry.source !is IntakeSource.File) {
      replaceIntakeLink(current, entry.key, command)
    } else {
      listOf(current.trimEnd(), command).filter { it.isNotEmpty() }.joinToString("\n")
    }
    replaceText(updated)
    return true
  }

  /**
   * Adds [files]: `.torrent` files as rows checked from their content, and lists of links such
   * as `.txt` or `.csv` files to the input.
   */
  fun addFiles(files: List<DroppedFile>) {
    if (mode != IntakeMode.Add) {
      notice = Res.string.intake_notice_close_first.text()
      return
    }
    val torrents = files.filter { it.name.endsWith(".torrent", ignoreCase = true) }
    torrents.forEach(::addFile)
    val lists = files.filter { LinkParser.isLinkList(it.name) }
    if (torrents.isEmpty() && lists.isEmpty() && files.isNotEmpty()) {
      notice = Res.string.intake_notice_unsupported_files.text()
      return
    }
    if (lists.isEmpty()) return
    scope.launch {
      val links = lists.flatMap { file ->
        val content = catchingUnlessCancelled { file.readBytes(MAX_LINK_LIST_BYTES) }
          .onFailure { e ->
            notice = e.message
              ?.let { Res.string.intake_notice_read_failed_reason.text(file.name, it) }
              ?: Res.string.intake_notice_read_failed.text(file.name)
          }
          .getOrNull() ?: return@flatMap emptyList()
        LinkParser.parseIntake(content.decodeToString(), file.name).links().map { it.url }
      }
      if (links.isEmpty()) {
        if (notice == null) notice = Res.string.intake_notice_no_links.text()
        return@launch
      }
      val updated = (listOf(text.text.trimEnd()) + links).filter { it.isNotEmpty() }
        .joinToString("\n")
      replaceText(updated)
    }
  }

  /** Adds one `.torrent` [file] as a row, unless it is a row already. */
  fun addFile(file: DroppedFile) {
    if (mode != IntakeMode.Add) return
    if (fileEntries.any { (it.source as IntakeSource.File).file === file }) return
    val entry = IntakeEntry(IntakeSource.File(file, "file:${fileCount++}:${file.name}"))
    fileEntries = fileEntries + entry
    followKind()
    request(entry)
  }

  /**
   * Sends downloads to [entry] instead, checking every row again there; the next adds of the
   * same [kind] go there too this session.
   */
  fun selectTarget(entry: InstanceEntry) {
    if (mode != IntakeMode.Add) return
    targetChosen = true
    state.rememberTarget(kind, entry)
    retarget(entry)
  }

  private fun retarget(entry: InstanceEntry) {
    if (entry == target) return
    target = entry
    targetStatus = null
    targetTasks = emptyList()
    loadDefaults(entry)
    watch(entry)
    for (row in entries) {
      row.job?.cancel()
      row.status = IntakeStatus.Waiting
      if (row.wanted) launchResolve(row)
    }
  }

  /** Opens or closes the Advanced section and remembers the choice. */
  fun updateAdvancedOpen(open: Boolean) {
    advancedOpen = open
    state.appSettings.saveUi { it.copy(intakeAdvancedOpen = open) }
  }

  /** Checks the links again with the changed Advanced headers, after a short pause. */
  fun onHeadersChanged() {
    headersJob?.cancel()
    headersJob = scope.launch {
      delay(TYPING_DEBOUNCE)
      entries.filter { it.wanted && it.source !is IntakeSource.File && !it.isMagnet }
        .forEach(::launchResolve)
    }
  }

  /** Pins [path] in [target]'s folder menu. */
  fun pinFolder(path: String) = updatePrefs { prefs ->
    if (path in prefs.favoriteFolders) prefs
    else prefs.copy(favoriteFolders = prefs.favoriteFolders + path)
  }

  /** Unpins [path] from [target]'s folder menu. */
  fun unpinFolder(path: String) = updatePrefs { prefs ->
    prefs.copy(favoriteFolders = prefs.favoriteFolders - path)
  }

  /** Asks before closing when typed text would be lost; returns whether the sheet may close. */
  fun requestClose(): Boolean {
    if (submitting) return false
    if (hasTypedInput && !confirmingClose) {
      confirmingClose = true
      return false
    }
    return true
  }

  /** Goes back to editing after [requestClose] asked. */
  fun keepEditing() {
    confirmingClose = false
  }

  /** Stops every check; the session is done. */
  internal fun cancel() {
    parseJob?.cancel()
    headersJob?.cancel()
    watchJob?.cancel()
    entries.forEach { it.job?.cancel() }
  }

  /** Adds the rows, retries the task or applies the options, as [mode] says. */
  fun submit(onDone: () -> Unit) {
    if (!canSubmit) return
    when {
      mode == IntakeMode.Edit -> applyToTask(onDone)
      mode == IntakeMode.Retry -> retryTask(onDone)
      allFailed -> retryAll()
      else -> add(onDone)
    }
  }

  /** Adds every row that failed its check anyway. */
  fun addAllAnyway(onDone: () -> Unit) {
    entries.forEach { it.addAnyway = true }
    add(onDone)
  }

  /** The device name downloads go to, as messages say it. */
  fun targetName(): UiText = target?.displayName ?: Res.string.device_any_in_sentence.text()

  private fun add(onDone: () -> Unit) {
    val target = target ?: return
    submitting = true
    scope.launch {
      // Links still being checked get a moment, so one that fails stays out like the others.
      val pending = entries.filter { it.checkPending }.mapNotNull { it.job }
      if (pending.isNotEmpty()) withTimeoutOrNull(SUBMIT_CHECK_WAIT) { pending.joinAll() }
      val rows = entries.filter { it.addable }
      val planned = rows.flatMap { entry -> requestsFor(entry).map { entry to it } }
      if (planned.isEmpty()) {
        // Every link failed meanwhile; the sheet stays open with their problems.
        submitting = false
        return@launch
      }
      // Duplicates left out on purpose are not offered for review.
      val skipped = entries.filter { !it.addable && (it.duplicate == null || it.downloadAgain) }
      val results = supervisorScope {
        planned.map { (entry, request) ->
          async {
            Triple(entry, request, catchingUnlessCancelled { target.instance.download(request) })
          }
        }.awaitAll()
      }
      results.forEach { (_, request, result) ->
        result.exceptionOrNull()?.let { e ->
          log.w { "Couldn't add ${redactUrl(request.url)}: ${e.describeCauses()}" }
        }
      }
      saveDefaults(target)
      if (results.any { it.third.isSuccess }) state.rememberTarget(kind, target)
      submitting = false
      onDone()
      reportAdded(target, results, skipped)
    }
  }

  private fun requestsFor(entry: IntakeEntry): List<DownloadRequest> {
    val resolved = entry.resolved
    val headers = effectiveHeaders(entry)
    val properties = when (val source = entry.source) {
      is IntakeSource.Link -> source.properties
      else -> emptyMap()
    }.let { if (TaskOrigin.PROPERTY in it) it else it + (TaskOrigin.PROPERTY to TaskOrigin.App.id) }
    val selection = entry.selectedFiles
    val selectedIds = if (entry.files.isEmpty() || selection == null ||
      selection.size == entry.files.size
    ) {
      emptySet()
    } else {
      selection
    }
    val name = entry.fileName.trim().ifEmpty { null }
      ?: (entry.source as? IntakeSource.Link)?.fileName
    val urls = when (val source = entry.source) {
      is IntakeSource.Link -> listOf(source.url)
      is IntakeSource.Range -> source.urls
      is IntakeSource.File -> listOfNotNull(resolved?.url)
    }
    return urls.mapIndexedNotNull { index, url ->
      try {
        DownloadRequest(
          url = url,
          destination = intakeDestination(
            folder = folder,
            name = name.takeIf { urls.size == 1 },
            separator = separator,
            torrent = entry.isTorrent,
          ),
          connections = connections,
          headers = headers,
          properties = properties,
          speedLimit = speedLimit,
          priority = priority,
          schedule = schedule,
          selectedFileIds = selectedIds,
          resolvedSource = resolved.takeIf { index == 0 },
        )
      } catch (e: IllegalArgumentException) {
        log.w { "Couldn't build a request for ${redactUrl(url)}: ${e.describeCauses()}" }
        null
      }
    }
  }

  private fun reportAdded(
    target: InstanceEntry,
    results: List<Triple<IntakeEntry, DownloadRequest, Result<DownloadTask>>>,
    skipped: List<IntakeEntry>,
  ) {
    val added = results.mapNotNull { it.third.getOrNull() }
    val failed = results.filter { it.third.isFailure }
    val review = (failed.map { it.first } + skipped).distinct()
    val failedUrls = failed.mapTo(HashSet()) { it.second.url }
    val reviewAction = if (review.isEmpty()) {
      null
    } else {
      MessageAction(Res.string.action_review.text()) { reopen(review, failedUrls) }
    }
    if (added.isEmpty()) {
      val error = failed.firstOrNull()?.third?.exceptionOrNull()
      val what = if (failed.size == 1) {
        verbatim(failed.single().first.name)
      } else {
        Res.plurals.count_downloads.text(failed.size)
      }
      state.messages.post(
        level = MessageLevel.Error,
        title = Res.string.intake_add_failed.text(what),
        detail = error?.message?.let(::verbatim),
        deviceId = target.deviceId,
        actions = listOfNotNull(reviewAction),
        cause = error,
      )
      return
    }
    if (target in state.shownInstances.value) showNewRows()
    state.announceAdded(added.map { TaskKey(target.deviceId, it.taskId) })
    val undo = state.undoAddAction(added)
    val single = added.singleOrNull()?.takeIf { review.isEmpty() }
    val key = single?.let { TaskKey(target.deviceId, it.taskId) }
    val show = MessageAction(Res.string.action_show.text()) { state.showOn(target, single) }
    val what = if (single != null) {
      verbatim(displayName(single.request))
    } else {
      Res.plurals.count_downloads.text(added.size)
    }
    val title = state.addedTitle(what, target, failed.size, left = skipped.sumOf { it.linkCount })
    state.messages.post(
      level = if (failed.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
      title = title,
      detail = failed.firstOrNull()?.third?.exceptionOrNull()?.message?.let(::verbatim),
      taskKey = key,
      deviceId = target.deviceId,
      actions = listOfNotNull(reviewAction ?: show, undo),
    )
  }

  /** Makes the Downloads list show the new rows: the All tab, without a search. */
  private fun showNewRows() {
    if (state.searchQuery.isNotBlank()) state.searchQuery = ""
    if (state.statusFilter != StatusFilter.All) state.showDownloads(StatusFilter.All)
  }

  /**
   * Opens the sheet again with [rows], to fix what failed or was left out. A range of which only
   * some links, [failedUrls], failed comes back as those links, since the others were added.
   */
  private fun reopen(rows: List<IntakeEntry>, failedUrls: Set<String>) {
    val seeds = rows.flatMap { row ->
      when (val source = row.source) {
        is IntakeSource.Link -> listOf(
          IntakeSeed(
            url = source.url,
            fileName = row.fileName.trim().ifEmpty { null } ?: source.fileName,
            headers = source.headers,
            properties = source.properties,
          ),
        )
        is IntakeSource.Range -> {
          val parts = source.urls.filter { it in failedUrls }
          if (parts.isEmpty() || parts.size == source.urls.size) {
            listOf(IntakeSeed(source.pattern, headers = source.headers))
          } else {
            parts.map { IntakeSeed(it, headers = source.headers) }
          }
        }
        is IntakeSource.File -> emptyList()
      }
    }
    val files = rows.mapNotNull { (it.source as? IntakeSource.File)?.file }
    val request = IntakeRequest(seeds = seeds, targetDeviceId = target?.deviceId)
    carry(request, files)
    state.openIntake(request)
  }

  private fun retryTask(onDone: () -> Unit) {
    val task = task ?: return
    val target = target ?: return
    val entry = entries.singleOrNull() ?: return
    val current = task.requestState.value
    val name = displayName(current, task.state.value)
    if (!startsOver) {
      val newConnections = connectionsFor(current)
      val failure = { device: UiText -> Res.string.intake_retry_failed.text(name, device) }
      state.runTaskCommand(task, "retry $name", failure) {
        if (speedLimit != current.speedLimit) setSpeedLimit(speedLimit)
        if (priority != current.priority) setPriority(priority)
        newConnections?.let { setConnections(it) }
        resume()
      }
      onDone()
      return
    }
    val request = requestsFor(entry).singleOrNull() ?: return
    submitting = true
    scope.launch {
      val result = catchingUnlessCancelled {
        task.remove(deleteFiles = true)
        target.instance.download(request)
      }
      submitting = false
      onDone()
      result.onSuccess { copy ->
        state.claimAdds(listOf(TaskKey(target.deviceId, copy.taskId)))
        state.messages.post(
          MessageLevel.Success,
          Res.string.intake_restarted.text(displayName(request)),
          deviceId = target.deviceId,
        )
      }.onFailure { e ->
        log.w { "Couldn't start taskId=${task.taskId} over: ${e.describeCauses()}" }
        state.messages.post(
          level = MessageLevel.Error,
          title = Res.string.intake_restart_failed.text(name, targetName()),
          detail = e.message?.let(::verbatim),
          deviceId = target.deviceId,
          cause = e,
        )
      }
    }
  }

  private fun applyToTask(onDone: () -> Unit) {
    val task = task ?: return
    val current = task.requestState.value
    val name = displayName(current, task.state.value)
    val speed = speedLimit
    val newPriority = priority
    val newConnections = connectionsFor(current)
    val newSchedule = schedule
    val wasScheduled = task.state.value as? DownloadState.Scheduled
    val failure = { device: UiText -> Res.string.intake_apply_failed.text(name, device) }
    state.runTaskCommand(task, "options $name", failure) {
      if (speed != current.speedLimit) setSpeedLimit(speed)
      if (newPriority != current.priority) setPriority(newPriority)
      newConnections?.let { setConnections(it) }
      if (newSchedule != (wasScheduled?.schedule ?: DownloadSchedule.Immediate)) {
        reschedule(newSchedule)
      }
    }
    onDone()
  }

  /**
   * Connections to set on the task downloading [current], or `null` to leave them. A device that
   * supports Auto gets 0. An older one cannot set a task back to Auto, so Auto gives a task with
   * its own count that device's default count; a torrent's peer limit, or a default not known
   * yet, is then left as it is.
   */
  private fun connectionsFor(current: DownloadRequest): Int? = when {
    connections == current.connections -> null
    connections > 0 -> connections
    KetchFeatures.AUTO_CONNECTIONS in targetStatus?.features.orEmpty() -> 0
    torrentsOnly -> null
    else -> autoConnections?.takeIf { it != current.connections }
  }

  private fun bindTask() {
    val key = request.editTask ?: request.retryOf ?: return
    val owner = state.instances.value.firstOrNull { it.deviceId == key.deviceId } ?: return
    val task = owner.instance.tasks.value.firstOrNull { it.taskId == key.taskId } ?: return
    this.task = task
    target = owner
    val current = task.requestState.value
    speedLimit = current.speedLimit
    priority = current.priority
    connections = current.connections
    schedule = (task.state.value as? DownloadState.Scheduled)?.schedule
      ?: DownloadSchedule.Immediate
    current.destination?.let { destination ->
      folder = folderOf(destination.value)
    }
    if (mode == IntakeMode.Retry) {
      // The task's headers move to Advanced, where they can be changed.
      headers.fill(current.headers)
      boundHeaders = headers.toMap()
      if (current.headers.isNotEmpty()) advancedOpen = true
      request.seeds.forEach { seedsByUrl[it.url] = it.copy(headers = emptyMap()) }
    }
  }

  private fun folderOf(destination: String): String? = when {
    Destination(destination).isName() -> null
    destination.startsWith(CONTENT_SCHEME, ignoreCase = true) -> destination
    Destination(destination).isDirectory() -> destination.trimEnd('/', '\\')
    else -> destination.substringBeforeLast(separatorIn(destination), "").ifEmpty { null }
  }

  private fun effectiveHeaders(entry: IntakeEntry): Map<String, String> {
    if (entry.isMagnet || entry.source is IntakeSource.File) return emptyMap()
    val own = when (val source = entry.source) {
      is IntakeSource.Link -> source.headers
      is IntakeSource.Range -> source.headers
      is IntakeSource.File -> emptyMap()
    }
    val typed = headers.toMap()
    val kept = own.filterKeys { name -> typed.keys.none { it.equals(name, ignoreCase = true) } }
    return kept + typed
  }

  private fun reparse() {
    val parsed = LinkParser.parseIntake(text.text)
    discoverQuery = (parsed.singleOrNull() as? IntakeItem.Discover)?.query
    val sources = parsed.mapNotNull { item ->
      when (item) {
        is IntakeItem.Link -> linkSource(item)
        is IntakeItem.Range ->
          IntakeSource.Range(item.pattern, item.urls, item.truncated, item.headers)
        is IntakeItem.Discover -> null
      }
    }
    val room = (MAX_INTAKE_ROWS - fileEntries.size).coerceAtLeast(0)
    val kept = sources.take(room)
    notice = Res.plurals.intake_notice_first_links.text(room).takeIf { kept.size < sources.size }
    val previous = textEntries.associateBy { it.key }
    val next = kept.map { source ->
      val existing = previous[source.key]
      when {
        existing == null -> IntakeEntry(source)
        existing.source != source -> existing.also {
          it.source = source
          if (it.wanted) launchResolve(it)
        }
        else -> existing
      }
    }
    val nextSet = next.toSet()
    previous.values.filter { it !in nextSet }.forEach { it.job?.cancel() }
    textEntries = next
    followKind()
    refreshDuplicates()
    next.take(EAGER_RESOLVES).forEach(::request)
  }

  // Until a target is chosen, the rows go where the last add of their kind went, else to the
  // active device.
  private fun followKind() {
    if (targetChosen || mode != IntakeMode.Add || entries.isEmpty()) return
    val wanted = state.lastTarget(kind) ?: state.activeInstance.value ?: return
    retarget(wanted)
  }

  private fun linkSource(item: IntakeItem.Link): IntakeSource.Link {
    val seed = seedsByUrl[item.url]
    return IntakeSource.Link(
      url = item.url,
      headers = item.headers + seed?.headers.orEmpty(),
      fileName = seed?.fileName,
      properties = seed?.properties.orEmpty(),
    )
  }

  private fun launchResolve(entry: IntakeEntry) {
    entry.job?.cancel()
    val target = target ?: return
    entry.status = IntakeStatus.Waiting
    entry.job = scope.launch {
      if (entry.isMagnet) {
        resolveOn(target.instance, entry)
      } else {
        limiter.withPermit { resolveOn(target.instance, entry) }
      }
    }
  }

  private suspend fun resolveOn(api: KetchApi, entry: IntakeEntry) {
    entry.status = IntakeStatus.Checking(clock.now())
    val result = coroutineScope {
      val call = async { catchingUnlessCancelled { resolveSource(api, entry) } }
      if (!entry.isMagnet) return@coroutineScope call.await()
      withTimeoutOrNull(MAGNET_METADATA_TIMEOUT) { call.await() } ?: run {
        entry.status = IntakeStatus.Problem(IntakeProblem.MagnetTimeout)
        call.await()
      }
    }
    result.onSuccess { resolved ->
      entry.status = IntakeStatus.Ready(resolved)
      if (resolved.files.size > 1 && entry.selectedFiles == null) {
        entry.selectedFiles = defaultTorrentSelection(resolved.files)
        // A torrent on its own goes straight to its files.
        if (entries.singleOrNull() === entry && torrentStage == null) torrentStage = entry
      }
      if (!submitting) entry.duplicate = duplicateOf(duplicateDetector(), entry)
    }.onFailure { e ->
      val file = (entry.source as? IntakeSource.File)?.file
      val url = entry.url ?: file?.name.orEmpty()
      log.d { "Couldn't check ${redactUrl(url)}: ${e.describeCauses()}" }
      entry.status = IntakeStatus.Problem(
        e.toIntakeProblem(url, discoverAvailable = state.aiSettings.available, file = file != null),
        e,
      )
    }
  }

  private suspend fun resolveSource(api: KetchApi, entry: IntakeEntry): ResolvedSource =
    when (val source = entry.source) {
      is IntakeSource.Link -> api.resolve(source.url, effectiveHeaders(entry))
      is IntakeSource.Range -> api.resolve(source.urls.first(), effectiveHeaders(entry))
      is IntakeSource.File -> resolveFile(api, source.file)
    }

  /**
   * Resolves a `.torrent` file's content. A dropped file is already being resolved by
   * [AppState.resolveDroppedFile], so that result is used when it is for the same device.
   */
  private suspend fun resolveFile(api: KetchApi, file: DroppedFile): ResolvedSource {
    if (file === state.droppedFile && api === state.droppedFileApi) {
      val (resolveState, current) = snapshotFlow { state.resolveState to state.droppedFile }
        .first { (resolveState, current) ->
          current !== file || resolveState is ResolveState.Resolved ||
            resolveState is ResolveState.Error
        }
      if (current === file) {
        when (resolveState) {
          is ResolveState.Resolved -> return resolveState.result
          is ResolveState.Error -> throw resolveState.cause
          else -> Unit
        }
      }
    }
    return api.resolveContent(file.readBytes(MAX_DROPPED_FILE_BYTES), file.name)
  }

  private fun watch(target: InstanceEntry) {
    watchJob?.cancel()
    targetTasks = target.instance.tasks.value
    watchJob = scope.launch {
      launch {
        targetStatus = catchingUnlessCancelled { target.instance.status() }
          .onFailure { e -> log.d { "No status from ${target.label}: ${e.describeCauses()}" } }
          .getOrNull()
      }
      state.visibleTasksOf(target).collect { tasks ->
        targetTasks = tasks
        if (!submitting) refreshDuplicates()
      }
    }
  }

  private fun duplicateDetector(): DuplicateDetector<DownloadTask> {
    val bound = task
    val tasks = targetTasks.filter { it !== bound }.sortedBy { it.createdAt }
    return DuplicateDetector(tasks) { it.requestState.value }
  }

  private fun refreshDuplicates() {
    val detector = duplicateDetector()
    for (entry in entries) entry.duplicate = duplicateOf(detector, entry)
  }

  private fun duplicateOf(detector: DuplicateDetector<DownloadTask>, entry: IntakeEntry) =
    when (val source = entry.source) {
      is IntakeSource.Link -> detector.find(source.url, entry.resolved)
      is IntakeSource.Range -> source.urls.firstNotNullOfOrNull { detector.find(it) }
      is IntakeSource.File -> entry.resolved?.let { detector.find(it.url, it) }
    }

  private fun loadDefaults(target: InstanceEntry) {
    if (mode != IntakeMode.Add) return
    val prefs = state.appSettings.ui.intake[target.deviceId] ?: IntakePreferences()
    folder = prefs.folder
    priority = prefs.priority
    connections = prefs.connections.coerceAtLeast(0)
  }

  private fun saveDefaults(target: InstanceEntry) {
    val folder = folder
    val priority = priority
    val connections = connections
    updatePrefs(target) { it.copy(folder = folder, priority = priority, connections = connections) }
  }

  private fun updatePrefs(
    target: InstanceEntry? = this.target,
    transform: (IntakePreferences) -> IntakePreferences,
  ) {
    val id = target?.deviceId ?: return
    state.appSettings.saveUi { ui ->
      val current = ui.intake[id] ?: IntakePreferences()
      ui.copy(intake = ui.intake + (id to transform(current)))
    }
  }

  private fun startOverWarning(): UiText {
    val done = task?.segments?.value?.sumOf { it.downloadedBytes } ?: 0
    return if (done > 0) {
      Res.string.intake_start_over_discard.text(sizeText(done))
    } else {
      Res.string.intake_start_over_new.text()
    }
  }

  internal companion object {
    /** Links checked at the same time. */
    const val PARALLEL_RESOLVES = 4

    /** Rows checked as soon as they appear; later ones wait until they are shown. */
    const val EAGER_RESOLVES = 50

    /** How long typing pauses before the input is read again. */
    val TYPING_DEBOUNCE = 500.milliseconds

    /** Characters a change must add at once to count as a paste, which is read at once. */
    const val PASTE_GROWTH = 8

    /** How long submitting waits for links still being checked before it adds them as they are. */
    val SUBMIT_CHECK_WAIT = 10.seconds

    /** Most connections the sheet offers. */
    const val MAX_CONNECTIONS = 32

    /** Clipboard text searched for links. */
    const val MAX_CLIP_CHARS = 64 * 1024
  }
}

/**
 * Turns [IntakeRequest]s into [IntakeSession]s for the add sheet, and keeps a session whose
 * torrent was left to load in the background.
 *
 * @param scope runs the checks and submits; it outlives one sheet.
 */
@Stable
class IntakeController(
  private val state: AppState,
  private val scope: CoroutineScope,
) {
  /** The session left to finish in the background, or `null`. */
  var background: IntakeSession? by mutableStateOf(null)
    private set

  private var resuming: IntakeSession? = null
  private var carriedFiles: Pair<IntakeRequest, List<DroppedFile>>? = null
  private var backgroundJob: Job? = null

  /** A session for [request]: the one coming back from the background, or a new one. */
  fun start(request: IntakeRequest): IntakeSession {
    resuming?.takeIf { it.request == request }?.let { session ->
      resuming = null
      session.inBackground = false
      return session
    }
    val session = IntakeSession(request, state, scope) { next, files ->
      carriedFiles = next to files
    }
    session.start()
    carriedFiles?.takeIf { it.first == request }?.second?.forEach(session::addFile)
    carriedFiles = null
    return session
  }

  /** Ends [session] when its sheet goes away, unless it went to the background. */
  fun release(session: IntakeSession) {
    if (!session.inBackground) session.cancel()
  }

  /**
   * Closes [session]'s sheet and keeps its checks running; once every torrent has its file
   * list, a toast offers to open the sheet again.
   */
  fun finishInBackground(session: IntakeSession) {
    background?.takeIf { it !== session }?.cancel()
    backgroundJob?.cancel()
    session.inBackground = true
    background = session
    state.closeAddDialog()
    backgroundJob = scope.launch {
      val torrents = snapshotFlow { session.entries.filter { it.isTorrent } }
        .first { rows -> rows.none { it.waitsForFiles || it.isTimedOut } }
      if (background !== session || !session.inBackground) return@launch
      val ready = torrents.firstOrNull { it.status is IntakeStatus.Ready }
      val name = (ready ?: torrents.firstOrNull())?.name
      val title = when {
        ready != null && name != null -> Res.string.intake_file_list_ready.text(name)
        ready != null -> Res.string.intake_file_list_ready_unnamed.text()
        name != null -> Res.string.intake_file_list_failed.text(name)
        else -> Res.string.intake_file_list_failed_unnamed.text()
      }
      val action = if (ready != null) Res.string.intake_choose_files else Res.string.action_review
      state.messages.post(
        level = if (ready != null) MessageLevel.Info else MessageLevel.Warning,
        title = title,
        actions = listOf(MessageAction(action.text()) { resume(session) }),
      )
    }
  }

  /** Opens [session]'s sheet again. */
  fun resume(session: IntakeSession) {
    if (background === session) background = null
    resuming = session
    state.openIntake(session.request)
  }
}

/**
 * Where a download is saved: in [folder] (the device default when `null`), as [name] when one
 * was typed. An Android `content://` folder takes no name, and torrents, which cannot write to
 * such a folder, use the device default instead.
 */
internal fun intakeDestination(
  folder: String?,
  name: String?,
  separator: String,
  torrent: Boolean,
): Destination? {
  val systemFolder = folder?.startsWith(CONTENT_SCHEME, ignoreCase = true) == true
  val directory = folder?.takeUnless { torrent && systemFolder }
  val file = name?.trim()?.takeIf { it.isNotEmpty() && !torrent }
  return when {
    directory == null -> file?.let(::Destination)
    systemFolder -> Destination(directory)
    file == null -> Destination(withSeparator(directory, separator))
    else -> Destination(withSeparator(directory, separator) + file)
  }
}

/**
 * Folders the downloads among [tasks] were saved in, newest first and at most five: the folder
 * of each finished file and each directory destination.
 */
internal fun recentFolders(tasks: List<DownloadTask>, separator: String): List<String> =
  tasks.sortedByDescending { it.createdAt }.mapNotNull { task ->
    val completed = task.state.value as? DownloadState.Completed
    val destination = task.requestState.value.destination?.value
    when {
      completed != null && !completed.outputPath.startsWith(CONTENT_SCHEME, ignoreCase = true) ->
        completed.outputPath.substringBeforeLast(separatorIn(completed.outputPath, separator), "")
      destination != null && Destination(destination).isDirectory() ->
        destination.trimEnd('/', '\\')
      else -> null
    }?.takeIf { it.isNotEmpty() }
  }.distinct().take(MAX_RECENT_FOLDERS)

/**
 * Short name of [folder] for the Save to pill: its last component, or the folder an Android
 * `content://` tree points to.
 */
fun folderLabel(folder: String): String {
  if (folder.startsWith(CONTENT_SCHEME, ignoreCase = true)) {
    val id = percentDecode(folder.trimEnd('/').substringAfterLast('/'))
    return id.substringAfterLast(':').substringAfterLast('/').ifEmpty { id }
  }
  val trimmed = folder.trimEnd('/', '\\')
  return trimmed.substringAfterLast('/').substringAfterLast('\\').ifEmpty { folder }
}

/** Whether [file] of a torrent is an extra skipped by default: `.nfo`, a tiny `.txt`, a sample. */
internal fun isTorrentExtra(file: SourceFile): Boolean {
  val path = file.name.lowercase()
  val name = path.substringAfterLast('/')
  return name.endsWith(".nfo") || "sample" in path ||
    (name.endsWith(".txt") && file.size in 0 until TINY_TEXT_BYTES)
}

/** The files of a torrent picked by default: all but the extras, or all when that is none. */
internal fun defaultTorrentSelection(files: List<SourceFile>): Set<String> {
  val kept = files.filterNot(::isTorrentExtra).ifEmpty { files }
  return kept.mapTo(LinkedHashSet()) { it.id }
}

/**
 * What happens once the links are added on a device with [config] that runs [tasks]: "Starts
 * now · 1 of 2 slots free · ≈ 4 min at current speed", "Queued · 2 ahead", "⚡ Urgent:
 * starts now and pauses debian-12.iso (Low)", "Waits for github.com · 8 per server" or "Starts
 * 01:00 tonight". Returns `null` when nothing is added or the device's queue is unknown.
 *
 * @param hosts the host of each link added.
 * @param bytes size of what is added, when known.
 */
internal fun intakeOutcome(
  hosts: List<String?>,
  priority: DownloadPriority,
  schedule: DownloadSchedule,
  config: DownloadConfig?,
  tasks: List<DownloadTask>,
  bytes: Long?,
  now: Instant,
  zone: TimeZone,
): UiText? {
  val count = hosts.size
  if (count == 0) return null
  if (schedule != DownloadSchedule.Immediate) return startTimeText(schedule, now, zone)
  if (config == null) return null
  val running = tasks.filter { it.state.value is DownloadState.Downloading }
  val slots = config.maxConcurrentDownloads
  val free = if (slots > 0) (slots - running.size).coerceAtLeast(0) else count
  val speed = running.sumOf { task ->
    (task.state.value as DownloadState.Downloading).progress.bytesPerSecond
  }
  val eta = if (bytes != null && speed > 0) {
    val seconds = bytes / speed
    if (seconds < 60) {
      Res.string.intake_outcome_eta_soon.text()
    } else {
      Res.string.intake_outcome_eta.text(approximateTime(seconds))
    }
  } else {
    null
  }
  if (priority == DownloadPriority.URGENT && free == 0 && running.isNotEmpty()) {
    val paused = running.minWith(
      compareBy<DownloadTask> { it.requestState.value.priority }.thenByDescending { it.createdAt },
    )
    val request = paused.requestState.value
    val name = displayName(request, paused.state.value)
    return Res.string.intake_outcome_urgent.text(name, priorityText(request.priority))
  }
  val slotsText = Res.plurals.intake_outcome_slots_free.text(slots, free, slots)
    .takeIf { slots > 0 }
  if (count == 1) {
    val host = hosts.single()
    val perHost = config.maxConnectionsPerHost
    if (host != null && perHost > 0 && free > 0) {
      val onHost = running.count { urlHost(it.requestState.value.url) == host }
      if (onHost >= perHost) return Res.string.intake_outcome_waits_for_host.text(host, perHost)
    }
    if (free > 0) {
      return listOfNotNull(Res.string.intake_outcome_starts_now.text(), slotsText, eta).joinText()
    }
    val ahead = tasks.count {
      it.state.value.waitsInQueue && it.requestState.value.priority >= priority
    }
    return if (ahead == 0) {
      Res.string.intake_outcome_queued_next.text()
    } else {
      Res.plurals.intake_outcome_queued_behind.text(ahead)
    }
  }
  val startNow = minOf(free, count)
  val queued = count - startNow
  return buildList {
    if (startNow > 0) {
      add(
        if (slotsText != null) {
          Res.plurals.intake_outcome_start_now_slots.text(startNow, startNow, slotsText)
        } else {
          Res.plurals.intake_outcome_start_now.text(startNow)
        },
      )
    }
    if (queued > 0) add(Res.plurals.intake_outcome_queued_count.text(queued))
    eta?.let(::add)
  }.joinText()
}

/**
 * [text] with the link or range whose row key is [key] replaced by [replacement], or removed
 * when it is `null`: a line holding only that link, or a whole cURL command, is replaced; in a
 * line with other words only the word is.
 */
internal fun replaceIntakeLink(text: String, key: String, replacement: String?): String {
  val lines = text.replace("\r\n", "\n").split('\n')
  val output = mutableListOf<String>()
  var replaced = false
  var i = 0
  while (i < lines.size) {
    val line = lines[i]
    if (CurlParser.isCurl(line)) {
      var end = i
      var command = line
      while (end < lines.lastIndex && CurlParser.isIncomplete(command)) {
        end++
        command += "\n" + lines[end]
      }
      if (!replaced && key in keysIn(command)) {
        replacement?.let(output::add)
        replaced = true
      } else {
        output += lines.subList(i, end + 1)
      }
      i = end + 1
      continue
    }
    val keys = keysIn(line)
    if (replaced || key !in keys) {
      output += line
    } else if (keys.size == 1) {
      replacement?.let(output::add)
      replaced = true
    } else {
      val words = WORD.findAll(line).toList()
      val match = words.firstOrNull { key in keysIn(it.value) }
      if (match == null) {
        output += line
      } else {
        val edited = line.replaceRange(match.range, replacement.orEmpty())
        output += edited.replace(SPACES, " ").trim()
        replaced = true
      }
    }
    i++
  }
  return output.filterIndexed { index, value ->
    value.isNotBlank() || (index > 0 && index < output.lastIndex)
  }.joinToString("\n")
}

private fun keysIn(segment: String): List<String> =
  LinkParser.parseIntake(segment).mapNotNull { item ->
    when (item) {
      is IntakeItem.Link -> item.url
      is IntakeItem.Range -> "range:${item.pattern}"
      is IntakeItem.Discover -> null
    }
  }

/** "4 min" or "2 h 10 min" for [seconds], rounded up to the minute. */
internal fun approximateTime(seconds: Long): UiText {
  val minutes = (seconds + 59) / 60
  return when {
    minutes < 60 -> Res.string.duration_minutes.text(minutes)
    minutes % 60 == 0L -> Res.string.intake_time_hours.text(minutes / 60)
    else -> Res.string.intake_time_hours_minutes.text(minutes / 60, minutes % 60)
  }
}

/** Short hash of clipboard text, remembered so the same text is offered only once. */
internal fun clipHash(text: String): String = text.hashCode().toUInt().toString(HASH_RADIX)

private fun withSeparator(directory: String, separator: String): String =
  if (directory.endsWith('/') || directory.endsWith('\\')) directory else directory + separator

/** The separator [path] uses: a backslash when it has one and no slash, else [fallback]. */
private fun separatorIn(path: String, fallback: String = "/"): String = when {
  '\\' in path && '/' !in path -> "\\"
  '/' in path -> "/"
  else -> fallback
}

private val WORD = Regex("\\S+")
private val SPACES = Regex("[ \\t]{2,}")
private const val CONTENT_SCHEME = "content://"
private const val TORRENT_SOURCE = "torrent"
private const val MAX_RECENT_FOLDERS = 5
private const val TINY_TEXT_BYTES = 1024L
private const val HASH_RADIX = 36

/** Longest file name the main button shows before shortening it in the middle. */
private const val MAX_LABEL_NAME = 36

/**
 * What to do with a link on the clipboard: the setting, else fill on desktop, else suggest, as
 * the add sheet reads it.
 */
internal val AppSettingsController.clipboardMode: ClipboardMode
  get() = ui.clipboardMode ?: if (isMobilePlatform || KeyboardPlatform.current.isWeb) {
    ClipboardMode.Suggest
  } else {
    ClipboardMode.Fill
  }
