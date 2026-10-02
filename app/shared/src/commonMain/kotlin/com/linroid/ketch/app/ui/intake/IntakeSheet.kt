package com.linroid.ketch.app.ui.intake

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchDialogDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.FilePicker
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.IntakeEntry
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeSession
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.FileDropTarget
import com.linroid.ketch.app.ui.common.ModalForm
import com.linroid.ketch.app.ui.common.modalForm
import com.linroid.ketch.app.util.toCopy
import com.linroid.ketch.config.ClipboardMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Sizes of the add sheet. */
internal object IntakeSheetDefaults {
  /** Space between the top of the window and the sheet on desktop and the web. */
  val TopOffset: Dp = 72.dp

  /** Narrowest the sheet gets while the window has room. */
  val MinWidth: Dp = 480.dp

  /** Height of a row of the list. */
  val RowHeight: Dp = 44.dp

  /** Most rows the list shows before it scrolls; short windows show fewer, at least [MIN_ROWS]. */
  const val VISIBLE_ROWS: Int = 6
  const val MIN_ROWS: Int = 2

  /** What the sheet holds besides the list, to size the list for the window. */
  val FixedChrome: Dp = 440.dp

  /** Lines the input grows to before it scrolls, and while the list shows its links. */
  const val MAX_INPUT_LINES: Int = 8
  const val INPUT_LINES_WITH_ROWS: Int = 4

  /** Share of the window's height the sheet grows to. */
  const val MAX_HEIGHT_SHARE: Float = 0.8f

  /** Height of the phone sheet's main button. */
  val PhoneButtonHeight: Dp = 56.dp

  /** Tallest the torrent picker grows, its share of the window's height, and its least. */
  val PickerMaxHeight: Dp = 420.dp
  const val PICKER_HEIGHT_SHARE: Float = 0.6f
  val PickerMinHeight: Dp = 160.dp

  /** What the sheet holds besides the torrent picker's tree, to size the tree. */
  val StageChrome: Dp = 432.dp

  /** Height of a row of the torrent picker. */
  val TreeRowHeight: Dp = 28.dp

  /** Width of the fields and pickers inside the option menus. */
  val MenuFieldWidth: Dp = 280.dp

  /** Width of the torrent picker's free-space bar. */
  val SpaceBarWidth: Dp = 96.dp

  /** Width of hairline borders and dividers. */
  val Hairline: Dp = 1.dp
}

/**
 * What the sheet's buttons and keys do beyond the session: the platform's clipboard, file
 * pickers and file actions, and closing the sheet.
 */
@Stable
internal class IntakeActions(
  val session: IntakeSession,
  val clipboard: SystemClipboard,
  val picker: FilePicker,
  val fileActions: FileActions?,
  private val scope: CoroutineScope,
  private val onClose: () -> Unit,
  private val onFinishInBackground: (() -> Unit)?,
) {
  /** Whether the sheet may close while a torrent's file list keeps loading. */
  val canFinishInBackground: Boolean get() = onFinishInBackground != null

  /** Closes the sheet, asking first when typed text would be lost. */
  fun close() {
    if (session.requestClose()) onClose()
  }

  /** Closes the sheet without asking. */
  fun discardAndClose() {
    onClose()
  }

  /** Runs the main button. */
  fun submit() {
    session.flush()
    session.submit(onDone = onClose)
  }

  /** Adds every row, failed ones included. */
  fun addAnyway() {
    session.addAllAnyway(onDone = onClose)
  }

  /** Closes the sheet and leaves the torrent's file list to load. */
  fun finishInBackground() {
    onFinishInBackground?.invoke()
  }

  /** Asks for `.torrent` files and adds them as rows. */
  fun pickTorrents() {
    scope.launch {
      catchingUnlessCancelled { picker.pickTorrentFiles() }.getOrNull()
        ?.let(session::addFiles)
    }
  }

  /** Asks for a folder on this device to save in. */
  fun pickFolder() {
    scope.launch {
      val picked = catchingUnlessCancelled { picker.pickFolder(session.folder) }.getOrNull()
      if (picked != null) session.folder = picked
    }
  }

  /** Pastes the clipboard after the input. */
  fun paste() {
    scope.launch {
      val clip = catchingUnlessCancelled { clipboard.readText() }.getOrNull() ?: return@launch
      val current = session.text.text.trimEnd()
      val text = if (current.isEmpty()) clip.trim() else "$current\n${clip.trim()}"
      session.onTextChange(TextFieldValue(text))
    }
  }

  /** Puts a cURL command from the clipboard in place of [entry], or after the input. */
  fun pasteCurl(entry: IntakeEntry? = null) {
    scope.launch {
      val clip = catchingUnlessCancelled { clipboard.readText() }.getOrNull()
      session.pasteCurl(clip, entry)
    }
  }

  /** Opens or shows the finished file of [entry]'s duplicate. */
  fun openFile(path: String, reveal: Boolean) {
    val actions = fileActions ?: return
    scope.launch {
      catchingUnlessCancelled {
        if (reveal) actions.reveal(path) else actions.open(path)
      }.onFailure { e ->
        session.notice = e.message ?: e.toCopy().title
      }
    }
  }
}

