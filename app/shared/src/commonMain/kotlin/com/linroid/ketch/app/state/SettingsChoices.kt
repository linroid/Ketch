package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isName
import com.linroid.ketch.app.util.percentDecode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.Weekday
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Unit a custom speed limit is typed in. */
enum class SpeedUnit(val label: String, val bytes: Long) {
  KB("KB/s", 1024L),
  MB("MB/s", 1024L * 1024L),
}

/**
 * [presets] plus [current], so a value set in the config file by hand
 * still shows up as a choice. Sorted ascending, with `0` — which the
 * download settings use for "no limit" — last.
 */
fun countChoices(presets: List<Int>, current: Int): List<Int> =
  (presets + current).distinct().sortedWith(compareBy({ it == 0 }, { it }))

/** "Unlimited", "512 KB/s", "2 MB/s" or "1.5 MB/s". */
fun formatSpeedLimit(limit: SpeedLimit): String {
  if (limit.isUnlimited) return "Unlimited"
  val unit = preferredUnit(limit)
  return "${formatSpeedAmount(limit, unit)} ${unit.label}"
}

/** MB/s from 1 MB/s up, KB/s below. */
fun preferredUnit(limit: SpeedLimit): SpeedUnit =
  if (limit.bytesPerSecond >= SpeedUnit.MB.bytes) SpeedUnit.MB else SpeedUnit.KB

/** [limit] in [unit], with up to two decimals: "2", "1.5", "0.25". */
fun formatSpeedAmount(limit: SpeedLimit, unit: SpeedUnit): String {
  val hundredths = (limit.bytesPerSecond * 100 + unit.bytes / 2) / unit.bytes
  val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
  return if (fraction.isEmpty()) "${hundredths / 100}" else "${hundredths / 100}.$fraction"
}

/**
 * Reads a typed custom limit such as "750" or "1.5", or `null` when
 * [amount] is not a positive number.
 */
fun parseSpeedLimit(amount: String, unit: SpeedUnit): SpeedLimit? {
  val value = amount.trim().toDoubleOrNull() ?: return null
  if (value.isNaN() || value <= 0.0 || value > 1_000_000.0) return null
  val bytes = (value * unit.bytes).toLong()
  return if (bytes > 0) SpeedLimit.of(bytes) else null
}

/**
 * [presets] plus [current], so a limit set elsewhere shows up as a chip of its own rather than
 * as a custom amount; unlimited first, then slowest first.
 */
fun speedChoices(presets: List<SpeedLimit>, current: SpeedLimit): List<SpeedLimit> =
  (presets + current).distinct().sortedBy { it.bytesPerSecond }

/**
 * Slow lane speeds offered as chips, slowest first: a few fixed ones and the [suggested] one,
 * so choosing the suggestion is one click.
 */
fun slowLanePresets(suggested: SpeedLimit): List<SpeedLimit> =
  (SlowLaneSpeeds + suggested).filterNot { it.isUnlimited }.distinct()
    .sortedBy { it.bytesPerSecond }

private val SlowLaneSpeeds = listOf(
  SpeedLimit.kbps(256),
  SpeedLimit.kbps(512),
  SpeedLimit.mbps(1),
  SpeedLimit.mbps(2),
  SpeedLimit.mbps(5),
)

/** Why [text] is not a usable TCP port, or `null` when it is. */
fun portError(text: String): String? {
  val port = text.trim().toIntOrNull()
  return if (port == null || port !in 1..65535) "Enter a port from 1 to 65535." else null
}

/** The rule "Add rule" starts from: working days, 09:00 to 18:00. */
fun newSpeedRule(): SpeedRule = SpeedRule(
  days = Weekday.entries.take(WORK_DAYS).toSet(),
  start = "09:00",
  end = "18:00",
)

/** Whether a rule on [days] starts on [day]; no days means every day. */
fun ruleIncludes(days: Set<Weekday>, day: Weekday): Boolean = days.isEmpty() || day in days

/**
 * [days] with [day] switched on or off. Every day is stored as no days, as [SpeedRule] reads it,
 * and the last day cannot be switched off, since no days would mean every day.
 */
fun toggleRuleDay(days: Set<Weekday>, day: Weekday): Set<Weekday> {
  val current = days.ifEmpty { Weekday.entries.toSet() }
  val next = if (day in current) current - day else current + day
  return when {
    next.isEmpty() -> days
    next.size == Weekday.entries.size -> emptySet()
    else -> next
  }
}

/** "Every day", "Weekdays", "Weekends" or the days in order, such as "Mon, Wed, Fri". */
fun ruleDaysLabel(days: Set<Weekday>): String {
  val workdays = Weekday.entries.take(WORK_DAYS).toSet()
  return when {
    days.isEmpty() || days.size == Weekday.entries.size -> "Every day"
    days == workdays -> "Weekdays"
    days == Weekday.entries.toSet() - workdays -> "Weekends"
    else -> Weekday.entries.filter { it in days }.joinToString(", ") { it.shortName }
  }
}

/** "Mon" to "Sun". */
val Weekday.shortName: String
  get() = name.take(SHORT_DAY_LENGTH)

/**
 * A rule time typed as "9", "9:30", "930" or "09:30", as the `HH:MM` that [SpeedRule] keeps;
 * `null` when it is not a time of day.
 */
