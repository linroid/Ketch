package com.linroid.ketch.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SpeedScheduler
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.formatEta
import com.linroid.ketch.config.SpeedRule
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * One quick choice of a start-time menu.
 *
 * @property label menu text, such as "Tonight 01:00".
 * @property schedule what choosing it schedules.
 */
data class StartTimeOption(
  val label: String,
  val schedule: DownloadSchedule,
)

/** The first moment after [now] at which the clock in [zone] shows [time]. */
fun nextLocalTime(now: Instant, zone: TimeZone, time: LocalTime): Instant {
  val today = now.toLocalDateTime(zone).date
  val candidate = today.atTime(time).toInstant(zone)
  if (candidate > now) return candidate
  return today.plus(1, DateTimeUnit.DAY).atTime(time).toInstant(zone)
}

/**
 * The quick choices of a start-time menu: Start now, In 1 hour, Tonight 01:00, Tomorrow 08:00
 * and, when [offPeak] is known, Off-peak. Every timed choice is a [DownloadSchedule.AtTime] in
 * [zone], never a delay, so it survives restarts unchanged.
 */
fun startTimeOptions(
  now: Instant,
  zone: TimeZone,
  offPeak: Instant? = null,
): List<StartTimeOption> {
  val tomorrow = now.toLocalDateTime(zone).date.plus(1, DateTimeUnit.DAY)
  return listOfNotNull(
    StartTimeOption("Start now", DownloadSchedule.Immediate),
    StartTimeOption("In 1 hour", DownloadSchedule.AtTime(now + 1.hours)),
    StartTimeOption("Tonight 01:00", DownloadSchedule.AtTime(nextLocalTime(now, zone, Night))),
    StartTimeOption(
      "Tomorrow 08:00",
      DownloadSchedule.AtTime(tomorrow.atTime(Morning).toInstant(zone)),
    ),
    offPeak?.let {
      val at = it.toLocalDateTime(zone)
      StartTimeOption("Off-peak · ${clock(at)}", DownloadSchedule.AtTime(it))
    },
  )
}

/**
 * When the Slow lane of the Auto [rules] next turns off, if it is on at [now]; `null` when it
 * is off already, since starting now is then off-peak.
 */
fun offPeakStart(
  rules: List<SpeedRule>,
  now: Instant,
  scheduler: SpeedScheduler = SpeedScheduler(),
): Instant? {
  val window = scheduler.evaluate(rules, now)
  return if (window.slowLane) window.until else null
}

/**
 * Short label of [schedule] for a picker: "Now", "Starts 01:00 tonight", "Starts 14:00 today",
 * "Starts tomorrow 08:00", "Starts Fri 08:00" within a week, or "Starts Oct 12 08:00".
 */
fun startTimeLabel(schedule: DownloadSchedule, now: Instant, zone: TimeZone): String {
  val at = when (schedule) {
    DownloadSchedule.Immediate -> return "Now"
    is DownloadSchedule.AfterDelay -> {
      return "Starts after ${formatEta(schedule.delay.inWholeSeconds)}"
    }
    is DownloadSchedule.AtTime -> schedule.startAt
  }
  if (at <= now) return "Now"
  val start = at.toLocalDateTime(zone)
  val today = now.toLocalDateTime(zone).date
  val tomorrow = today.plus(1, DateTimeUnit.DAY)
  val night = start.hour < NIGHT_ENDS_HOUR
  val time = clock(start)
  return when {
    start.date == today && (night || start.hour >= EVENING_HOUR) -> "Starts $time tonight"
    start.date == today -> "Starts $time today"
    start.date == tomorrow && night -> "Starts $time tonight"
    start.date == tomorrow -> "Starts tomorrow $time"
    start.date < today.plus(WEEK_DAYS, DateTimeUnit.DAY) -> {
      "Starts ${WEEKDAYS[start.dayOfWeek.ordinal]} $time"
    }
    else -> "Starts ${MONTHS[start.month.ordinal]} ${start.date.day} $time"
  }
}

/** "in 3h 12m" until [at], or `null` once it has passed. */
fun startCountdown(at: Instant, now: Instant): String? {
  val seconds = (at - now).inWholeSeconds
  return if (seconds > 0) "in ${formatEta(seconds)}" else null
}

/**
 * A chip that shows when a download starts and opens [StartTimeMenu] to change it.
 *
 * @param offPeak when the Slow lane of the Auto rules ends, offered as Off-peak; see
 *   [offPeakStart].
 * @param disabledReason why it is disabled, shown as its tooltip.
 */
