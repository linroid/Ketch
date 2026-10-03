package com.linroid.ketch.app.ui.intake

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.state.IntakeEntry
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeSource
import com.linroid.ketch.app.state.IntakeStatus
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.util.IntakeAction
import com.linroid.ketch.app.util.displayName
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_open
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.intake_action_sign_in
import ketch.app.shared.generated.resources.intake_add_all
import ketch.app.shared.generated.resources.intake_choose_files
import ketch.app.shared.generated.resources.intake_connections
import ketch.app.shared.generated.resources.intake_download_again
import ketch.app.shared.generated.resources.intake_download_all_now
import ketch.app.shared.generated.resources.intake_finish_background
import ketch.app.shared.generated.resources.intake_keep_out
import ketch.app.shared.generated.resources.intake_password
import ketch.app.shared.generated.resources.intake_remove_named
import ketch.app.shared.generated.resources.intake_rename
import ketch.app.shared.generated.resources.intake_user_name
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The rows of a batch in a bordered group, which scrolls once it holds more than a few. */
@Composable
internal fun EntryList(
  actions: IntakeActions,
  entries: List<IntakeEntry>,
  phone: Boolean,
  maxRows: Int,
) {
  val colors = KetchTheme.colors
  val now by rememberNow(entries.any { it.status is IntakeStatus.Checking })
  val rowHeight = IntakeSheetDefaults.RowHeight
  // Half a row more than fits shows that the list scrolls.
  val maxHeight = rowHeight * maxRows + rowHeight / 2
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.lg, colors.surface, colors.hairline),
  ) {
    LazyColumn(
      modifier = Modifier.heightIn(max = maxHeight),
    ) {
      itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
        if (index > 0) {
          Box(
            Modifier
              .fillMaxWidth()
              .height(IntakeSheetDefaults.Hairline)
              .background(colors.divider),
          )
        }
        EntryRow(actions, entry, phone, now)
      }
    }
  }
}

@Composable
private fun EntryRow(actions: IntakeActions, entry: IntakeEntry, phone: Boolean, now: Instant) {
  val session = actions.session
  val spacing = KetchTheme.spacing
  var signingIn by remember(entry) { mutableStateOf(false) }
  // Rows past the first ones are checked once they are shown.
  LaunchedEffect(entry) { session.request(entry) }
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = IntakeSheetDefaults.RowHeight)
      .padding(horizontal = spacing.s3, vertical = spacing.s1),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier.heightIn(min = IntakeSheetDefaults.RowHeight - spacing.s2),
    ) {
      KetchFileTypeChip(
        fileName = entry.name,
        sourceUrl = entry.url,
        size = KetchFileTypeChipDefaults.ListSize,
      )
      Column(Modifier.weight(1f)) {
        EntryName(entry, KetchTheme.typography.bodyStrong)
        EntryStatus(entry, now)
      }
      if (!phone) EntryActions(actions, entry, onSignIn = { signingIn = true })
      RemoveButton(actions, entry)
    }
    if (phone && entryActions(actions, entry).isNotEmpty()) {
      EntryActions(
        actions = actions,
        entry = entry,
        onSignIn = { signingIn = true },
        modifier = Modifier.padding(start = KetchFileTypeChipDefaults.ListSize + spacing.s3),
      )
    }
    if (signingIn) SignInFields(onSignIn = { user, password ->
      signingIn = false
      session.signIn(entry, user, password)
    })
  }
}

/** The single row of the sheet, shown large with the way its connections would split. */
@Composable
internal fun PreviewCard(actions: IntakeActions, entry: IntakeEntry) {
  val session = actions.session
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val now by rememberNow(entry.status is IntakeStatus.Checking)
  var signingIn by remember(entry) { mutableStateOf(false) }
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.lg, colors.surface, colors.hairline)
      .padding(spacing.s4),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
      KetchFileTypeChip(
        fileName = entry.name,
        sourceUrl = entry.url,
        size = KetchFileTypeChipDefaults.LargeSize,
      )
      Column(Modifier.weight(1f)) {
        EntryName(entry, KetchTheme.typography.titleM)
        EntryStatus(entry, now)
      }
      if (session.mode == IntakeMode.Add) RemoveButton(actions, entry)
    }
    if (entryActions(actions, entry).isNotEmpty()) {
      EntryActions(actions, entry, onSignIn = { signingIn = true })
    }
    if (signingIn) SignInFields(onSignIn = { user, password ->
      signingIn = false
      session.signIn(entry, user, password)
    })
    LanesPreview(actions, entry)
  }
}

