package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.FileActionException
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.countChoices
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.folderNameText
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.state.isAppPrivateFolder
import com.linroid.ketch.app.state.isDocumentTree
import com.linroid.ketch.app.state.offersDefaultFolder
import com.linroid.ketch.app.state.recentDownloadFolders
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.IntakePreferences
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.device_free_space
import ketch.app.shared.generated.resources.settings_count_unlimited
import ketch.app.shared.generated.resources.settings_downloads_at_once
import ketch.app.shared.generated.resources.settings_downloads_at_once_fewer
import ketch.app.shared.generated.resources.settings_downloads_at_once_more
import ketch.app.shared.generated.resources.settings_downloads_at_once_unlimited
import ketch.app.shared.generated.resources.settings_downloads_change
import ketch.app.shared.generated.resources.settings_downloads_choose_folder
import ketch.app.shared.generated.resources.settings_downloads_enter_path
import ketch.app.shared.generated.resources.settings_downloads_folder
import ketch.app.shared.generated.resources.settings_downloads_folders
import ketch.app.shared.generated.resources.settings_downloads_folders_footer
import ketch.app.shared.generated.resources.settings_downloads_path_placeholder
import ketch.app.shared.generated.resources.settings_downloads_per_server
import ketch.app.shared.generated.resources.settings_downloads_per_server_count
import ketch.app.shared.generated.resources.settings_downloads_per_server_fewer
import ketch.app.shared.generated.resources.settings_downloads_per_server_hint
import ketch.app.shared.generated.resources.settings_downloads_per_server_more
import ketch.app.shared.generated.resources.settings_downloads_per_server_unlimited
import ketch.app.shared.generated.resources.settings_downloads_pin
import ketch.app.shared.generated.resources.settings_downloads_pinned
import ketch.app.shared.generated.resources.settings_downloads_private_folder
import ketch.app.shared.generated.resources.settings_downloads_queue
import ketch.app.shared.generated.resources.settings_downloads_queue_footer
import ketch.app.shared.generated.resources.settings_downloads_recent
import ketch.app.shared.generated.resources.settings_downloads_remote
import ketch.app.shared.generated.resources.settings_downloads_retries
import ketch.app.shared.generated.resources.settings_downloads_retries_count
import ketch.app.shared.generated.resources.settings_downloads_retries_fewer
import ketch.app.shared.generated.resources.settings_downloads_retries_hint
import ketch.app.shared.generated.resources.settings_downloads_retries_more
import ketch.app.shared.generated.resources.settings_downloads_retries_never
import ketch.app.shared.generated.resources.settings_downloads_retries_never_state
import ketch.app.shared.generated.resources.settings_downloads_run_at_once
import ketch.app.shared.generated.resources.settings_downloads_run_at_once_hint
import ketch.app.shared.generated.resources.settings_downloads_save_to
import ketch.app.shared.generated.resources.settings_downloads_save_to_row
import ketch.app.shared.generated.resources.settings_downloads_torrents_own_folder
import ketch.app.shared.generated.resources.settings_downloads_unpin
import ketch.app.shared.generated.resources.settings_downloads_use_default
import ketch.app.shared.generated.resources.settings_loading_from
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val log = KetchLogger("DownloadSettings")

/**
 * Download settings of [device]: where downloads are saved, the folders the add sheet offers,
 * and the queue. Every change applies as soon as it is made; the embedded device also saves it
 * to `config.toml`, while a remote one keeps it until it restarts.
 */