/**
 * The add sheet: a multi-line input of links, a row per link with its check on the target
 * device, the options that apply to every row, and the button that adds them.
 *
 * On desktop and the web it hangs 72 dp below the top of the window, like a command palette; in
 * compact windows it is a full-height bottom sheet with its main button at the bottom.
 */
@Composable
internal fun IntakeSheet(
  session: IntakeSession,
  onClose: () -> Unit,
  onFinishInBackground: (() -> Unit)?,
) {
  val window = LocalWindowInfo.current.containerSize
  val density = LocalDensity.current
  val width = with(density) { window.width.toDp() }
  val height = with(density) { window.height.toDp() }
  val phone = modalForm(width, isMobilePlatform) == ModalForm.Sheet
  val clipboard = rememberSystemClipboard()
  val picker = rememberFilePicker()
  val fileActions = rememberFileActions()
  val scope = rememberCoroutineScope()
  val close by rememberUpdatedState(onClose)
  val background by rememberUpdatedState(onFinishInBackground)
  val backgroundAllowed = onFinishInBackground != null
  val actions = remember(session, clipboard, picker, fileActions, scope, backgroundAllowed) {
    IntakeActions(
      session = session,
      clipboard = clipboard,
      picker = picker,
      fileActions = fileActions,
      scope = scope,
      onClose = { close() },
      onFinishInBackground = if (backgroundAllowed) {
        { background?.invoke() }
      } else {
        null
      },
    )
  }
  val available = if (phone) height else height * IntakeSheetDefaults.MAX_HEIGHT_SHARE
  val listRows = ((available - IntakeSheetDefaults.FixedChrome) / IntakeSheetDefaults.RowHeight)
    .toInt()
    .coerceIn(IntakeSheetDefaults.MIN_ROWS, IntakeSheetDefaults.VISIBLE_ROWS)
  val treeHeight = (available - IntakeSheetDefaults.StageChrome).coerceIn(
    IntakeSheetDefaults.PickerMinHeight,
    minOf(height * IntakeSheetDefaults.PICKER_HEIGHT_SHARE, IntakeSheetDefaults.PickerMaxHeight),
  )
  CompositionLocalProvider(LocalTreeHeight provides treeHeight) {
    if (phone) {
      PhoneSheet(actions, height, listRows)
    } else {
      AnchoredSheet(actions, height, listRows)
    }
  }
}

/** Tallest the torrent picker's tree grows in this window. */
internal val LocalTreeHeight = staticCompositionLocalOf { IntakeSheetDefaults.PickerMaxHeight }