/** How the file would split over the chosen connections, redrawn as the count changes. */
@Composable
private fun LanesPreview(actions: IntakeActions, entry: IntakeEntry) {
  val session = actions.session
  val resolved = entry.resolved ?: return
  if (entry.isTorrent || resolved.totalBytes <= 0 || resolved.maxSegments <= 1) return
  val chosen = session.connections.takeIf { it > 0 } ?: session.autoConnections ?: return
  val most = minOf(resolved.maxSegments.toLong(), resolved.totalBytes).toInt()
  val connections = chosen.coerceIn(1, most)
  val segments = remember(resolved.totalBytes, connections) {
    evenSegments(resolved.totalBytes, connections)
  }
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = eyebrowText(stringResource(Res.string.intake_connections)),
        style = KetchTheme.typography.eyebrow,
        color = KetchTheme.colors.textTertiary,
        modifier = Modifier.weight(1f),
      )
      splitLabel(resolved.totalBytes, connections)?.let {
        Text(
          text = it.resolve(),
          style = KetchTheme.typography.numeralS,
          color = KetchTheme.colors.textSecondary,
        )
      }
    }
    // Write heads at each segment's start show where every connection begins.
    LaneStrip(
      state = DownloadState.Downloading(DownloadProgress(0, resolved.totalBytes)),
      segments = segments,
      height = LaneStripDefaults.CellHeight,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/** A magnet's single row while its peers have not sent the file list yet. */
@Composable
internal fun TorrentWaiting(actions: IntakeActions, entry: IntakeEntry) {
  val session = actions.session
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val now by rememberNow(true)
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.lg, colors.surface, colors.hairline)
      .padding(spacing.s4),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
      KetchFileTypeChip(
        fileName = entry.name,
        sourceUrl = entry.url,
        size = KetchFileTypeChipDefaults.LargeSize,
      )
      Column(Modifier.weight(1f)) {
        EntryName(entry, KetchTheme.typography.titleM)
        EntryStatus(entry, now)
      }
      RemoveButton(actions, entry)
    }
    LinearProgressIndicator(
      color = colors.accent,
      trackColor = colors.surfaceSunken,
      modifier = Modifier.fillMaxWidth().height(spacing.s1),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
      KetchButton(
        text = stringResource(Res.string.intake_download_all_now),
        onClick = {
          entry.addAnyway = true
          actions.submit()
        },
        variant = KetchButtonVariant.Secondary,
        size = KetchButtonSize.Small,
      )
      if (actions.canFinishInBackground) {
        KetchButton(
          text = stringResource(Res.string.intake_finish_background),
          onClick = actions::finishInBackground,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          enabled = session.entries.size == 1,
        )
      }
    }
  }
}

/** The task whose options the sheet changes. */
@Composable
internal fun TaskPreview(task: DownloadTask) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val request by task.requestState.collectAsState()
  val state by task.state.collectAsState()
  val name = displayName(request, state)
  val size = when (val current = state) {
    is DownloadState.Downloading -> current.progress.totalBytes
    is DownloadState.Paused -> current.progress.totalBytes
    is DownloadState.Completed -> current.totalBytes ?: -1
    else -> request.resolvedSource?.totalBytes ?: -1
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.lg, colors.surface, colors.hairline)
      .padding(spacing.s4),
  ) {
    KetchFileTypeChip(
      fileName = name,
      sourceUrl = request.url,
      size = KetchFileTypeChipDefaults.LargeSize,
    )
    Column(Modifier.weight(1f)) {
      Text(
        text = name,
        style = KetchTheme.typography.titleM,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      size.takeIf { it > 0 }?.let {
        Text(
          text = sizeText(it).resolve(),
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
        )
      }
      Text(
        text = request.url,
        style = KetchTheme.typography.mono,
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** The failure a retry starts from, on the failed tint. */
@Composable
internal fun ProblemCard(title: String, detail: String?) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.status.failed.soft, KetchTheme.shapes.md)
      .padding(spacing.s3),
  ) {
    KetchIconImage(
      icon = KetchIcon.Failed,
      size = KetchTheme.density.controlGlyph,
      tint = colors.status.failed.color,
    )
    Column(Modifier.weight(1f)) {
      Text(title, style = KetchTheme.typography.bodyStrong, color = colors.textPrimary)
      if (detail != null) {
        Text(detail, style = KetchTheme.typography.caption, color = colors.textSecondary)
      }
    }
  }
}

/** A one-line note with a glyph: amber for warnings, quiet otherwise. */
@Composable
internal fun NoticeLine(text: String, icon: KetchIcon, warning: Boolean = false) {
  val colors = KetchTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
  ) {
    KetchIconImage(
      icon = icon,
      size = KetchTheme.density.controlGlyph,
      tint = if (warning) colors.status.paused.color else colors.textTertiary,
    )
    Text(
      text = text,
      style = KetchTheme.typography.caption,
      color = if (warning) colors.textPrimary else colors.textSecondary,
      modifier = Modifier.weight(1f),
    )
  }
}

