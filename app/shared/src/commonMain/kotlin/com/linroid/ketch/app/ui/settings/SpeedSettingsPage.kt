package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.ConnectionRange
import com.linroid.ketch.app.components.ConnectionStepper
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.components.SpeedLimitPickerPresets
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.autoModeSummary
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.newSpeedRule
import com.linroid.ketch.app.state.normalizeRuleTime
import com.linroid.ketch.app.state.ruleDaysLabel
import com.linroid.ketch.app.state.ruleIncludes
import com.linroid.ketch.app.state.slowLanePresets
import com.linroid.ketch.app.state.speedChoices
import com.linroid.ketch.app.state.toggleRuleDay
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.SpeedSettings
import com.linroid.ketch.config.Weekday
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.datetime.TimeZone

/**
 * Speed settings of [device]: the speed mode with its Slow lane and Auto rules, the full speed
 * cap, and the connections each download opens. Changes apply at once.
 *
 * Only the embedded device has a speed mode, kept by the host's [SpeedModeController]; other
 * devices, and hosts that keep no speed mode, offer a plain speed limit instead.
 */
@Composable
fun SpeedSettingsPage(state: AppState, device: InstanceEntry) {
  val controller = state.settingsFor(device)
  val model = remember(state, device) { SpeedSettingsModel(state, device) }
  LaunchedEffect(controller) { controller.loadDownload() }
  val config = controller.download
  if (controller.isRemote) {
    SettingsNotice(
      text = "Saved on ${device.label} until it restarts. No Slow lane or Auto for it yet.",
      tone = NoticeTone.Info,
    )
  }
  DeviceSettingsError(controller.downloadError, loaded = config != null) {
    controller.loadDownload()
  }
  if (config == null) {
    if (controller.downloadError == null) {
      SettingsLoading("Loading settings from ${device.label}…")
    }
    return
  }
  val speedMode = model.speedMode
  if (speedMode == null) {
    SettingsGroup(title = "Limit") {
      SettingsRow(
        title = "Speed limit",
        description = "All downloads share it; a download's own limit can go lower.",
      ) {
        SpeedLimitPicker(
          value = config.speedLimit,
          onCommit = model::setFullSpeedCap,
          presets = speedChoices(SpeedLimitPickerPresets, config.speedLimit),
        )
      }
    }
  } else {
    val settings by speedMode.settings.collectAsState()
    val mode by speedMode.mode.collectAsState()
    val peak by speedMode.observedPeak.collectAsState()
    val cap = if (settings.mode == SpeedLimitMode.Full) config.speedLimit else settings.standard
    val suggested = SpeedModeController.suggestSlowLane(peak.bytesPerSecond)
    ModeGroup(settings, mode, cap, model)
    LimitsGroup(settings, cap, suggested, hasPeak = peak.bytesPerSecond > 0, model)
    RulesGroup(settings, model)
  }
  PerDownloadGroup(config, model)
}

/** Full speed, Slow lane or Auto, and what the chosen one does right now. */
@Composable
private fun ModeGroup(
  settings: SpeedSettings,
  mode: SpeedMode,
  cap: SpeedLimit,
  model: SpeedSettingsModel,
) {
  val slowLane = model.slowLaneSpeed(settings)
  val summary = when (mode) {
    SpeedMode.Full -> if (cap.isUnlimited) {
      "Downloads use all the speed they can get."
    } else {
      "Downloads share up to ${formatSpeedLimit(cap)}."
    }
    SpeedMode.SlowLane ->
      "Downloads share at most ${formatSpeedLimit(slowLane)}, leaving room for calls."
    is SpeedMode.Auto -> if (settings.rules.isEmpty()) {
      "Add a rule below to turn the Slow lane on by itself."
    } else {
      "${autoModeSummary(mode, LocalClock.current.now(), TimeZone.currentSystemDefault())}."
    }
  }
  SettingsGroup(title = "Mode") {
    SettingsRow(title = "Speed mode", description = summary) {
      KetchSegmented(
        options = SpeedLimitMode.entries,
        selected = settings.mode,
        onSelect = model::setMode,
        label = { it.label },
        icon = { it.icon },
      )
    }
  }
}

