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
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.platform.FileActionException
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.countChoices
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.folderName
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.state.isAppPrivateFolder
import com.linroid.ketch.app.state.isDocumentTree
import com.linroid.ketch.app.state.offersDefaultFolder
import com.linroid.ketch.app.state.recentDownloadFolders
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.IntakePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
    SettingsNotice(text = "Saved on ${device.label} until it restarts.", tone = NoticeTone.Info)
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
  val onChange = { updated: DownloadConfig -> controller.updateDownload(updated) }
  FolderGroup(device, config, onChange)
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

  SettingsGroup(title = "Save to") {
    // The folder shows below, so the row only explains what went wrong.
    SettingsRow(
      title = "Save downloads to",
      description = failure,
      descriptionColor = KetchTheme.colors.status.failed.color,
    ) {
      val free = system?.usableSpace?.takeIf { it > 0 }?.let { "${formatSpace(it)} free" }
      // Typing replaces the pill, so the folder shows once.
      if (typing) {
        SettingsTextInput(
          value = config.defaultDirectory.orEmpty(),
          onCommit = { onChange(config.copy(defaultDirectory = it.ifBlank { null })) },
          placeholder = defaultFolder ?: "Path of a folder on ${device.label}",
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
          text = "Only Ketch can see this folder. Choose one such as Download.",
          tone = NoticeTone.Warning,
          action = if (canPick) {
            {
              KetchButton(
                text = "Choose folder",
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
          text = "Torrents still save to Ketch's own folder.",
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
            text = "Change…",
            onClick = pick,
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Folder,
          )
        }
        if (revealLabel != null && folder != null) {
          KetchButton(
            text = "Show",
            tooltip = revealLabel,
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
            text = "Enter path…",
            onClick = { typing = true },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        }
        if (offerDefault) {
          KetchButton(
            text = "Use the Downloads folder",
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
      text = path?.let { if (isDocumentTree(it)) folderName(it) else it } ?: "Downloads folder",
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
    title = "Folders in the add sheet",
    footer = "Pinned folders come first when you add a download.",
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
  val name = folderName(path)
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
        text = if (pinned) "Pinned · $path" else "Recent · $path",
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
      )
    }
    if (pinned) {
      KetchIconButton(
        icon = KetchIcon.Close,
        onClick = onToggle,
        size = KetchButtonSize.Small,
        contentDescription = "Unpin $name",
      )
    } else {
      KetchButton(
        text = "Pin",
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
  val unlimited = { count: Int -> if (count == 0) "Unlimited" else "$count" }
  SettingsGroup(
    title = "Queue",
    footer = "Lowering a limit lets running downloads finish. Retries apply as downloads start " +
      "or resume.",
  ) {
    SettingsStepperRow(
      title = "Run at once",
      description = "The rest wait in the queue, by priority.",
      value = config.maxConcurrentDownloads,
      values = countChoices(RunAtOnceChoices, config.maxConcurrentDownloads),
      label = unlimited,
      noun = "downloads at once",
      onChange = { onChange(config.copy(maxConcurrentDownloads = it)) },
    )
    SettingsStepperRow(
      title = "Per server",
      description = "Downloads from one website or FTP server at once.",
      value = config.maxConnectionsPerHost,
      values = countChoices(PerServerChoices, config.maxConnectionsPerHost),
      label = unlimited,
      noun = "downloads per server",
      onChange = { onChange(config.copy(maxConnectionsPerHost = it)) },
    )
    SettingsStepperRow(
      title = "Retries",
      description = "For network errors and busy servers.",
      value = config.retryCount,
      values = (RetryChoices + config.retryCount).distinct().sorted(),
      label = { if (it == 0) "Never" else "$it" },
      noun = "retries",
      onChange = { onChange(config.copy(retryCount = it)) },
    )
  }
}

private suspend fun readSystem(device: InstanceEntry): SystemInfo? = try {
  device.instance.status().system
} catch (e: CancellationException) {
  throw e
} catch (e: Exception) {
  // A device that is offline shows its folder without the free space.
  log.d {
    "Couldn't read the download folder of deviceId=${device.deviceId}: ${e.describeCauses()}"
  }
  null
}

private val RunAtOnceChoices = (1..10).toList() + 0
private val PerServerChoices = (1..8).toList() + listOf(10, 12, 16, 0)
private val RetryChoices = (0..10).toList()
