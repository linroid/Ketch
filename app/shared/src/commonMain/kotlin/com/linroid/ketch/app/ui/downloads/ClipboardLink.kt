package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.clipHash
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.LinkParser
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.links
import com.linroid.ketch.app.util.urlHost
import com.linroid.ketch.config.ClipboardMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.downloads_clip_copied
import ketch.app.shared.generated.resources.downloads_clip_dismiss
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** What the page knows about a link on the clipboard. */
internal sealed interface ClipboardLink {
  /** Nothing to offer, or the clipboard is off limits. */
  data object None : ClipboardLink

  /** Probably a link, which is read only once the user asks for it. */
  data object Maybe : ClipboardLink

  /**
   * A link Ketch does not have yet, read without a notice.
   *
   * @property label its file name and site, such as "ubuntu-24.04.iso · releases.ubuntu.com".
   * @property hash the clip's hash, saved once the link was offered.
   */
  data class Found(val url: String, val label: String, val hash: String) : ClipboardLink
}

/**
 * The link on the clipboard, looked at each time the window gains focus. It is read only where
 * that shows no notice ([SystemClipboard.readsSilently]); elsewhere the platform only says
 * whether a link is probably there. Nothing is looked at when the clipboard mode is off.
 *
 * @param skipOffered leaves out a clip already offered once, by the add sheet or the chip.
 */
@Composable
internal fun rememberClipboardLink(
  state: AppState,
  clipboard: SystemClipboard,
  skipOffered: Boolean,
): ClipboardLink {
  val focused = LocalWindowInfo.current.isWindowFocused
  val ui = state.appSettings.ui
  val mode = ui.clipboardMode
  val offered = ui.lastClipHash.takeIf { skipOffered }
  val tasks by state.tasks.collectAsState()
  var link by remember { mutableStateOf<ClipboardLink>(ClipboardLink.None) }
  LaunchedEffect(clipboard, focused, mode, offered, tasks.size) {
    if (mode == ClipboardMode.Off) link = ClipboardLink.None
    if (!focused || mode == ClipboardMode.Off) return@LaunchedEffect
    link = catchingUnlessCancelled {
      when {
        clipboard.readsSilently -> {
          val text = clipboard.readText().orEmpty().trim()
          val hash = clipHash(text)
          val known = tasks.mapTo(HashSet()) { it.request.url }
          val url = LinkParser.parseIntake(text).links().map { it.url }
            .firstOrNull { it !in known }
          if (url == null || hash == offered) {
            ClipboardLink.None
          } else {
            ClipboardLink.Found(url, linkLabel(url), hash)
          }
        }
        clipboard.hasLink() -> ClipboardLink.Maybe
        else -> ClipboardLink.None
      }
    }.onFailure { log.d { "Couldn't look at the clipboard: ${it.describeCauses()}" } }
      .getOrDefault(ClipboardLink.None)
  }
  return link
}

/**
 * The clipboard the Downloads page offers copied links from: the system's unless a preview or a
 * snapshot provides another, which must never read this machine's.
 */
internal val LocalPageClipboard = staticCompositionLocalOf<SystemClipboard?> { null }

/** The [LocalPageClipboard], or the system's. */
@Composable
internal fun rememberPageClipboard(): SystemClipboard =
  LocalPageClipboard.current ?: rememberSystemClipboard()

/**
 * Adds the copied [link] at once and stops offering that clip, as the clipboard chip and the
 * Add button's split part do.
 */
internal fun addClipboardLink(state: AppState, link: ClipboardLink.Found) {
  state.appSettings.saveUi { it.copy(lastClipHash = link.hash) }
  state.quickAdd(listOf(link.url))
}

/** "ubuntu-24.04.iso · releases.ubuntu.com" for a link on the clipboard. */
internal fun linkLabel(url: String): String {
  val name = displayName(DownloadRequest(url = url))
  val host = urlHost(url)
  return listOfNotNull(name, host?.takeIf { it != name }).joinToString(" · ")
}

/** Adds pasted [text]: one link at once, several or none through the add sheet. */
internal fun addPasted(state: AppState, text: String) {
  val links = LinkParser.parseIntake(text).links()
  if (links.size == 1) {
    state.quickAdd(listOf(links.single().url))
  } else {
    state.openIntake(IntakeRequest(text = text))
  }
}

/**
 * The phone's offer to download a copied link, above the rows: "Download copied link ·
 * ubuntu-24.04.iso". A tap adds it at once; ✕ stops offering that clip.
 */
@Composable
internal fun ClipboardChip(state: AppState, modifier: Modifier = Modifier) {
  val clipboard = rememberPageClipboard()
  val scope = rememberCoroutineScope()
  val link = rememberClipboardLink(state, clipboard, skipOffered = true)
  var dismissed by remember(link) { mutableStateOf(false) }
  if (link == ClipboardLink.None || dismissed) return
  ClipboardChipRow(
    label = (link as? ClipboardLink.Found)?.label,
    onClick = {
      dismissed = true
      when (link) {
        is ClipboardLink.Found -> addClipboardLink(state, link)
        else -> scope.launch {
          val text = catchingUnlessCancelled { clipboard.readText() }.getOrNull().orEmpty()
          state.appSettings.saveUi { it.copy(lastClipHash = clipHash(text.trim())) }
          addPasted(state, text)
        }
      }
    },
    onDismiss = {
      dismissed = true
      (link as? ClipboardLink.Found)?.let { found ->
        state.appSettings.saveUi { it.copy(lastClipHash = found.hash) }
      }
    },
    modifier = modifier,
  )
}

/** The chip of [ClipboardChip]: the copied link's [label] when known. */
@Composable
internal fun ClipboardChipRow(
  label: String?,
  onClick: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.md
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier
      .padding(horizontal = spacing.s4, vertical = spacing.s1)
      .fillMaxWidth()
      .focusRing(focus.visible, shape, colors.focusRing)
      .height(spacing.s10)
      .clip(shape)
      .background(colors.accentSoft)
      .background(overlay)
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(start = spacing.s3),
  ) {
    KetchIconImage(KetchIcon.Link, size = KetchTheme.density.controlGlyph, tint = colors.accentText)
    Text(
      text = listOfNotNull(stringResource(Res.string.downloads_clip_copied), label)
        .joinToString(SEPARATOR),
      style = KetchTheme.typography.labelS,
      color = colors.accentText,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    KetchIconButton(
      icon = KetchIcon.Close,
      contentDescription = stringResource(Res.string.downloads_clip_dismiss),
      onClick = onDismiss,
      tint = colors.accentText,
    )
  }
}

private val log = KetchLogger("Downloads")
