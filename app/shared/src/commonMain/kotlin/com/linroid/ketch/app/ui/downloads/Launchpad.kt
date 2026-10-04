package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.SailLanesIllustration
import com.linroid.ketch.app.components.SailLanesIllustrationDefaults
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.IntegrationStatus
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.shortPathText
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.KetchLayout
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.launchpad_browser_connected
import ketch.app.shared.generated.resources.launchpad_browser_get
import ketch.app.shared.generated.resources.launchpad_browser_none
import ketch.app.shared.generated.resources.launchpad_browser_title
import ketch.app.shared.generated.resources.launchpad_checklist_change
import ketch.app.shared.generated.resources.launchpad_checklist_close
import ketch.app.shared.generated.resources.launchpad_checklist_connected
import ketch.app.shared.generated.resources.launchpad_checklist_detected
import ketch.app.shared.generated.resources.launchpad_checklist_extension
import ketch.app.shared.generated.resources.launchpad_checklist_folder
import ketch.app.shared.generated.resources.launchpad_checklist_folder_choose
import ketch.app.shared.generated.resources.launchpad_checklist_get_it
import ketch.app.shared.generated.resources.launchpad_checklist_phone
import ketch.app.shared.generated.resources.launchpad_checklist_show_qr
import ketch.app.shared.generated.resources.launchpad_checklist_title
import ketch.app.shared.generated.resources.launchpad_discover
import ketch.app.shared.generated.resources.launchpad_pair_hint
import ketch.app.shared.generated.resources.launchpad_pair_title
import ketch.app.shared.generated.resources.launchpad_paste
import ketch.app.shared.generated.resources.launchpad_paste_download
import ketch.app.shared.generated.resources.launchpad_paste_hint
import ketch.app.shared.generated.resources.launchpad_paste_hint_phone
import ketch.app.shared.generated.resources.launchpad_paste_hint_plain
import ketch.app.shared.generated.resources.launchpad_paste_maybe
import ketch.app.shared.generated.resources.launchpad_paste_title
import ketch.app.shared.generated.resources.launchpad_subtitle
import ketch.app.shared.generated.resources.launchpad_subtitle_phone
import ketch.app.shared.generated.resources.launchpad_title
import ketch.app.shared.generated.resources.launchpad_torrent_hint
import ketch.app.shared.generated.resources.launchpad_torrent_hint_phone
import ketch.app.shared.generated.resources.launchpad_torrent_title
import ketch.app.shared.generated.resources.launchpad_torrent_title_phone
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.days

/**
 * What the Downloads page shows before the first download: the sail lanes, three ways to add
 * one (a copied link, a dropped or opened `.torrent`, the browser), where it is supported a line
 * that opens Discover, and on desktop and the web the setup checklist.
 *
 * @param phone whether the page is laid out for a phone, which stacks the ways and leaves out
 *   the checklist.
 */
@Composable
internal fun Launchpad(state: AppState, phone: Boolean, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val hooks = LocalDesktopHooks.current
  Box(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .align(Alignment.TopCenter)
        .padding(horizontal = spacing.s4)
        .padding(top = spacing.s8, bottom = if (phone) KetchLayout.FabClearance else spacing.s8)
        .widthIn(max = LaunchpadWidth)
        .fillMaxWidth(),
    ) {
      SailLanesIllustration(
        width = if (phone) PhoneIllustrationWidth else SailLanesIllustrationDefaults.Width,
      )
      Spacer(Modifier.height(spacing.s6))
      Text(
        text = stringResource(Res.string.launchpad_title),
        style = KetchTheme.typography.largeTitle,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(spacing.s2))
      Text(
        text = if (phone) {
          stringResource(Res.string.launchpad_subtitle_phone)
        } else {
          stringResource(Res.string.launchpad_subtitle)
        },
        style = KetchTheme.typography.body,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
      )
      Spacer(Modifier.height(spacing.s6))
      val tiles: @Composable (Modifier) -> Unit = { tile ->
        PasteTile(state, phone, tile)
        TorrentTile(state, phone, tile)
        when {
          phone -> PairTile(state, tile)
          hooks.isSupported -> BrowserTile(state, LocalIntegrationStatus.current, tile)
        }
      }
      if (phone) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
          tiles(Modifier.fillMaxWidth())
        }
      } else {
        Row(
          horizontalArrangement = Arrangement.spacedBy(spacing.s3),
          modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
          tiles(Modifier.weight(1f).fillMaxHeight())
        }
      }
      if (state.aiSettings.offered) {
        DiscoverLine(state, phone, Modifier.padding(top = spacing.s4))
      }
      if (!phone) SetupChecklist(state, hooks, Modifier.padding(top = spacing.s6))
    }
  }
}

