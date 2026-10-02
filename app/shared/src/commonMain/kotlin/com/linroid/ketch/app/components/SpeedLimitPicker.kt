package com.linroid.ketch.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.formatSpeedAmount
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.parseSpeedLimit
import com.linroid.ketch.app.state.preferredUnit
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Limits [SpeedLimitPicker] offers as chips, before Custom…. */
val SpeedLimitPickerPresets: List<SpeedLimit> = listOf(
  SpeedLimit.Unlimited,
  SpeedLimit.kbps(512),
  SpeedLimit.mbps(1),
  SpeedLimit.mbps(2),
  SpeedLimit.mbps(5),
  SpeedLimit.mbps(10),
)

/**
 * Reads a typed speed limit: "500k", "2m", "1.5 MB/s", "unlimited", or a bare number in
 * [defaultUnit]. Decimals may use a point or a comma. Returns `null` for anything else,
 * including 0.
 */
fun parseSpeedInput(text: String, defaultUnit: SpeedUnit): SpeedLimit? {
  val compact = text.lowercase().filterNot { it.isWhitespace() }.replace(',', '.')
  if (compact == "unlimited") return SpeedLimit.Unlimited
  val amount = compact.removeSuffix("/s").removeSuffix("ps").removeSuffix("b")
  return when {
    amount.endsWith("k") -> parseSpeedLimit(amount.dropLast(1), SpeedUnit.KB)
    amount.endsWith("m") -> parseSpeedLimit(amount.dropLast(1), SpeedUnit.MB)
    else -> parseSpeedLimit(amount, defaultUnit)
  }
}

/**
 * The caption naming the limit that caps a download whose own limit is [own] while the global
 * limit called [globalName] is [global], such as "Slow lane 1 MB/s applies to all downloads";
 * `null` when its own limit is the one that applies.
 */
fun winningLimitCaption(own: SpeedLimit, global: SpeedLimit, globalName: String): String? {
  if (global.isUnlimited) return null
  if (!own.isUnlimited && own.bytesPerSecond <= global.bytesPerSecond) return null
  return "$globalName ${formatSpeedLimit(global)} applies to all downloads"
}

