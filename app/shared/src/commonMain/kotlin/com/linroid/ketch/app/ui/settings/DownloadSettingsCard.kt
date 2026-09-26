package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.state.DownloadSettingsInput
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.SpeedLimitSelector

/**
 * Download engine limits. Saving pushes them to the active instance, so
 * they take effect without a restart.
 *
 * @param config saved download settings.
 * @param backendLabel instance the settings will be applied to.
 * @param onSave persist and apply the edited settings.
 * @param onUnsavedChange told whether the form differs from [config].
 */
@Composable
fun DownloadSettingsCard(
  config: DownloadConfig,
  backendLabel: String,
  compact: Boolean,
  onSave: (DownloadConfig) -> Unit,
  modifier: Modifier = Modifier,
  onUnsavedChange: (Boolean) -> Unit = {},
) {
  var input by rememberSaveable(config, stateSaver = InputSaver) {
    mutableStateOf(DownloadSettingsInput.from(config))
  }
  val error = input.validate()
  val edited = input.toConfig(config)
  // An invalid form is an unsaved edit too: closing would lose it.
  ReportUnsaved(edited != config, onUnsavedChange)

  SettingsCard(
    title = "Downloads",
    description = "Where files land and how hard Ketch pulls on them.",
    compact = compact,
    modifier = modifier,
  ) {
    SettingsTextField(
      value = input.directory,
      onValueChange = { input = input.copy(directory = it) },
      label = "Download folder",
      placeholder = "Platform default",
      supportingText = "Leave empty to use the platform's downloads folder.",
    )
    SettingsTextField(
      value = input.maxConcurrentDownloads,
      onValueChange = {
        input = input.copy(maxConcurrentDownloads = it)
      },
      label = "Simultaneous downloads",
      supportingText = "0 means no limit; extra downloads wait in the queue.",
      numeric = true,
    )
    SettingsTextField(
      value = input.maxConnectionsPerDownload,
      onValueChange = {
        input = input.copy(maxConnectionsPerDownload = it)
      },
      label = "Connections per download",
      supportingText = "Segments requested in parallel for one file.",
      numeric = true,
    )
    SettingsTextField(
      value = input.maxConnectionsPerHost,
      onValueChange = {
        input = input.copy(maxConnectionsPerHost = it)
      },
      label = "Downloads per host",
      supportingText = "0 means no limit. Keeps one server from being hammered.",
      numeric = true,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SettingsFieldLabel("Global speed limit")
      SpeedLimitSelector(
        value = input.speedLimit,
        onValueChange = { input = input.copy(speedLimit = it) },
      )
    }

    if (error != null) {
      Text(
        text = error,
        style = KetchTheme.typography.bodySmall,
        color = KetchTheme.colors.error,
      )
    } else {
      SettingsHint("Applied to $backendLabel as soon as you save.")
    }

    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
      KetchButton(
        text = "Save",
        onClick = { edited?.let(onSave) },
        enabled = edited != null && edited != config,
      )
    }
  }
}

private val InputSaver = listSaver<DownloadSettingsInput, Any>(
  save = {
    listOf(
      it.directory,
      it.maxConcurrentDownloads,
      it.maxConnectionsPerDownload,
      it.maxConnectionsPerHost,
      it.speedLimit.bytesPerSecond,
    )
  },
  restore = {
    val bytesPerSecond = it[4] as Long
    DownloadSettingsInput(
      directory = it[0] as String,
      maxConcurrentDownloads = it[1] as String,
      maxConnectionsPerDownload = it[2] as String,
      maxConnectionsPerHost = it[3] as String,
      speedLimit = if (bytesPerSecond == 0L) {
        SpeedLimit.Unlimited
      } else {
        SpeedLimit.of(bytesPerSecond)
      },
    )
  },
)