/** Tile 1: adds the link on the clipboard, read as each platform allows. */
@Composable
private fun PasteTile(state: AppState, phone: Boolean, modifier: Modifier) {
  val clipboard = rememberSystemClipboard()
  val scope = rememberCoroutineScope()
  val clip = rememberClipboardLink(state, clipboard, skipOffered = false)
  val shortcut = KetchCommands.PasteLinks.shortcutLabel()
  val paste = {
    scope.launch {
      val text = catchingUnlessCancelled { clipboard.readText() }.getOrNull().orEmpty()
      addPasted(state, text)
    }
    Unit
  }
  val title = stringResource(Res.string.launchpad_paste_title)
  when (clip) {
    is ClipboardLink.Found -> LaunchTile(
      icon = KetchIcon.Link,
      title = title,
      detail = clip.label,
      hint = shortcut.takeUnless { phone },
      action = stringResource(Res.string.launchpad_paste_download),
      onClick = { state.quickAdd(listOf(clip.url)) },
      modifier = modifier,
      compact = phone,
    )
    ClipboardLink.Maybe -> LaunchTile(
      icon = KetchIcon.Link,
      title = title,
      detail = stringResource(Res.string.launchpad_paste_maybe),
      hint = shortcut.takeUnless { phone },
      action = stringResource(Res.string.launchpad_paste),
      onClick = paste,
      modifier = modifier,
      compact = phone,
    )
    ClipboardLink.None -> LaunchTile(
      icon = KetchIcon.Link,
      title = title,
      detail = when {
        phone -> stringResource(Res.string.launchpad_paste_hint_phone)
        shortcut != null -> stringResource(Res.string.launchpad_paste_hint, shortcut)
        else -> stringResource(Res.string.launchpad_paste_hint_plain)
      },
      hint = shortcut.takeUnless { phone },
      onClick = { state.openIntake() },
      modifier = modifier,
      compact = phone,
    )
  }
}

/** Tile 2: drop a link or `.torrent` file on the window, or pick a `.torrent` file. */
@Composable
private fun TorrentTile(state: AppState, phone: Boolean, modifier: Modifier) {
  val picker = rememberFilePicker()
  val scope = rememberCoroutineScope()
  LaunchTile(
    icon = KetchIcon.Drop,
    title = if (phone) {
      stringResource(Res.string.launchpad_torrent_title_phone)
    } else {
      stringResource(Res.string.launchpad_torrent_title)
    },
    detail = if (phone) {
      stringResource(Res.string.launchpad_torrent_hint_phone)
    } else {
      stringResource(Res.string.launchpad_torrent_hint)
    },
    hint = KetchCommands.OpenTorrent.shortcutLabel().takeUnless { phone },
    onClick = {
      scope.launch {
        val files = catchingUnlessCancelled { picker.pickTorrentFiles() }
          .onFailure { log.w { "Couldn't pick .torrent files: ${it.describeCauses()}" } }
          .getOrNull().orEmpty()
        if (files.isNotEmpty()) state.addDroppedFiles(files)
      }
    },
    modifier = modifier,
    compact = phone,
  )
}

/** Tile 3 on desktop: the browsers found and whether Ketch's extension is in them. */
@Composable
private fun BrowserTile(state: AppState, integration: IntegrationStatus, modifier: Modifier) {
  val browsers = integration.browsers.map { browser ->
    if (browser.extensionConnected) {
      stringResource(Res.string.launchpad_browser_connected, browser.name)
    } else {
      stringResource(Res.string.launchpad_browser_get, browser.name)
    }
  }
  val detail = when {
    browsers.isEmpty() -> stringResource(Res.string.launchpad_browser_none)
    else -> browsers.joinToString("  ")
  }
  LaunchTile(
    icon = KetchIcon.Browser,
    title = stringResource(Res.string.launchpad_browser_title),
    detail = detail,
    onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Integration)) },
    modifier = modifier,
  )
}