/** The row's name; a click turns it into a field that renames the file. */
@Composable
private fun EntryName(entry: IntakeEntry, style: TextStyle) {
  val colors = KetchTheme.colors
  var editing by remember(entry) { mutableStateOf(false) }
  val renamable = entry.source is IntakeSource.Link && !entry.isTorrent
  if (editing) {
    val original = remember(entry) { entry.name }
    var draft by remember(entry) { mutableStateOf(original) }
    val focus = remember { FocusRequester() }
    val commit = {
      editing = false
      // An unchanged name keeps following the source; a cleared one goes back to it.
      if (draft.trim() != original) entry.fileName = draft.trim()
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    KetchTextField(
      value = draft,
      onValueChange = { draft = it },
      clearable = false,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { commit() }),
      onFocusChange = { focused -> if (!focused && editing) commit() },
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focus)
        .onPreviewKeyEvent { event ->
          val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
          when {
            event.type != KeyEventType.KeyDown -> false
            enter -> {
              commit()
              true
            }
            event.key == Key.Escape -> {
              editing = false
              true
            }
            else -> false
          }
        },
    )
    return
  }
  Text(
    text = entry.name,
    style = style,
    color = colors.textPrimary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = if (renamable) {
      val rename = stringResource(Res.string.intake_rename)
      Modifier.clickable(onClickLabel = rename, role = Role.Button) { editing = true }
    } else {
      Modifier
    },
  )
}