@Composable
fun DownloadSettings(state: AppState, device: InstanceEntry) {
  val controller = state.settingsFor(device)
  LaunchedEffect(controller) { controller.loadDownload() }
  val config = controller.download
  if (controller.isRemote) {
    SettingsNotice(
      text = stringResource(Res.string.settings_downloads_remote, device.label),
      tone = NoticeTone.Info,
    )
  }
  DeviceSettingsError(controller.downloadError, loaded = config != null) {
    controller.loadDownload()
  }
  if (config == null) {
    if (controller.downloadError == null) {
      SettingsLoading(stringResource(Res.string.settings_loading_from, device.label))
    }
    return
  }
  val onChange = { updated: DownloadConfig -> controller.updateDownload(updated) }
  val presence by state.instanceManager.presence.collectAsState()
  // A remote device that has not reported yet is assumed to sort, as it just sent its settings.
  val sortsIntoFolders = remember(presence, device) {
    device !is RemoteInstance || presence.firstOrNull { it.deviceId == device.deviceId }
      ?.status?.let { KetchFeatures.CATEGORY_FOLDERS in it.features } ?: true
  }
  FolderGroup(device, config, onChange)
  CategoryGroup(device, { controller.download ?: config }, sortsIntoFolders, onChange)
  FolderShortcuts(state, device, config)
  QueueGroup(config, onChange)
}

/** Where [device] saves downloads, with the free space there and ways to change it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderGroup(
  device: InstanceEntry,
  config: DownloadConfig,
  onChange: (DownloadConfig) -> Unit,
) {
  val spacing = KetchTheme.spacing
  val local = device is EmbeddedInstance
  val picker = rememberFilePicker()
  val files = rememberFileActions()
  val scope = rememberCoroutineScope()
  val system by produceState<SystemInfo?>(null, device, config.defaultDirectory) {
    value = readSystem(device)
  }
  val folder = config.defaultDirectory ?: system?.downloadDirectory
  val defaultFolder = system?.defaultDownloadDirectory
  val offerDefault = offersDefaultFolder(config.defaultDirectory, defaultFolder)
  val canPick = local && picker.canPickFolder
  val revealLabel = files?.revealLabel.takeIf { local && folder != null && !isDocumentTree(folder) }
  var typing by rememberSaveable(device.deviceId) { mutableStateOf(!canPick) }
  var failure by remember { mutableStateOf<String?>(null) }
  val pick: () -> Unit = {
    scope.launch {
      picker.pickFolder(folder)?.let { onChange(config.copy(defaultDirectory = it)) }
    }
  }

  SettingsGroup(title = stringResource(Res.string.settings_downloads_save_to)) {
    // The folder shows below, so the row only explains what went wrong.
    SettingsRow(
      title = stringResource(Res.string.settings_downloads_save_to_row),
      description = failure,
      descriptionColor = KetchTheme.colors.status.failed.color,
    ) {
      val free = system?.usableSpace?.takeIf { it > 0 }
        ?.let { Res.string.device_free_space.text(formatSpace(it)).resolve() }
      // Typing replaces the pill, so the folder shows once.
      if (typing) {
        SettingsTextInput(
          value = config.defaultDirectory.orEmpty(),
          onCommit = { onChange(config.copy(defaultDirectory = it.ifBlank { null })) },
          placeholder = defaultFolder
            ?: stringResource(Res.string.settings_downloads_path_placeholder, device.label),
          mono = true,
          actions = if (free != null) {
            { FreeSpace(free, Modifier.padding(end = spacing.s2)) }
          } else {
            null
          },
        )
      } else {
        FolderPill(path = folder, free = free)
      }
      if (local && folder != null && isAppPrivateFolder(folder)) {
        SettingsNotice(
          text = stringResource(Res.string.settings_downloads_private_folder),
          tone = NoticeTone.Warning,
          action = if (canPick) {
            {
              KetchButton(
                text = stringResource(Res.string.settings_downloads_choose_folder),
                onClick = pick,
                variant = KetchButtonVariant.Secondary,
                size = KetchButtonSize.Small,
              )
            }
          } else {
            null
          },
        )
      }
      if (folder != null && isDocumentTree(folder)) {
        SettingsNotice(
          text = stringResource(Res.string.settings_downloads_torrents_own_folder),
          tone = NoticeTone.Info,
        )
      }
      if (!canPick && revealLabel == null && !offerDefault) return@SettingsRow
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        if (canPick) {
          KetchButton(
            text = stringResource(Res.string.settings_downloads_change),
            onClick = pick,
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Folder,
          )
        }
        if (revealLabel != null && folder != null) {
          KetchButton(
            text = stringResource(Res.string.action_show),
            tooltip = revealLabel.resolve(),
            onClick = {
              scope.launch {
                failure = try {
                  files?.reveal(folder)
                  null
                } catch (e: FileActionException) {
                  e.message
                }
              }
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Reveal,
          )
        }
        if (canPick && !typing) {
          KetchButton(
            text = stringResource(Res.string.settings_downloads_enter_path),
            onClick = { typing = true },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        }
        if (offerDefault) {
          KetchButton(
            text = stringResource(Res.string.settings_downloads_use_default),
            onClick = { onChange(config.copy(defaultDirectory = null)) },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        }
      }
    }
  }
}

/** The folder as a sunken pill, middle-ellipsized, with the [free] space at its end. */
@Composable
private fun FolderPill(path: String?, free: String?) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth()
      .heightIn(min = KetchTheme.density.input)
      .background(colors.surfaceSunken, KetchTheme.shapes.full)
      .padding(horizontal = spacing.s3),
  ) {
    KetchIconImage(
      icon = KetchIcon.Folder,
      size = KetchTheme.density.controlGlyph,
      tint = colors.textTertiary,
    )
    MiddleEllipsisText(
      text = path?.let { if (isDocumentTree(it)) folderNameText(it).resolve() else it }
        ?: stringResource(Res.string.settings_downloads_folder),
      style = KetchTheme.typography.mono,
      color = colors.textPrimary,
      modifier = Modifier.weight(1f),
    )
    if (free != null) FreeSpace(free)
  }
}