@Composable
private fun AnchoredSheet(actions: IntakeActions, windowHeight: Dp, listRows: Int) {
  val spacing = KetchTheme.spacing
  val properties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
  )
  Dialog(onDismissRequest = actions::close, properties = properties) {
    // The content fills the window, so a click outside the sheet lands here.
    Box(
      contentAlignment = Alignment.TopCenter,
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(actions) { detectTapGestures { actions.close() } },
    ) {
      // The sheet is a layer of its own, above the window's drop target.
      FileDropTarget(
        onDrop = actions.session::addFiles,
        compact = true,
        modifier = Modifier
          .padding(top = IntakeSheetDefaults.TopOffset, bottom = spacing.s6)
          .padding(horizontal = spacing.s4)
          .widthIn(min = IntakeSheetDefaults.MinWidth, max = KetchDialogDefaults.IntakeMaxWidth)
          .fillMaxWidth()
          .heightIn(max = windowHeight * IntakeSheetDefaults.MAX_HEIGHT_SHARE)
          .pointerInput(Unit) { detectTapGestures {} }
          .ketchSurface(
            KetchElevationLevel.E4,
            KetchTheme.shapes.xl,
            KetchTheme.colors.surfaceRaised,
          ),
      ) {
        IntakeContent(
          actions = actions,
          phone = false,
          listRows = listRows,
          modifier = Modifier,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneSheet(actions: IntakeActions, windowHeight: Dp, listRows: Int) {
  val colors = KetchTheme.colors
  val session = actions.session
  // The sheet state is keyed on this lambda, so it must stay the same one.
  val confirmChange = remember(session) {
    { value: SheetValue -> value != SheetValue.Hidden || session.requestClose() }
  }
  val sheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = SheetValues,
    confirmValueChange = confirmChange,
  )
  ModalBottomSheet(
    onDismissRequest = actions::discardAndClose,
    sheetState = sheetState,
    shape = KetchTheme.shapes.sheetTop,
    containerColor = colors.surfaceRaised,
    contentColor = colors.textPrimary,
    scrimColor = colors.scrim,
  ) {
    IntakeContent(
      actions = actions,
      phone = true,
      listRows = listRows,
      modifier = Modifier.fillMaxWidth().heightIn(max = windowHeight).fillMaxHeight().imePadding(),
    )
  }
}

@Composable
private fun IntakeContent(
  actions: IntakeActions,
  phone: Boolean,
  listRows: Int,
  modifier: Modifier,
) {
  val session = actions.session
  val spacing = KetchTheme.spacing
  val padding = if (phone) KetchTheme.density.pagePadding else spacing.s6
  val matcher = remember { ShortcutMatcher() }
  val scroll = rememberScrollState()
  Column(
    modifier = modifier.onKeyEvent { event -> handleSheetKey(event, matcher, actions) },
  ) {
    IntakeHeader(
      actions = actions,
      phone = phone,
      modifier = Modifier
        .padding(horizontal = padding)
        .padding(top = if (phone) spacing.s1 else padding, bottom = spacing.s4),
    )
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier
        .weight(1f, fill = phone)
        .verticalScroll(scroll)
        .padding(horizontal = padding)
        .padding(bottom = spacing.s1),
    ) {
      when (session.mode) {
        IntakeMode.Edit -> EditBody(actions)
        else -> AddBody(actions, matcher, phone, listRows)
      }
    }
    // A hairline under the body tells that it scrolls on.
    Box(
      Modifier
        .fillMaxWidth()
        .height(IntakeSheetDefaults.Hairline)
        .background(if (scroll.canScrollForward) KetchTheme.colors.divider else Color.Transparent),
    )
    IntakeFooter(
      actions = actions,
      phone = phone,
      modifier = Modifier.padding(horizontal = padding).padding(top = spacing.s4, bottom = padding),
    )
  }
}

@Composable
private fun IntakeHeader(actions: IntakeActions, phone: Boolean, modifier: Modifier) {
  val session = actions.session
  val spacing = KetchTheme.spacing
  val instances by session.instances.collectAsState()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = when (session.mode) {
        IntakeMode.Add -> "Add downloads"
        IntakeMode.Retry -> "Retry with options"
        IntakeMode.Edit -> "Download options"
      },
      style = KetchTheme.typography.titleL,
      color = KetchTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (instances.size >= 2 && session.mode == IntakeMode.Add) TargetChip(session, instances)
    if (phone) {
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Close",
        onClick = actions::close,
      )
    }
  }
}