@Composable
fun StartTimePicker(
  value: DownloadSchedule,
  onSelect: (DownloadSchedule) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  offPeak: Instant? = null,
  pending: Boolean = false,
  disabledReason: String? = null,
) {
  var expanded by remember { mutableStateOf(false) }
  val label = startTimeLabel(value, LocalClock.current.now(), TimeZone.currentSystemDefault())
  val chip = @Composable { chipModifier: Modifier ->
    Box(chipModifier) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        KetchChip(
          label = label,
          selected = false,
          enabled = enabled,
          trailingIcon = KetchIcon.ChevronDown,
          onClick = { expanded = true },
        )
        if (pending) KetchSpinner(Modifier.padding(start = KetchTheme.spacing.s2))
      }
      StartTimeMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        value = value,
        onSelect = onSelect,
        offPeak = offPeak,
      )
    }
  }
  if (!enabled && disabledReason != null) {
    KetchTooltip(text = disabledReason, modifier = modifier) { chip(Modifier) }
  } else {
    chip(modifier)
  }
}

/**
 * The start-time menu: the [startTimeOptions], "Pick date & time…", which asks for a date and
 * then a time, and Clear while a time is set. Place it next to its anchor like [KetchMenu].
 */
@Composable
fun StartTimeMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  value: DownloadSchedule,
  onSelect: (DownloadSchedule) -> Unit,
  offPeak: Instant? = null,
) {
  var picking by remember { mutableStateOf(false) }
  val now = LocalClock.current.now()
  val zone = TimeZone.currentSystemDefault()
  KetchMenu(expanded = expanded, onDismissRequest = onDismissRequest, title = "Start") {
    startTimeOptions(now, zone, offPeak).forEach { option ->
      item(
        label = option.label,
        onClick = { onSelect(option.schedule) },
        checked = option.schedule == value,
      )
    }
    item(label = "Pick date & time…", onClick = { picking = true })
    if (value != DownloadSchedule.Immediate) {
      divider()
      item(label = "Clear", onClick = { onSelect(DownloadSchedule.Immediate) })
    }
  }
  if (picking) {
    StartTimeDialog(
      zone = zone,
      onPicked = {
        picking = false
        onSelect(DownloadSchedule.AtTime(it))
      },
      onCancel = { picking = false },
    )
  }
}

/**
 * "Pick date & time…": asks for a date from today on, then a time, and reports the moment in
 * [zone]. A moment that has already passed cannot be chosen. Row menus open it from their Start
 * later submenu.
 *
 * @param onPicked receives the chosen moment, which is in the future.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartTimeDialog(
  onPicked: (Instant) -> Unit,
  onCancel: () -> Unit,
  zone: TimeZone = TimeZone.currentSystemDefault(),
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.dialog
  val clock = LocalClock.current
  val now = remember { clock.now() }
  val today = remember(zone) { now.toLocalDateTime(zone).date }
  var date by remember { mutableStateOf<LocalDate?>(null) }
  val picked = date
  if (picked == null) {
    val todayMillis = today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val state = rememberDatePickerState(
      initialSelectedDateMillis = todayMillis,
      yearRange = today.year..today.year + 1,
      selectableDates = remember(todayMillis) {
        object : SelectableDates {
          override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMillis
        }
      },
    )
    val pickerColors = DatePickerDefaults.colors(containerColor = colors.surfaceRaised)
    DatePickerDialog(
      onDismissRequest = onCancel,
      shape = shape,
      colors = pickerColors,
      confirmButton = {
        KetchButton(
          text = "Next",
          enabled = state.selectedDateMillis != null,
          onClick = {
            val millis = state.selectedDateMillis ?: return@KetchButton
            date = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
          },
        )
      },
      dismissButton = {
        KetchButton(text = "Cancel", variant = KetchButtonVariant.Ghost, onClick = onCancel)
      },
    ) {
      DatePicker(state = state, colors = pickerColors)
    }
  } else {
    val next = now.toLocalDateTime(zone)
    val state = rememberTimePickerState(initialHour = (next.hour + 1) % 24, initialMinute = 0)
    val at = LocalDateTime(picked, LocalTime(state.hour, state.minute)).toInstant(zone)
    val passed = at <= clock.now()
    TimePickerDialog(
      onDismissRequest = onCancel,
      shape = shape,
      containerColor = colors.surfaceRaised,
      title = { Text("Start at", style = KetchTheme.typography.titleL) },
      confirmButton = {
        KetchButton(
          text = "Schedule",
          enabled = !passed,
          tooltip = if (passed) "That time has already passed" else null,
          onClick = { if (at > clock.now()) onPicked(at) },
        )
      },
      dismissButton = {
        KetchButton(text = "Back", variant = KetchButtonVariant.Ghost, onClick = { date = null })
      },
    ) {
      TimePicker(state = state)
    }
  }
}

private fun clock(time: LocalDateTime): String =
  "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

private val Night = LocalTime(1, 0)
private val Morning = LocalTime(8, 0)
private const val NIGHT_ENDS_HOUR = 6
private const val EVENING_HOUR = 18
private const val WEEK_DAYS = 7
private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MONTHS = listOf(
  "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)