/** "384 GB free" at the end of the folder. */
@Composable
private fun FreeSpace(text: String, modifier: Modifier = Modifier) {
  Text(
    text = text,
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textSecondary,
    maxLines = 1,
    modifier = modifier,
  )
}

/**
 * The folders the add sheet offers [device] besides its default: the pinned ones, which the
 * user can unpin, and recent ones from its downloads, which can be pinned.
 */
@Composable
private fun FolderShortcuts(state: AppState, device: InstanceEntry, config: DownloadConfig) {
  val deviceId = device.deviceId
  val pinned = state.appSettings.ui.intake[deviceId]?.favoriteFolders.orEmpty()
  val tasks by device.instance.tasks.collectAsState()
  val recent = remember(tasks, pinned, config.defaultDirectory) {
    recentDownloadFolders(tasks, exclude = pinned + listOfNotNull(config.defaultDirectory))
  }
  if (pinned.isEmpty() && recent.isEmpty()) return
  val savePinned = { folders: List<String> ->
    state.appSettings.saveUi { ui ->
      val intake = ui.intake[deviceId] ?: IntakePreferences()
      ui.copy(intake = ui.intake + (deviceId to intake.copy(favoriteFolders = folders)))
    }
  }
  SettingsGroup(
    title = stringResource(Res.string.settings_downloads_folders),
    footer = stringResource(Res.string.settings_downloads_folders_footer),
  ) {
    pinned.forEach { path ->
      FolderShortcutRow(path = path, pinned = true, onToggle = { savePinned(pinned - path) })
    }
    recent.forEach { path ->
      FolderShortcutRow(path = path, pinned = false, onToggle = { savePinned(pinned + path) })
    }
  }
}