@Composable
private fun AddBody(
  actions: IntakeActions,
  matcher: ShortcutMatcher,
  phone: Boolean,
  listRows: Int,
) {
  val session = actions.session
  if (session.mode == IntakeMode.Retry) RetryProblem(session)
  val stage = session.activeStage
  if (stage != null) {
    TorrentStage(actions, stage, onBack = { session.torrentStage = null })
  } else {
    IntakeInput(actions, matcher, phone)
    session.notice?.let { NoticeLine(it, KetchIcon.Info) }
    EntriesArea(actions, phone, listRows)
  }
  OptionPills(actions)
  AnimatedVisibility(visible = session.advancedOpen) { AdvancedSection(actions) }
  session.spaceWarning?.let { NoticeLine(it, KetchIcon.Warning, warning = true) }
  session.cookieWarning?.let { NoticeLine(it, KetchIcon.Warning, warning = true) }
  session.outcome?.let { outcome ->
    val warning = session.mode == IntakeMode.Retry && session.startsOver
    NoticeLine(outcome, if (warning) KetchIcon.Warning else KetchIcon.Info, warning = warning)
  }
}

@Composable
private fun IntakeInput(actions: IntakeActions, matcher: ShortcutMatcher, phone: Boolean) {
  val session = actions.session
  val type = KetchTheme.typography
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val focus = remember { FocusRequester() }
  LaunchedEffect(session) {
    // On phones a prefilled sheet keeps the keyboard down, so its rows stay in view.
    val focusInput = session.mode == IntakeMode.Add && (!phone || session.text.text.isEmpty())
    if (focusInput) catchingUnlessCancelled { focus.requestFocus() }
  }
  val suggest = session.clipboardMode == ClipboardMode.Suggest
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
    KetchTextField(
      value = session.text,
      onValueChange = session::onTextChange,
      placeholder = if (phone) {
        "Links, magnets or cURL"
      } else {
        "Paste links, magnets or a cURL command, one per line"
      },
      mono = true,
      textStyle = type.mono.copy(
        fontSize = type.bodyS.fontSize,
        lineHeight = type.bodyS.lineHeight,
      ),
      maxLines = if (session.entries.size > 1) {
        IntakeSheetDefaults.INPUT_LINES_WITH_ROWS
      } else {
        IntakeSheetDefaults.MAX_INPUT_LINES
      },
      onPaste = if (suggest) actions::paste else null,
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focus)
        .onPreviewKeyEvent { event -> handleInputKey(event, matcher, actions) },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (session.fromClipboard) {
        Text("From clipboard", style = type.caption, color = colors.textSecondary)
        KetchIconButton(
          icon = KetchIcon.Close,
          contentDescription = "Clear the text from the clipboard",
          size = KetchButtonSize.Small,
          onClick = session::dismissClipboard,
        )
      }
      // The summary of a batch shares the line; a single link shows its own card.
      if (session.entries.isNotEmpty() && session.single == null) {
        SummaryLine(session, Modifier.weight(1f))
      } else {
        Spacer(Modifier.weight(1f))
      }
      if (session.mode != IntakeMode.Add) {
        // A retry keeps its link; files are added from a new sheet.
      } else if (phone && session.entries.isNotEmpty() && session.single == null) {
        KetchIconButton(
          command = KetchCommands.IntakeOpenTorrent,
          onClick = actions::pickTorrents,
          size = KetchButtonSize.Small,
        )
      } else {
        KetchButton(
          text = "Open .torrent…",
          onClick = actions::pickTorrents,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.FileTorrent,
          shortcut = KetchCommands.IntakeOpenTorrent.shortcutLabel(),
        )
      }
    }
  }
}

