package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.AiSettings

/**
 * Settings destination: device, appearance, downloads, server and AI.
 *
 * @param appSettings holder for the non-AI config sections.
 * @param aiSettings holder for AI discovery settings and its provider.
 * @param defaultDeviceName label used when no name is configured.
 * @param backendLabel instance that download settings are applied to.
 * @param serverState whether the local server is running.
 * @param serverSupported whether this platform can run a local server.
 * @param onSaveDownload persist download settings and push them to the
 *   active instance.
 * @param onTestAi persist AI settings and call the provider.
 * @param onStartServer start the local server.
 * @param onStopServer stop the local server.
 */
@Composable
fun SettingsPage(
  appSettings: AppSettingsController,
  aiSettings: AiSettingsController,
  defaultDeviceName: String,
  backendLabel: String,
  serverState: ServerState,
  serverSupported: Boolean,
  onSaveDownload: (DownloadConfig) -> Unit,
  onTestAi: (AiSettings) -> Unit,
  onStartServer: () -> Unit,
  onStopServer: () -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 600.dp
    val inset = if (compact) 16.dp else 32.dp
    Column(
      modifier = Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(inset),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Settings",
            style = KetchTheme.typography.displaySmall,
            color = KetchTheme.colors.onBackground,
          )
          Text(
            text = "Everything here is saved to this device's config file.",
            style = KetchTheme.typography.bodyMedium,
            color = KetchTheme.colors.onSurfaceVariant,
          )
        }

        GeneralSettingsCard(
          name = appSettings.config.name.orEmpty(),
          defaultName = defaultDeviceName,
          compact = compact,
          onSave = { appSettings.saveName(it) },
        )

        AppearanceSettingsCard(
          accent = appSettings.accent,
          compact = compact,
          onSelect = { appSettings.saveAccent(it) },
        )

        DownloadSettingsCard(
          config = appSettings.config.download,
          backendLabel = backendLabel,
          compact = compact,
          onSave = onSaveDownload,
        )

        if (serverSupported) {
          ServerSettingsCard(
            config = appSettings.config.server,
            serverState = serverState,
            compact = compact,
            onSave = { appSettings.saveServer(it) },
            onStart = onStartServer,
            onStop = onStopServer,
          )
        }

        AiSettingsCard(
          settings = aiSettings.settings,
          supported = aiSettings.supported,
          resolveCredentials = aiSettings::withPlatformCredentials,
          connectionTest = aiSettings.connectionTest,
          compact = compact,
          onSave = { aiSettings.save(it) },
          onTest = onTestAi,
        )
      }
    }
  }
}