/** Tile 3 on phones: control a computer's downloads from here. */
@Composable
private fun PairTile(state: AppState, modifier: Modifier) {
  LaunchTile(
    icon = KetchIcon.Laptop,
    title = stringResource(Res.string.launchpad_pair_title),
    detail = stringResource(Res.string.launchpad_pair_hint),
    onClick = { state.showAddRemoteDialog = true },
    modifier = modifier,
    compact = true,
  )
}

/** The line under the ways to add: describe what you want and Discover finds it. */
@Composable
private fun DiscoverLine(state: AppState, phone: Boolean, modifier: Modifier = Modifier) {
  val shortcut = KetchCommands.Discover.shortcutLabel().takeUnless { phone }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = modifier,
  ) {
    KetchButton(
      text = stringResource(Res.string.launchpad_discover),
      onClick = { state.runInShell(KetchCommands.Discover) },
      variant = KetchButtonVariant.Ghost,
      size = KetchButtonSize.Small,
      leadingIcon = KetchIcon.Discover,
    )
    if (shortcut != null) {
      Text(shortcut, style = KetchTheme.typography.labelS, color = KetchTheme.colors.textTertiary)
    }
  }
}

/**
 * One way to add a first download: a bordered card with a glyph, a title, a line about it and,
 * when there is one, a button for what it does now, such as "Download it".
 *
 * @param hint shortcut of the same action, shown at the top end.
 */
@Composable
private fun LaunchTile(
  icon: KetchIcon,
  title: String,
  detail: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  hint: String? = null,
  action: String? = null,
  compact: Boolean = false,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.lg
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  val frame = modifier
    .focusRing(focus.visible, shape, colors.focusRing)
    .clip(shape)
    .background(colors.surface)
    .background(overlay)
    .border(HairlineWidth, colors.hairline, shape)
    .ketchClickable(interactions, focus, onClick = onClick)
    .padding(spacing.s4)
  if (compact) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = frame,
    ) {
      TileGlyph(icon)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text(text = title, style = KetchTheme.typography.bodyStrong, color = colors.textPrimary)
        Text(
          text = detail,
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (action != null) {
        KetchButton(
          text = action,
          onClick = onClick,
          variant = KetchButtonVariant.Tonal,
          size = KetchButtonSize.Small,
        )
      }
    }
    return
  }
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier
      .heightIn(min = TileMinHeight)
      .then(frame),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      TileGlyph(icon)
      Spacer(Modifier.weight(1f))
      if (hint != null) {
        Text(hint, style = KetchTheme.typography.labelS, color = colors.textTertiary, maxLines = 1)
      }
    }
    Spacer(Modifier.height(spacing.s1))
    Text(
      text = title,
      style = KetchTheme.typography.bodyStrong,
      color = colors.textPrimary,
    )
    Text(
      text = detail,
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
      maxLines = 3,
      overflow = TextOverflow.Ellipsis,
    )
    if (action != null) {
      Spacer(Modifier.weight(1f))
      KetchButton(
        text = action,
        onClick = onClick,
        variant = KetchButtonVariant.Tonal,
        size = KetchButtonSize.Small,
        modifier = Modifier.padding(top = spacing.s2),
      )
    }
  }
}

/** A tile's glyph on a soft accent square. */
@Composable
private fun TileGlyph(icon: KetchIcon) {
  val colors = KetchTheme.colors
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(KetchTheme.spacing.s8)
      .background(colors.accentSoft, KetchTheme.shapes.sm),
  ) {
    KetchIconImage(icon, size = KetchTheme.density.controlGlyph, tint = colors.accentText)
  }
}

/**
 * The setup checklist under the launchpad on desktop and the web: where downloads go, the
 * browser extension and control from a phone, each with what sets it up. It shows for 7 days
 * from the first time, until everything is done or it is closed.
 */