fun normalizeRuleTime(text: String): String? {
  val trimmed = text.trim()
  val (hourText, minuteText) = when {
    ':' in trimmed -> trimmed.substringBefore(':') to trimmed.substringAfter(':')
    trimmed.length <= 2 -> trimmed to "0"
    trimmed.length in 3..4 -> trimmed.dropLast(2) to trimmed.takeLast(2)
    else -> return null
  }
  if (hourText.isEmpty() || minuteText.isEmpty()) return null
  if (!hourText.all(Char::isDigit) || !minuteText.all(Char::isDigit)) return null
  val hour = hourText.toInt()
  val minute = minuteText.toInt()
  if (hour !in 0..23 || minute !in 0..59 || minuteText.length > 2) return null
  return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

/**
 * What Auto mode does now: "Slow lane on until 18:00", "Full speed until Fri 09:00", or
 * "Slow lane on all week" when the rules never let it go.
 *
 * @param mode the Auto mode in effect, from [SpeedModeController.mode].
 */
fun autoModeSummary(mode: SpeedMode.Auto, now: Instant, zone: TimeZone): String {
  val until = mode.until
  val state = if (mode.slowLane) "Slow lane on" else "Full speed"
  return when {
    until != null -> "$state until ${changeTime(until, now, zone)}"
    mode.slowLane -> "Slow lane on all week"
    else -> "Full speed, no rule starts this week"
  }
}

// "18:00" today, "tomorrow 09:00", or "Fri 09:00" later in the week.
private fun changeTime(at: Instant, now: Instant, zone: TimeZone): String {
  val time = at.toLocalDateTime(zone)
  val today = now.toLocalDateTime(zone).date
  return when (time.date) {
    today -> clock(time)
    today.plus(1, DateTimeUnit.DAY) -> "tomorrow ${clock(time)}"
    else -> "${Weekday.entries[time.dayOfWeek.ordinal].shortName} ${clock(time)}"
  }
}

private fun clock(time: LocalDateTime): String =
  "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

/**
 * Whether [path] lies in Android's app-private storage (`/Android/data`), whose files other apps
 * cannot open.
 */
fun isAppPrivateFolder(path: String): Boolean =
  path.contains("/Android/data/") || path.trimEnd('/').endsWith("/Android/data")

/** Whether [path] is a folder chosen through Android's document picker, a `content://` URI. */
fun isDocumentTree(path: String): Boolean = path.startsWith("content://")

/**
 * Short name of a download folder: its last segment, such as "Downloads", or for a document tree
 * URI the folder it names, such as "Download" for `…/tree/primary%3ADownload`.
 */
fun folderName(path: String): String {
  if (isDocumentTree(path)) {
    val document = percentDecode(path.substringAfterLast('/'))
    val folder = document.substringAfter(':').trimEnd('/').substringAfterLast('/')
    return folder.ifEmpty { "Internal storage" }
  }
  val trimmed = path.trimEnd('/', '\\')
  return trimmed.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
}

/**
 * Folders the downloads in [tasks] were saved to, newest first and at most [limit] of them,
 * leaving out the ones in [exclude]. Paths are given without a trailing separator.
 */
fun recentDownloadFolders(
  tasks: List<DownloadTask>,
  exclude: Collection<String> = emptyList(),
  limit: Int = RECENT_FOLDERS,
): List<String> {
  val excluded = exclude.map(::folderKey).toSet()
  return tasks.sortedByDescending { it.createdAt }
    .mapNotNull(::folderOf)
    .map(::folderKey)
    .distinct()
    .filterNot { it in excluded }
    .take(limit)
}

private fun folderOf(task: DownloadTask): String? {
  val output = (task.state.value as? DownloadState.Completed)?.outputPath
  if (output != null && !isDocumentTree(output)) return parentFolder(output)
  val destination = task.request.destination ?: return null
  return when {
    destination.isDirectory() -> destination.value
    destination.isName() || isDocumentTree(destination.value) -> null
    else -> parentFolder(destination.value)
  }
}

private fun parentFolder(path: String): String? {
  val end = path.trimEnd('/', '\\').lastIndexOfAny(charArrayOf('/', '\\'))
  return if (end > 0) path.substring(0, end) else null
}

/**
 * Whether Settings offers to save downloads to the device's [default] folder instead of the
 * [chosen] one. A folder chosen by path can be the default itself; a default that is unknown,
 * such as from an older server, or app-private storage, which Settings warns about, is not offered.
 */
fun offersDefaultFolder(chosen: String?, default: String?): Boolean =
  chosen != null && default != null && !isSameFolder(chosen, default) &&
    !isAppPrivateFolder(default)

/** Whether [path] and [other] name the same folder, ignoring a trailing separator. */
fun isSameFolder(path: String, other: String?): Boolean =
  other != null && folderKey(path) == folderKey(other)

private fun folderKey(path: String): String = path.trimEnd('/', '\\').ifEmpty { path }

/**
 * [text] with its middle replaced by "…" until [fits] accepts it, keeping more of the end, where
 * a path names its folder. Returns [text] when it fits as it is, and "…" when nothing else does.
 */
fun elideMiddle(text: String, fits: (String) -> Boolean): String {
  if (fits(text)) return text
  fun keep(count: Int): String {
    val head = count * 2 / 5
    return text.take(head) + "…" + text.takeLast(count - head)
  }
  var low = 0
  var high = text.length - 1
  while (low < high) {
    val mid = (low + high + 1) / 2
    if (fits(keep(mid))) low = mid else high = mid - 1
  }
  return keep(low)
}

/** Host names typed into one field, separated by commas, spaces or new lines. */
fun parseHostList(text: String): List<String> =
  text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

private const val RECENT_FOLDERS = 3
private const val WORK_DAYS = 5
private const val SHORT_DAY_LENGTH = 3