/**
 * Picks a speed limit from preset chips or a custom field.
 *
 * The chip that looks selected follows [value], the limit currently requested, so it never
 * drifts from the task. The custom field takes "500k", "2m" or "1.5" in the unit shown next to
 * it, and commits on ↩, when the focus leaves the picker, or 600 ms after the last keystroke,
 * never once per keystroke. Clicking a chip or the unit, which takes the focus from the field,
 * does not commit the typed value first. While [pending] the control shows a spinner and keeps
 * showing the limit just chosen; once the command ends it follows [value] again, so a failure
 * shows the old limit.
 *
 * @param onCommit applies a newly chosen limit.
 * @param caption names the limit that wins when another one is lower; see
 *   [winningLimitCaption].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedLimitPicker(
  value: SpeedLimit,
  onCommit: (SpeedLimit) -> Unit,
  modifier: Modifier = Modifier,
  presets: List<SpeedLimit> = SpeedLimitPickerPresets,
  caption: String? = null,
  enabled: Boolean = true,
  pending: Boolean = false,
) {
  val spacing = KetchTheme.spacing
  var requested by remember { mutableStateOf<SpeedLimit?>(null) }
  LaunchedEffect(value, pending, requested) {
    val chosen = requested ?: return@LaunchedEffect
    if (value == chosen) {
      requested = null
    } else if (!pending) {
      // The command ended without the limit changing, or the caller tracks no command.
      delay(SettleTimeout)
      requested = null
    }
  }
  val shown = requested ?: value
  val currentValue by rememberUpdatedState(value)
  val currentOnCommit by rememberUpdatedState(onCommit)
  val commit: (SpeedLimit) -> Unit = { limit ->
    if (limit != (requested ?: currentValue)) {
      requested = limit
      currentOnCommit(limit)
    }
  }
  val scope = rememberCoroutineScope()
  val debounce = remember(scope) {
    DebouncedCommit<SpeedLimit>(scope, CustomCommitDelay) { commit(it) }
  }
  DisposableEffect(debounce) { onDispose { debounce.flush() } }

  val isPreset = shown in presets
  var customOpen by remember { mutableStateOf(!isPreset) }
  var unit by remember { mutableStateOf(if (isPreset) SpeedUnit.MB else preferredUnit(shown)) }
  var text by remember { mutableStateOf(if (isPreset) "" else customText(shown)) }
  var fieldFocused by remember { mutableStateOf(false) }
  LaunchedEffect(shown) {
    if (!fieldFocused && shown !in presets) {
      unit = preferredUnit(shown)
      text = customText(shown)
    }
  }
  val field = remember { FocusRequester() }
  var focusField by remember { mutableStateOf(false) }
  LaunchedEffect(focusField) {
    if (focusField) {
      field.requestFocus()
      focusField = false
    }
  }

  var pickerFocused by remember { mutableStateOf(false) }
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.onFocusChanged {
      if (pickerFocused && !it.hasFocus) debounce.flush()
      pickerFocused = it.hasFocus
    },
  ) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      presets.forEach { preset ->
        KetchChip(
          label = formatSpeedLimit(preset),
          selected = shown == preset,
          enabled = enabled,
          onClick = {
            debounce.cancel()
            customOpen = false
            commit(preset)
          },
        )
      }
      KetchChip(
        label = "Custom…",
        selected = !isPreset,
        enabled = enabled,
        onClick = {
          customOpen = true
          focusField = true
        },
      )
      if (pending) KetchSpinner()
    }
    if (customOpen || !isPreset) {
      KetchTextField(
        value = text,
        onValueChange = { input ->
          text = input
          val limit = parseSpeedInput(input, unit)
          if (limit != null) debounce.update(limit) else debounce.cancel()
        },
        placeholder = "e.g. 750k or 1.5m",
        enabled = enabled,
        clearable = false,
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Decimal,
          imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { debounce.flush() }),
        onFocusChange = { fieldFocused = it },
        trailing = {
          KetchButton(
            text = unit.label,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
            enabled = enabled,
            tooltip = "Unit of a number without k or m",
            onClick = {
              unit = if (unit == SpeedUnit.MB) SpeedUnit.KB else SpeedUnit.MB
              parseSpeedInput(text, unit)?.let { debounce.update(it) }
            },
          )
        },
        modifier = Modifier.width(CustomFieldWidth).focusRequester(field),
      )
    }
    if (caption != null) {
      Text(caption, style = KetchTheme.typography.caption, color = KetchTheme.colors.textSecondary)
    }
  }
}

private fun customText(limit: SpeedLimit): String =
  if (limit.isUnlimited) "" else formatSpeedAmount(limit, preferredUnit(limit))

/**
 * Commits the last value given to [update] once no other arrives for [delay], or at once on
 * [flush], so a value typed key by key is committed once.
 */
internal class DebouncedCommit<T>(
  private val scope: CoroutineScope,
  private val delay: Duration,
  private val commit: (T) -> Unit,
) {
  private var job: Job? = null
  private var pending: Pending<T>? = null

  /** Replaces the value waiting to be committed and restarts the delay. */
  fun update(value: T) {
    pending = Pending(value)
    job?.cancel()
    job = scope.launch {
      delay(delay)
      job = null
      commitPending()
    }
  }

  /** Commits the waiting value now, if there is one. */
  fun flush() {
    job?.cancel()
    job = null
    commitPending()
  }

  /** Drops the waiting value. */
  fun cancel() {
    job?.cancel()
    job = null
    pending = null
  }

  private fun commitPending() {
    val value = pending ?: return
    pending = null
    commit(value.value)
  }

  private class Pending<T>(val value: T)
}

private val CustomCommitDelay = 600.milliseconds
private val SettleTimeout = 2.seconds
private val CustomFieldWidth = 200.dp
