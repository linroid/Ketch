package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.state.InstanceSettingsController
import com.linroid.ketch.app.state.SpeedLimitPresets
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.countChoices
import com.linroid.ketch.app.state.formatSpeedAmount
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.parseSpeedLimit
import com.linroid.ketch.app.state.preferredUnit
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Global download settings of the active instance. Every change is
 * applied as soon as it is made.
 *
 * @param instanceLabel name of the instance the settings belong to.
 */
@Composable
fun DownloadSettings(
  controller: InstanceSettingsController,
  instanceLabel: String,
) {
  LaunchedEffect(controller) { controller.loadDownload() }
  val config = controller.download
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    if (controller.isRemote) {
      SettingsNotice(
        text = "These settings belong to $instanceLabel. Changes apply right away " +
          "and last until it restarts.",
        tone = NoticeTone.Info,
      )
    }
    controller.downloadError?.let { error ->
      SettingsNotice(
        text = if (config == null) "Couldn't load the settings: $error" else error,
        tone = NoticeTone.Error,
        action = if (config == null) {
          {
            KetchButton(
              text = "Retry",
              onClick = { controller.loadDownload() },
              variant = KetchButtonVariant.Secondary,
              size = KetchButtonSize.Small,
            )
          }
        } else {
          null
        },
      )
    }
    if (config == null) {
      if (controller.downloadError == null) {
        Text(
          text = "Loading settings from $instanceLabel…",
          style = KetchTheme.typography.bodyMedium,
          color = KetchTheme.colors.onSurfaceVariant,
        )
      }
      return@Column
    }
    DownloadSettingsGroups(
      config = config,
      remote = controller.isRemote,
      instanceLabel = instanceLabel,
      onChange = { controller.updateDownload(it) },
    )
  }
}

@Composable
private fun DownloadSettingsGroups(
  config: DownloadConfig,
  remote: Boolean,
  instanceLabel: String,
  onChange: (DownloadConfig) -> Unit,
) {
  SettingsGroup(title = "Location") {
    SettingsRow(
      title = "Save downloads to",
      description = if (remote) {
        "A folder on $instanceLabel. Leave empty to use its Downloads folder."
      } else {
        "Leave empty to use the Downloads folder."
      },
    ) {
      SettingsTextInput(
        value = config.defaultDirectory.orEmpty(),
        onCommit = { onChange(config.copy(defaultDirectory = it.ifBlank { null })) },
        placeholder = "Downloads folder",
      )
    }
  }

  SettingsGroup(
    title = "Queue",
    footer = "Downloads beyond these limits wait in the queue and start in priority order.",
  ) {
    SettingsSelectRow(
      title = "Simultaneous downloads",
      description = "How many downloads run at once.",
      value = config.maxConcurrentDownloads,
      options = countChoices(listOf(1, 2, 3, 4, 5, 6, 8, 10, 0), config.maxConcurrentDownloads),
      label = { if (it == 0) "Unlimited" else "$it" },
      onSelect = { onChange(config.copy(maxConcurrentDownloads = it)) },
    )
    SettingsSelectRow(
      title = "Downloads per server",
      description = "Limit for each website or FTP server. Torrents aren't counted.",
      value = config.maxConnectionsPerHost,
      options = countChoices(listOf(1, 2, 3, 4, 6, 8, 12, 16, 0), config.maxConnectionsPerHost),
      label = { if (it == 0) "Unlimited" else "$it" },
      onSelect = { onChange(config.copy(maxConnectionsPerHost = it)) },
    )
  }

  SettingsGroup(
    title = "Speed",
    footer = "Speed and queue limits apply immediately. The other settings apply to " +
      "downloads as they start or resume.",
  ) {
    SpeedLimitRow(
      limit = config.speedLimit,
      onChange = { onChange(config.copy(speedLimit = it)) },
    )
    SettingsSelectRow(
      title = "Connections per download",
      description = "Parallel connections per file over HTTP or FTP. Torrents use " +
        "peers instead.",
      value = config.maxConnectionsPerDownload,
      options = countChoices(
        listOf(1, 2, 4, 6, 8, 12, 16, 24, 32),
        config.maxConnectionsPerDownload,
      ),
      label = { "$it" },
      onSelect = { onChange(config.copy(maxConnectionsPerDownload = it)) },
    )
  }

  SettingsGroup(title = "Reliability") {
    SettingsSelectRow(
      title = "Retry failed downloads",
      description = "Retries network errors and busy servers, keeping what's already " +
        "downloaded.",
      value = config.retryCount,
      options = countChoices(listOf(0, 1, 2, 3, 5, 10), config.retryCount)
        .sortedBy { it },
      label = {
        when (it) {
          0 -> "Never"
          1 -> "Once"
          else -> "$it times"
        }
      },
      onSelect = { onChange(config.copy(retryCount = it)) },
    )
  }
}

/**
 * Preset limits in a drop-down, plus "Custom…" which reveals an amount
 * field and unit toggle underneath.
 */
@Composable
private fun SpeedLimitRow(
  limit: SpeedLimit,
  onChange: (SpeedLimit) -> Unit,
) {
  var custom by remember { mutableStateOf(limit !in SpeedLimitPresets) }
  var unit by remember { mutableStateOf(preferredUnit(limit)) }
  SettingsRow(
    title = "Speed limit",
    description = "Shared by all downloads. A download's own limit can only lower it.",
    trailing = {
      SettingsSelect(
        value = if (custom) null else limit,
        options = SpeedLimitPresets + null,
        label = { it?.let(::formatSpeedLimit) ?: "Custom…" },
        onSelect = { choice ->
          if (choice == null) {
            custom = true
          } else {
            custom = false
            onChange(choice)
          }
        },
      )
    },
    content = if (custom) {
      {
        Row(
          verticalAlignment = Alignment.Top,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          SettingsTextInput(
            value = if (limit.isUnlimited) "" else formatSpeedAmount(limit, unit),
            onCommit = { amount -> parseSpeedLimit(amount, unit)?.let(onChange) },
            normalize = { text ->
              parseSpeedLimit(text, unit)?.let { formatSpeedAmount(it, unit) } ?: text.trim()
            },
            validate = { text ->
              if (text.isBlank() || parseSpeedLimit(text, unit) != null) null
              else "Enter a speed above 0."
            },
            placeholder = "Amount",
            decimal = true,
            mono = true,
            width = 160.dp,
          )
          SettingsSegmented(
            value = unit,
            options = SpeedUnit.entries,
            label = { it.label },
            onSelect = { newUnit ->
              // Keep the typed number and reinterpret it in the new unit.
              val amount = if (limit.isUnlimited) null else formatSpeedAmount(limit, unit)
              unit = newUnit
              amount?.let { parseSpeedLimit(it, newUnit) }?.let(onChange)
            },
          )
        }
      }
    } else {
      null
    },
  )
}