@Composable
private fun SetupChecklist(state: AppState, hooks: DesktopHooks, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val ui = state.appSettings.ui
  val integration = LocalIntegrationStatus.current
  val server by state.serverState.collectAsState()
  val active by state.activeInstance.collectAsState()
  val local = active is EmbeddedInstance
  val folder = state.instanceSettings.download?.defaultDirectory
  val clock = LocalClock.current
  val now = remember { clock.now().toEpochMilliseconds() }
  val shownAt = ui.setupChecklistShownAt
  val items = buildList {
    add(
      ChecklistItem(
        text = if (folder != null) {
          stringResource(Res.string.launchpad_checklist_folder, shortPathText(folder).resolve())
        } else {
          stringResource(Res.string.launchpad_checklist_folder_choose)
        },
        done = folder != null,
        action = stringResource(Res.string.launchpad_checklist_change),
        onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Downloads)) },
      )
    )
    if (hooks.isSupported) {
      val detected = integration.browsers.firstOrNull()?.name
        ?.let { stringResource(Res.string.launchpad_checklist_detected, it) }
      add(
        ChecklistItem(
          text = listOfNotNull(stringResource(Res.string.launchpad_checklist_extension), detected)
            .joinToString(SEPARATOR),
          done = integration.extensionConnected,
          action = if (integration.extensionConnected) {
            stringResource(Res.string.launchpad_checklist_connected)
          } else {
            stringResource(Res.string.launchpad_checklist_get_it)
          },
          onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Integration)) },
        )
      )
    }
    if (local && hooks.isSupported) {
      add(
        ChecklistItem(
          text = stringResource(
            Res.string.launchpad_checklist_phone,
            localDeviceNounInSentence().resolve(),
          ),
          done = server is ServerState.Running,
          action = stringResource(Res.string.launchpad_checklist_show_qr),
          onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Sharing)) },
        )
      )
    }
  }
  val expired = shownAt > 0 && now - shownAt > CHECKLIST_DAYS.days.inWholeMilliseconds
  if (ui.setupChecklistDismissed || expired || items.all { it.done }) return
  LaunchedEffect(shownAt) {
    if (shownAt == 0L) state.appSettings.saveUi { it.copy(setupChecklistShownAt = now) }
  }
  Column(modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      KetchEyebrow(stringResource(Res.string.launchpad_checklist_title), Modifier.weight(1f))
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = stringResource(Res.string.launchpad_checklist_close),
        onClick = { state.appSettings.saveUi { it.copy(setupChecklistDismissed = true) } },
      )
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = spacing.s1)
        .background(colors.surface, KetchTheme.shapes.lg)
        .border(HairlineWidth, colors.hairline, KetchTheme.shapes.lg)
        .padding(vertical = spacing.s1),
    ) {
      items.forEach { ChecklistRow(it) }
    }
  }
}

/**
 * One step of the setup checklist.
 *
 * @property action label of the button that sets it up.
 */
private class ChecklistItem(
  val text: String,
  val done: Boolean,
  val action: String,
  val onClick: () -> Unit,
)

@Composable
private fun ChecklistRow(item: ChecklistItem) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = spacing.s10)
      .padding(horizontal = spacing.s4),
  ) {
    if (item.done) {
      KetchIconImage(
        icon = KetchIcon.CheckCircle,
        size = KetchTheme.density.controlGlyph,
        tint = colors.status.completed.color,
      )
    } else {
      val ring = colors.textTertiary
      Box(
        Modifier.size(KetchTheme.density.controlGlyph).drawBehind {
          // The ring of the icon set's circle: 1.7 units of its 20-unit grid.
          val stroke = size.minDimension * RING_STROKE
          drawCircle(ring, radius = size.minDimension * RING_RADIUS, style = Stroke(stroke))
        }
      )
    }
    Text(
      text = item.text,
      style = KetchTheme.typography.bodyS,
      color = if (item.done) colors.textSecondary else colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    KetchButton(
      text = item.action,
      onClick = item.onClick,
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
    )
  }
}

private val log = KetchLogger("Launchpad")

private const val CHECKLIST_DAYS = 7
private const val RING_STROKE = 1.7f / 20
private const val RING_RADIUS = 7.5f / 20
private val LaunchpadWidth: Dp = 560.dp
private val PhoneIllustrationWidth: Dp = 200.dp
private val TileMinHeight: Dp = 140.dp