@Composable
private fun EntriesArea(actions: IntakeActions, phone: Boolean, listRows: Int) {
  val session = actions.session
  val entries = session.entries
  val single = session.single
  when {
    entries.isEmpty() -> session.discoverQuery?.let { DiscoverOffer(actions, it) }
    single != null && single.waitsForFiles -> TorrentWaiting(actions, single)
    single != null -> PreviewCard(actions, single)
    else -> EntryList(actions, entries, phone, listRows)
  }
}

@Composable
private fun SummaryLine(session: IntakeSession, modifier: Modifier) {
  Text(
    text = session.summary.text,
    style = KetchTheme.typography.caption,
    color = KetchTheme.colors.textSecondary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

@Composable
private fun DiscoverOffer(actions: IntakeActions, query: String) {
  val session = actions.session
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.lg, colors.surface, colors.hairline)
      .padding(horizontal = spacing.s3, vertical = spacing.s2),
  ) {
    KetchIconImage(KetchIcon.Discover, size = KetchTheme.density.controlGlyph, tint = colors.accent)
    Text(
      text = if (session.canDiscover) "No links here. Search Discover for \"$query\"?"
      else "No links here. Paste a link, a magnet or a cURL command.",
      style = KetchTheme.typography.bodyS,
      color = colors.textSecondary,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    if (session.canDiscover) {
      KetchButton(
        text = "Discover",
        onClick = { session.discover(query) },
        variant = KetchButtonVariant.Tonal,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Discover,
        shortcut = KetchCommands.IntakeDiscover.shortcutLabel(),
      )
    }
  }
}

@Composable
private fun RetryProblem(session: IntakeSession) {
  val task = session.task
  if (task == null) {
    NoticeLine("This download is no longer in the list.", KetchIcon.Warning, warning = true)
    return
  }
  val failed = task.state.value as? DownloadState.Failed ?: return
  val copy = failed.error.toCopy()
  ProblemCard(title = copy.title, detail = copy.hint)
}

@Composable
private fun EditBody(actions: IntakeActions) {
  val session = actions.session
  val task = session.task
  if (task == null) {
    NoticeLine("This download is no longer in the list.", KetchIcon.Warning, warning = true)
    return
  }
  TaskPreview(task)
  OptionPills(actions)
}

@Composable
private fun IntakeFooter(actions: IntakeActions, phone: Boolean, modifier: Modifier) {
  val session = actions.session
  val spacing = KetchTheme.spacing
  if (session.confirmingClose) {
    ConfirmClose(actions, modifier)
    return
  }
  val stage = session.activeStage
  val batchStage = stage != null && session.entries.size > 1
  val stageEntry = stage?.takeIf { !batchStage }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.fillMaxWidth(),
  ) {
    if (!phone) {
      KeyHints(session, inStage = stage != null, modifier = Modifier.weight(1f))
    }
    val secondary: Pair<String, () -> Unit>? = when {
      batchStage -> null
      session.mode == IntakeMode.Add && session.allFailed -> "Add anyway" to actions::addAnyway
      stageEntry != null && session.mode == IntakeMode.Add -> "Add all files" to {
        stageEntry.selectedFiles = stageEntry.files.mapTo(LinkedHashSet()) { it.id }
        actions.submit()
      }
      phone -> null
      else -> "Cancel" to actions::close
    }
    secondary?.let { (label, onClick) ->
      KetchButton(
        text = label,
        onClick = onClick,
        variant = KetchButtonVariant.Secondary,
        size = if (phone) KetchButtonSize.Large else KetchButtonSize.Medium,
        modifier = if (phone) Modifier.heightIn(min = IntakeSheetDefaults.PhoneButtonHeight)
        else Modifier,
      )
    }
    KetchButton(
      text = if (batchStage) "Done" else session.primaryLabel,
      onClick = if (batchStage) ({ session.torrentStage = null }) else actions::submit,
      enabled = batchStage || session.canSubmit,
      loading = session.submitting,
      size = if (phone) KetchButtonSize.Large else KetchButtonSize.Medium,
      modifier = if (phone) {
        Modifier.weight(1f).heightIn(min = IntakeSheetDefaults.PhoneButtonHeight)
      } else {
        Modifier
      },
    )
  }
}