/** The full speed cap and the Slow lane speed, each with the shared speed picker. */
@Composable
private fun LimitsGroup(
  settings: SpeedSettings,
  cap: SpeedLimit,
  suggested: SpeedLimit,
  hasPeak: Boolean,
  model: SpeedSettingsModel,
) {
  val slowLane = model.slowLaneSpeed(settings)
  SettingsGroup(title = "Limits") {
    SettingsRow(
      title = "Full speed cap",
      description = "The most downloads share outside the Slow lane.",
    ) {
      SpeedLimitPicker(
        value = cap,
        onCommit = model::setFullSpeedCap,
        presets = speedChoices(SpeedLimitPickerPresets, cap),
      )
    }
    SettingsRow(
      title = "Slow lane speed",
      description = if (hasPeak) {
        "≈ ${formatSpeedLimit(suggested)} suggested, ${SpeedModeController.SLOW_LANE_PERCENT}% " +
          "of the top speed seen this week."
      } else {
        "${formatSpeedLimit(suggested)} until Ketch has seen how fast this connection goes."
      },
    ) {
      SpeedLimitPicker(
        value = slowLane,
        presets = speedChoices(slowLanePresets(suggested), slowLane),
        onCommit = { limit -> model.setSlowLane(limit.takeUnless { it == suggested }) },
        caption = if (!cap.isUnlimited && cap.bytesPerSecond < slowLane.bytesPerSecond) {
          "The full speed cap of ${formatSpeedLimit(cap)} is lower and applies instead."
        } else {
          null
        },
      )
    }
  }
}

/** Up to [SpeedSettings.MAX_RULES] weekly windows in which Auto turns the Slow lane on. */
@Composable
private fun RulesGroup(settings: SpeedSettings, model: SpeedSettingsModel) {
  val rules = settings.rules
  val canAdd = rules.size < SpeedSettings.MAX_RULES
  SettingsGroup(
    title = "Auto rules",
    footer = if (settings.mode == SpeedLimitMode.Auto) {
      "Rules follow this device's clock."
    } else {
      "Rules apply in Auto mode, by this device's clock."
    },
    action = {
      KetchButton(
        text = "Add rule",
        onClick = { model.setRules(rules + newSpeedRule()) },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Plus,
        enabled = canAdd,
        tooltip = if (canAdd) null else "Up to ${SpeedSettings.MAX_RULES} rules",
      )
    },
  ) {
    if (rules.isEmpty()) {
      SettingsRow(
        title = "No rules yet",
        description = "For example, slow downloads down during work hours.",
      )
    }
    rules.forEachIndexed { index, rule ->
      RuleRow(
        rule = rule,
        onChange = { changed ->
          model.setRules(rules.mapIndexed { i, it -> if (i == index) changed else it })
        },
        onRemove = { model.setRules(rules.filterIndexed { i, _ -> i != index }) },
      )
    }
  }
}

/** "Slow lane on (M)(T)(W)(T)(F)(S)(S) [09:00] – [18:00] ✕", wrapping on narrow windows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleRow(rule: SpeedRule, onChange: (SpeedRule) -> Unit, onRemove: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth()
      .background(colors.surface)
      .heightIn(min = KetchTheme.density.iconButtonTarget + spacing.s4)
      .padding(horizontal = spacing.s4, vertical = spacing.s3),
  ) {
    FlowRow(
      modifier = Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Slow lane on", style = KetchTheme.typography.body, color = colors.textPrimary)
      Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        modifier = Modifier.semantics { contentDescription = ruleDaysLabel(rule.days) },
      ) {
        Weekday.entries.forEach { day ->
          DayToggle(
            day = day,
            on = ruleIncludes(rule.days, day),
            onToggle = { onChange(rule.copy(days = toggleRuleDay(rule.days, day))) },
          )
        }
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        TimeField(rule.start, label = "Start", onCommit = { onChange(rule.copy(start = it)) })
        Text("–", style = KetchTheme.typography.body, color = colors.textSecondary)
        TimeField(rule.end, label = "End", onCommit = { onChange(rule.copy(end = it)) })
      }
    }
    KetchIconButton(
      icon = KetchIcon.Close,
      onClick = onRemove,
      size = KetchButtonSize.Small,
      contentDescription = "Remove rule",
    )
  }
}

/** One day of a rule as a round toggle with its initial. */
@Composable
private fun DayToggle(day: Weekday, on: Boolean, onToggle: () -> Unit) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .size(KetchTheme.density.chip)
      .background(if (on) colors.accentSoft else colors.surface, shape)
      .background(overlay, shape)
      .border(HairlineWidth, if (on) colors.accentSoft else colors.borderStrong, shape)
      .semantics { contentDescription = day.name }
      .trackFocusVisibility(focus)
      .toggleable(
        value = on,
        interactionSource = interactions,
        indication = null,
        role = Role.Checkbox,
        onValueChange = { onToggle() },
      ),
  ) {
    Text(
      text = day.name.take(1),
      style = KetchTheme.typography.labelS,
      color = if (on) colors.accentText else colors.textSecondary,
    )
  }
}

/** An `HH:MM` field that saves a valid time as it is typed. */
@Composable
private fun TimeField(value: String, label: String, onCommit: (String) -> Unit) {
  SettingsTextInput(
    value = value,
    onCommit = onCommit,
    normalize = { normalizeRuleTime(it) ?: it.trim() },
    validate = { if (normalizeRuleTime(it) == null) "Use HH:MM" else null },
    placeholder = label,
    mono = true,
    width = TimeFieldWidth,
  )
}

