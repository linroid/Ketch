package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.SettingsSection
import com.linroid.ketch.config.AiSettings

/**
 * The card for one settings [section]. [SettingsPage] stacks them;
 * [SettingsDialog] shows one at a time.
 *
 * @param onUnsavedChange told whether the section's form holds edits that
 *   are not saved yet.
 * @param appSettings holder for the non-AI config sections.
 * @param aiSettings holder for AI discovery settings and its provider.
 * @param defaultDeviceName label used when no name is configured.
 * @param backendLabel instance that download settings are applied to.
 * @param serverState whether the local server is running.
 * @param onSaveDownload persist download settings and push them to the
 *   active instance.
 * @param onTestAi persist AI settings and call the provider.
 * @param onStartServer start the local server.
 * @param onStopServer stop the local server.
 */
@Composable
fun SettingsSectionCard(
  section: SettingsSection,
  compact: Boolean,
  onUnsavedChange: (Boolean) -> Unit,
  appSettings: AppSettingsController,
  aiSettings: AiSettingsController,
  defaultDeviceName: String,
  backendLabel: String,
  serverState: ServerState,
  onSaveDownload: (DownloadConfig) -> Unit,
  onTestAi: (AiSettings) -> Unit,
  onStartServer: () -> Unit,
  onStopServer: () -> Unit,
) {
  when (section) {
    SettingsSection.General -> GeneralSettingsCard(
      name = appSettings.config.name.orEmpty(),
      defaultName = defaultDeviceName,
      compact = compact,
      onSave = { appSettings.saveName(it) },
      onUnsavedChange = onUnsavedChange,
    )
    SettingsSection.Appearance -> AppearanceSettingsCard(
      accent = appSettings.accent,
      compact = compact,
      onSelect = { appSettings.saveAccent(it) },
    )
    SettingsSection.Downloads -> DownloadSettingsCard(
      config = appSettings.config.download,
      backendLabel = backendLabel,
      compact = compact,
      onSave = onSaveDownload,
      onUnsavedChange = onUnsavedChange,
    )
    SettingsSection.Server -> ServerSettingsCard(
      config = appSettings.config.server,
      serverState = serverState,
      compact = compact,
      onSave = { appSettings.saveServer(it) },
      onStart = onStartServer,
      onStop = onStopServer,
      onUnsavedChange = onUnsavedChange,
    )
    SettingsSection.Ai -> AiSettingsCard(
      settings = aiSettings.settings,
      supported = aiSettings.supported,
      resolveCredentials = aiSettings::withPlatformCredentials,
      connectionTest = aiSettings.connectionTest,
      compact = compact,
      onSave = { aiSettings.save(it) },
      onTest = onTestAi,
      onUnsavedChange = onUnsavedChange,
    )
  }
}