@Composable
private fun EntryStatus(entry: IntakeEntry, now: Instant) {
  val colors = KetchTheme.colors
  val line = statusLine(entry, now)
  val text = line.text.resolve()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
  ) {
    if (line.tone == LineTone.Checking) KetchSpinner()
    line.badge?.let {
      KetchBadge(it.resolve(), tone = KetchBadgeTone.Warning)
    }
    if (text.isNotEmpty()) {
      // A problem's advice is often longer than the row; hovering shows all of it.
      KetchTooltip(text = text, enabled = line.tone != LineTone.Neutral) {
        Text(
          text = text,
          style = KetchTheme.typography.caption,
          color = when (line.tone) {
            LineTone.Danger -> colors.status.failed.color
            LineTone.Checking -> colors.textTertiary
            else -> colors.textSecondary
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/** One button a row offers; a [sparkle] marks one that asks Discover. */
private class RowButton(
  val label: UiText,
  val primary: Boolean = false,
  val sparkle: Boolean = false,
  val onClick: () -> Unit,
)

/** The buttons [entry] offers for its problem, its duplicate, or its torrent files. */
private fun entryActions(
  actions: IntakeActions,
  entry: IntakeEntry,
  onSignIn: () -> Unit = {},
): List<RowButton> {
  val session = actions.session
  val duplicate = entry.duplicate
  if (duplicate != null) {
    if (entry.downloadAgain) {
      return listOf(RowButton(Res.string.intake_keep_out.text()) { entry.downloadAgain = false })
    }
    val completed = duplicate.state.value as? DownloadState.Completed
    val files = actions.fileActions.takeIf { session.target is EmbeddedInstance }
    return buildList {
      if (completed != null && files != null) {
        add(RowButton(Res.string.action_open.text()) {
          actions.openFile(completed.outputPath, reveal = false)
        })
        if (files.revealLabel != null) {
          add(RowButton(Res.string.action_show.text()) {
            actions.openFile(completed.outputPath, reveal = true)
          })
        }
      }
      add(RowButton(Res.string.intake_download_again.text()) { entry.downloadAgain = true })
    }
  }
  val problem = (entry.status as? IntakeStatus.Problem)?.problem
  if (problem != null && !entry.addAnyway) {
    return problem.actions.mapNotNull { action ->
      val label = action.label.text()
      when (action) {
        IntakeAction.SignIn -> RowButton(label, primary = true, onClick = onSignIn)
        IntakeAction.PasteCurl -> RowButton(label, primary = true) { actions.pasteCurl(entry) }
        IntakeAction.AddHeaders -> RowButton(label) { session.updateAdvancedOpen(true) }
        IntakeAction.FindMirror -> RowButton(label, sparkle = true) {
          session.discover(entry.name)
        }
        IntakeAction.Retry -> RowButton(label) { session.retry(entry) }
        IntakeAction.KeepWaiting -> RowButton(label) { session.keepWaiting(entry) }
        IntakeAction.AddAnyway -> RowButton(label) { entry.addAnyway = true }
      }
    }
  }
  if (entry.isTorrent) {
    if (entry.files.isNotEmpty()) {
      return listOf(
        RowButton(Res.string.intake_choose_files.text()) { session.torrentStage = entry },
      )
    }
    if (session.entries.size > 1 && entry.waitsForFiles && !entry.addAnyway) {
      return listOf(RowButton(Res.string.intake_add_all.text()) { entry.addAnyway = true })
    }
  }
  return emptyList()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryActions(
  actions: IntakeActions,
  entry: IntakeEntry,
  onSignIn: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val buttons = entryActions(actions, entry, onSignIn)
  if (buttons.isEmpty()) return
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = modifier,
  ) {
    for (button in buttons) {
      val label = button.label.resolve()
      KetchButton(
        text = if (button.sparkle) "✦ $label" else label,
        onClick = button.onClick,
        variant = if (button.primary) KetchButtonVariant.Tonal else KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
  }
}

@Composable
private fun RemoveButton(actions: IntakeActions, entry: IntakeEntry) {
  KetchIconButton(
    icon = KetchIcon.Close,
    contentDescription = stringResource(Res.string.intake_remove_named, entry.name),
    size = KetchButtonSize.Small,
    onClick = { actions.session.remove(entry) },
  )
}

/** User name and password for a link that needs a sign-in. */
@Composable
private fun SignInFields(onSignIn: (user: String, password: String) -> Unit) {
  val spacing = KetchTheme.spacing
  var user by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  Row(
    verticalAlignment = Alignment.Bottom,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    KetchTextField(
      value = user,
      onValueChange = { user = it },
      label = stringResource(Res.string.intake_user_name),
      modifier = Modifier.weight(1f),
    )
    KetchTextField(
      value = password,
      onValueChange = { password = it },
      label = stringResource(Res.string.intake_password),
      clearable = false,
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(
        onDone = { if (user.isNotBlank()) onSignIn(user, password) },
      ),
      modifier = Modifier.weight(1f),
    )
    KetchButton(
      text = stringResource(Res.string.intake_action_sign_in),
      onClick = { onSignIn(user, password) },
      enabled = user.isNotBlank(),
      variant = KetchButtonVariant.Tonal,
    )
  }
}

/** The current time, ticking every second while [ticking], for "0:14" waits. */
@Composable
internal fun rememberNow(ticking: Boolean): State<Instant> {
  val clock = LocalClock.current
  return produceState(clock.now(), ticking) {
    value = clock.now()
    while (ticking) {
      delay(1.seconds)
      value = clock.now()
    }
  }
}

/** [totalBytes] split into [count] equal segments with nothing downloaded. */
internal fun evenSegments(totalBytes: Long, count: Int): List<Segment> {
  val size = totalBytes / count
  return List(count) { index ->
    val start = index * size
    val end = if (index == count - 1) totalBytes - 1 else start + size - 1
    Segment(index, start, end, 0)
  }
}