/** Settings each download starts with. */
@Composable
private fun PerDownloadGroup(config: DownloadConfig, model: SpeedSettingsModel) {
  SettingsGroup(title = "Per download") {
    SettingsRow(
      title = "Connections per download",
      description = "For HTTP and FTP; applies as downloads start or resume.",
      trailing = {
        ConnectionStepper(
          value = config.maxConnectionsPerDownload.coerceIn(ConnectionRange),
          onCommit = model::setConnections,
        )
      },
    )
  }
}

private val SpeedLimitMode.label: String
  get() = when (this) {
    SpeedLimitMode.Full -> "Full speed"
    SpeedLimitMode.SlowLane -> "Slow lane"
    SpeedLimitMode.Auto -> "Auto"
  }

private val SpeedLimitMode.icon: KetchIcon
  get() = when (this) {
    SpeedLimitMode.Full -> KetchIcon.Speed
    SpeedLimitMode.SlowLane -> KetchIcon.SlowLane
    SpeedLimitMode.Auto -> KetchIcon.Auto
  }

private val TimeFieldWidth = 72.dp

/**
 * What the Speed page changes on [device], kept out of the composable so it can be tested.
 *
 * Mode changes go through the host's [SpeedModeController], which replaces the device's speed
 * limit; the device's settings are then read back, so the Pulse bar's cap follows at once.
 * Outside full speed, the device's limit is the Slow lane's, so the full speed cap is saved as
 * the controller's standing cap instead of in the download settings.
 *
 * At full speed the controller only learns the device's limit when it leaves full speed, and
 * any change it makes before that puts back the standing cap it last knew. So every change
 * first hands it the limit the device uses, which may have been set elsewhere, such as in the
 * Pulse bar or the config file.
 *
 * Changes run in the app scope, so closing Settings right after a click never undoes them, and
 * failures are posted to [AppState.messages].
 */
internal class SpeedSettingsModel(
  private val state: AppState,
  private val device: InstanceEntry,
) {
  private val log = KetchLogger("SpeedSettings")
  private val settings = state.settingsFor(device)

  /** Speed mode of [device]; only the embedded device has one, and only when the host keeps it. */
  val speedMode: SpeedModeController? = state.speedMode.takeIf { device is EmbeddedInstance }

  /** The Slow lane speed of [settings]: the one set, or the suggested one. */
  fun slowLaneSpeed(settings: SpeedSettings): SpeedLimit =
    settings.slowLane ?: speedMode?.suggestedSlowLane ?: SpeedModeController.DEFAULT_SLOW_LANE

  /** Switches to [mode]. */
  fun setMode(mode: SpeedLimitMode): Job? = change("switch to ${mode.label}") { it.setMode(mode) }

  /** Sets the speed downloads share outside the Slow lane; [SpeedLimit.Unlimited] for none. */
  fun setFullSpeedCap(limit: SpeedLimit): Job? {
    val modes = speedMode
    if (modes == null || modes.settings.value.mode == SpeedLimitMode.Full) {
      // At full speed the cap is the device's own limit, saved with its download settings.
      val config = settings.download ?: return null
      settings.updateDownload(config.copy(speedLimit = limit))
      if (modes == null) return null
      return change("change the full speed cap", syncCap = false) { it.setStandard(limit) }
    }
    return change("change the full speed cap") { it.setStandard(limit) }
  }

  /** Sets the Slow lane speed; `null` follows the suggestion. */
  fun setSlowLane(limit: SpeedLimit?): Job? =
    change("change the Slow lane speed") { it.setSlowLane(limit) }

  /** Replaces the Auto rules. */
  fun setRules(rules: List<SpeedRule>): Job? =
    change("change the Auto rules") { it.setRules(rules) }

  /** Sets the connections a download opens unless it asks for its own number. */
  fun setConnections(count: Int) {
    val config = settings.download ?: return
    if (config.maxConnectionsPerDownload != count) {
      settings.updateDownload(config.copy(maxConnectionsPerDownload = count))
    }
  }

  // syncCap: whether to hand the controller the device's limit first; see the class KDoc.
  private fun change(
    action: String,
    syncCap: Boolean = true,
    block: suspend (SpeedModeController) -> Unit,
  ): Job? {
    val modes = speedMode ?: return null
    return state.launchCommand {
      try {
        if (syncCap && modes.settings.value.mode == SpeedLimitMode.Full) {
          val limit = device.instance.status().config.speedLimit
          if (modes.settings.value.standard != limit) modes.setStandard(limit)
        }
        block(modes)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't $action on deviceId=${device.deviceId}: ${e.describeCauses()}" }
        state.messages.post(
          level = MessageLevel.Error,
          title = "Couldn't $action",
          detail = e.message,
          deviceId = device.deviceId,
          cause = e,
        )
      }
      // The mode may have changed the device's limit; read it back for the speed readout.
      settings.loadDownload()
    }
  }
}