@Composable
private fun KeyHints(session: IntakeSession, inStage: Boolean, modifier: Modifier) {
  val hints = buildList {
    if (session.mode == IntakeMode.Edit) return@buildList
    hint(KetchCommands.IntakeAdd, if (session.mode == IntakeMode.Retry) "Retry" else "Add")
    // The file picker has no input to type in or send to Discover.
    if (session.mode == IntakeMode.Add && !inStage) {
      hint(KetchCommands.IntakeNewLine, "New line")
      if (session.canDiscover) hint(KetchCommands.IntakeDiscover, "Discover")
      hint(KetchCommands.IntakeOpenTorrent, ".torrent")
    }
  }
  Text(
    text = hints.joinToString("  ·  "),
    style = KetchTheme.typography.labelS,
    color = KetchTheme.colors.textTertiary,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

private fun MutableList<String>.hint(command: KetchCommand, label: String) {
  command.shortcutLabel()?.let { add("$it $label") }
}

@Composable
private fun ConfirmClose(actions: IntakeActions, modifier: Modifier) {
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.fillMaxWidth(),
  ) {
    Text(
      text = "Close and discard what you typed?",
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textPrimary,
      modifier = Modifier.weight(1f),
    )
    KetchButton(
      text = "Keep editing",
      onClick = actions.session::keepEditing,
      variant = KetchButtonVariant.Secondary,
    )
    KetchButton(
      text = "Discard",
      onClick = actions::discardAndClose,
      variant = KetchButtonVariant.Danger,
    )
  }
}

/**
 * Handles the sheet's chords that bubble up from any control in it. Esc reaches the dialog or
 * sheet, which asks [IntakeActions.close].
 */
private fun handleSheetKey(
  event: KeyEvent,
  matcher: ShortcutMatcher,
  actions: IntakeActions,
): Boolean {
  val session = actions.session
  val command = matcher.match(event, ShortcutContext(overlay = CommandScope.Intake))
  return when (command) {
    KetchCommands.IntakeAdd -> {
      if (session.canSubmit) actions.submit()
      true
    }
    KetchCommands.IntakeDiscover -> session.discover()
    KetchCommands.IntakeOpenTorrent -> {
      actions.pickTorrents()
      true
    }
    else -> {
      val number = (1..9).firstOrNull { command == KetchCommands.intakeTarget(it) }
      val target = number?.let { session.instances.value.getOrNull(it - 1) }
      if (target != null) session.selectTarget(target)
      target != null
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
private val SheetValues = setOf(SheetValue.Hidden, SheetValue.Expanded)

/** Handles ↩ and ⌥↩ in the input before the field types a line break; ⇧↩ types one. */
private fun handleInputKey(
  event: KeyEvent,
  matcher: ShortcutMatcher,
  actions: IntakeActions,
): Boolean {
  val session = actions.session
  val context = ShortcutContext(
    overlay = CommandScope.Intake,
    textFieldFocused = true,
    // ↩ that confirms an input method's composition must not add.
    composing = session.text.composition != null,
  )
  return when (matcher.match(event, context)) {
    KetchCommands.IntakeAdd -> {
      session.flush()
      if (session.canSubmit) actions.submit()
      true
    }
    KetchCommands.IntakeDiscover -> {
      session.flush()
      session.discover()
      true
    }
    else -> false
  }
}