@Composable
private fun FolderShortcutRow(path: String, pinned: Boolean, onToggle: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val name = folderNameText(path).resolve()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier.fillMaxWidth()
      .background(colors.surface)
      .heightIn(min = KetchTheme.density.iconButtonTarget + spacing.s4)
      .padding(horizontal = spacing.s4, vertical = spacing.s2),
  ) {
    KetchIconImage(
      icon = KetchIcon.Folder,
      size = KetchTheme.density.controlGlyph,
      tint = if (pinned) colors.accentText else colors.textTertiary,
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Text(
        text = name,
        style = KetchTheme.typography.body,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      MiddleEllipsisText(
        text = if (pinned) {
          stringResource(Res.string.settings_downloads_pinned, path)
        } else {
          stringResource(Res.string.settings_downloads_recent, path)
        },
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
      )
    }
    if (pinned) {
      KetchIconButton(
        icon = KetchIcon.Close,
        onClick = onToggle,
        size = KetchButtonSize.Small,
        contentDescription = stringResource(Res.string.settings_downloads_unpin, name),
      )
    } else {
      KetchButton(
        text = stringResource(Res.string.settings_downloads_pin),
        onClick = onToggle,
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
  }
}

/** How many downloads run, per device and per server, and how often failures are retried. */
@Composable
private fun QueueGroup(config: DownloadConfig, onChange: (DownloadConfig) -> Unit) {
  SettingsGroup(
    title = stringResource(Res.string.settings_downloads_queue),
    footer = stringResource(Res.string.settings_downloads_queue_footer),
  ) {
    val atOnce = config.maxConcurrentDownloads
    SettingsStepperRow(
      title = stringResource(Res.string.settings_downloads_run_at_once),
      description = stringResource(Res.string.settings_downloads_run_at_once_hint),
      value = atOnce,
      values = countChoices(RunAtOnceChoices, atOnce),
      label = countLabel(atOnce, Res.string.settings_count_unlimited),
      state = if (atOnce == 0) {
        stringResource(Res.string.settings_downloads_at_once_unlimited)
      } else {
        pluralStringResource(Res.plurals.settings_downloads_at_once, atOnce, atOnce)
      },
      fewer = stringResource(Res.string.settings_downloads_at_once_fewer),
      more = stringResource(Res.string.settings_downloads_at_once_more),
      onChange = { onChange(config.copy(maxConcurrentDownloads = it)) },
    )
    val perServer = config.maxConnectionsPerHost
    SettingsStepperRow(
      title = stringResource(Res.string.settings_downloads_per_server),
      description = stringResource(Res.string.settings_downloads_per_server_hint),
      value = perServer,
      values = countChoices(PerServerChoices, perServer),
      label = countLabel(perServer, Res.string.settings_count_unlimited),
      state = if (perServer == 0) {
        stringResource(Res.string.settings_downloads_per_server_unlimited)
      } else {
        pluralStringResource(Res.plurals.settings_downloads_per_server_count, perServer, perServer)
      },
      fewer = stringResource(Res.string.settings_downloads_per_server_fewer),
      more = stringResource(Res.string.settings_downloads_per_server_more),
      onChange = { onChange(config.copy(maxConnectionsPerHost = it)) },
    )
    val retries = config.retryCount
    SettingsStepperRow(
      title = stringResource(Res.string.settings_downloads_retries),
      description = stringResource(Res.string.settings_downloads_retries_hint),
      value = retries,
      values = (RetryChoices + retries).distinct().sorted(),
      label = countLabel(retries, Res.string.settings_downloads_retries_never),
      state = if (retries == 0) {
        stringResource(Res.string.settings_downloads_retries_never_state)
      } else {
        pluralStringResource(Res.plurals.settings_downloads_retries_count, retries, retries)
      },
      fewer = stringResource(Res.string.settings_downloads_retries_fewer),
      more = stringResource(Res.string.settings_downloads_retries_more),
      onChange = { onChange(config.copy(retryCount = it)) },
    )
  }
}

/** [count] as a stepper shows it, or the word [zero] stands for, such as "Unlimited". */
@Composable
private fun countLabel(count: Int, zero: StringResource): String =
  if (count == 0) stringResource(zero) else count.toString()

private suspend fun readSystem(device: InstanceEntry): SystemInfo? = try {
  device.instance.status().system
} catch (e: CancellationException) {
  throw e
} catch (e: Exception) {
  // A device that is offline shows its folder without the free space.
  val id = device.deviceId
  log.d { "Couldn't read the download folder of deviceId=$id: ${e.describeCauses()}" }
  null
}

private val RunAtOnceChoices = (1..10).toList() + 0
private val PerServerChoices = (1..8).toList() + listOf(10, 12, 16, 0)
private val RetryChoices = (0..10).toList()
