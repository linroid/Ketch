package com.linroid.ketch.app.ui.intake

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
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
import com.linroid.ketch.app.components.KetchBottomSheet
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchDialogDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.rememberKetchSheetState
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.FilePicker
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.state.IntakeEntry
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeSession
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.DropHoverState
import com.linroid.ketch.app.ui.FileDropTarget
import com.linroid.ketch.app.ui.common.ModalForm
import com.linroid.ketch.app.ui.common.modalForm
import com.linroid.ketch.app.ui.downloads.ClipboardLink
import com.linroid.ketch.app.util.toCopy
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
  val FixedChrome: Dp = 400.dp

  /** Lines the input grows to before it scrolls, and while the list shows its links. */
  const val MAX_INPUT_LINES: Int = 8
  const val INPUT_LINES_WITH_ROWS: Int = 4

  /** Lines the empty input is tall, so it reads as a place to paste and drop. */
  const val EMPTY_INPUT_LINES: Int = 3

  /** The input's focus ring: its width and its share of the accent. */
  val FocusRingWidth: Dp = 3.dp
  const val FOCUS_RING_ALPHA: Float = 0.2f

  /** A focus ring drawn right at the edge. */
  val NoGap: Dp = 0.dp

  /** Width of the Options popover. */
  val OptionsWidth: Dp = 320.dp

  /** Space under the input's text beside its buttons, which centers one line on them. */
  val LineInset: Dp = 6.dp

  /** Widest the main button grows; a long file name ends in an ellipsis. */
  val PrimaryMaxWidth: Dp = 360.dp

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

  /** Puts the clipboard in the empty input, as its chip offers. */
  fun pasteClipboard() {
    scope.launch {
      val clip = catchingUnlessCancelled { clipboard.readText() }.getOrNull() ?: return@launch
      session.pasteClipboard(clip)
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
 * The add sheet. Empty, it is one roomy area to paste links into or drop files on; once it holds
 * a link it shows a row per link with its check on the target device, one line of options that
 * apply to every row, and the button that says what it will do.
 *
 * On desktop and the web it hangs 72 dp below the top of the window, like a command palette; in
 * compact windows it is a bottom sheet, as tall as its input while empty and full height with its
 * main button at the bottom once links are in.
 *
 * @param clipboardLink a link on the clipboard, offered as a chip while the sheet is empty.
 */
@Composable
internal fun IntakeSheet(
  session: IntakeSession,
  clipboardLink: ClipboardLink,
  onClose: () -> Unit,
  onFinishInBackground: (() -> Unit)?,
) {
  val window = LocalWindowInfo.current.containerSize
  val density = LocalDensity.current
  val width = with(density) { window.width.toDp() }
  val height = with(density) { window.height.toDp() }
  val phone = modalForm(width, isMobilePlatform) == ModalForm.Sheet
  val clipboard = rememberIntakeClipboard()
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
      PhoneSheet(actions, clipboardLink, height, listRows)
    } else {
      AnchoredSheet(actions, clipboardLink, height, listRows)
    }
  }
}

/** Tallest the torrent picker's tree grows in this window. */
internal val LocalTreeHeight = staticCompositionLocalOf { IntakeSheetDefaults.PickerMaxHeight }

@Composable
private fun AnchoredSheet(
  actions: IntakeActions,
  clipboardLink: ClipboardLink,
  windowHeight: Dp,
  listRows: Int,
) {
  val spacing = KetchTheme.spacing
  val session = actions.session
  val properties = DialogProperties(
    dismissOnBackPress = true,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
  )
  val drop = remember { DropHoverState() }
  // While the input shows, a drag lights it up; elsewhere the sheet shows the drop overlay.
  val inputShown = session.mode == IntakeMode.Add && session.activeStage == null
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
        onDrop = session::addFiles,
        compact = true,
        hover = drop.takeIf { inputShown },
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
          clipboardLink = clipboardLink,
          dropping = inputShown && drop.active,
          modifier = Modifier,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneSheet(
  actions: IntakeActions,
  clipboardLink: ClipboardLink,
  windowHeight: Dp,
  listRows: Int,
) {
  val session = actions.session
  // The sheet state is keyed on this lambda, so it must stay the same one.
  val confirmChange = remember(session) {
    { value: SheetValue -> value != SheetValue.Hidden || session.requestClose() }
  }
  KetchBottomSheet(
    onDismissRequest = actions::discardAndClose,
    sheetState = rememberKetchSheetState(confirmChange),
  ) {
    // Empty, the sheet is only as tall as its input; it takes the screen once links are in.
    val full = session.showsOptions || session.confirmingClose
    IntakeContent(
      actions = actions,
      phone = true,
      listRows = listRows,
      clipboardLink = clipboardLink,
      dropping = false,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = windowHeight)
        .then(if (full) Modifier.fillMaxHeight() else Modifier)
        .imePadding(),
    )
  }
}

@Composable
private fun IntakeContent(
  actions: IntakeActions,
  phone: Boolean,
  listRows: Int,
  clipboardLink: ClipboardLink,
  dropping: Boolean,
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
        .weight(1f, fill = phone && footerShown(session, phone))
        .verticalScroll(scroll)
        .padding(horizontal = padding)
        .padding(bottom = spacing.s1),
    ) {
      when (session.mode) {
        IntakeMode.Edit -> EditBody(actions)
        else -> AddBody(actions, matcher, phone, listRows, clipboardLink, dropping)
      }
    }
    val footer = footerShown(session, phone)
    // A hairline under the body tells that it scrolls on.
    Box(
      Modifier
        .fillMaxWidth()
        .height(IntakeSheetDefaults.Hairline)
        .background(if (scroll.canScrollForward) KetchTheme.colors.divider else Color.Transparent),
    )
    if (footer) {
      IntakeFooter(
        actions = actions,
        phone = phone,
        modifier = Modifier
          .padding(horizontal = padding)
          .padding(top = spacing.s4, bottom = padding),
      )
    } else {
      Spacer(Modifier.height(if (phone) padding else spacing.s6))
    }
  }
}

@Composable
private fun IntakeHeader(actions: IntakeActions, phone: Boolean, modifier: Modifier) {
  val session = actions.session
  val spacing = KetchTheme.spacing
  val instances by session.instances.collectAsState()
  val target = instances.size >= 2 && session.mode == IntakeMode.Add
  // A phone has no room for the target chip beside the title; it goes under it.
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier.fillMaxWidth(),
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
      if (target && !phone) TargetChip(session, instances)
      if (phone) {
        KetchIconButton(
          icon = KetchIcon.Close,
          contentDescription = "Close",
          onClick = actions::close,
        )
      }
    }
    if (target && phone) TargetChip(session, instances)
  }
}

@Composable
private fun AddBody(
  actions: IntakeActions,
  matcher: ShortcutMatcher,
  phone: Boolean,
  listRows: Int,
  clipboardLink: ClipboardLink,
  dropping: Boolean,
) {
  val session = actions.session
  if (session.mode == IntakeMode.Retry) RetryProblem(session)
  val stage = session.activeStage
  if (stage != null) {
    TorrentStage(actions, stage, onBack = { session.torrentStage = null })
  } else {
    PasteArea(actions, matcher, phone, clipboardLink, dropping)
    session.notice?.let { NoticeLine(it, KetchIcon.Info) }
    if (session.entries.isEmpty()) session.discoverQuery?.let { DiscoverOffer(actions, it) }
  }
  // The options wait until there is something to add.
  Disclosure(visible = session.showsOptions) {
    Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3)) {
      if (stage == null) EntriesArea(actions, phone, listRows)
      OptionsRow(actions)
      AnimatedVisibility(
        visible = session.advancedOpen,
        enter = expandVertically(tween(KetchTheme.motion.medium)) +
          fadeIn(tween(KetchTheme.motion.medium)),
        exit = shrinkVertically(tween(KetchTheme.motion.short)) +
          fadeOut(tween(KetchTheme.motion.short)),
      ) {
        AdvancedSection(actions)
      }
      session.spaceWarning?.let { NoticeLine(it, KetchIcon.Warning, warning = true) }
      session.cookieWarning?.let { NoticeLine(it, KetchIcon.Warning, warning = true) }
      session.outcome?.let { outcome ->
        val warning = session.mode == IntakeMode.Retry && session.startsOver
        NoticeLine(outcome, if (warning) KetchIcon.Warning else KetchIcon.Info, warning = warning)
      }
    }
  }
}

/** Shows [content] once [visible], sliding it open; still when motion is reduced. */
@Composable
private fun Disclosure(visible: Boolean, content: @Composable () -> Unit) {
  val motion = KetchTheme.motion
  AnimatedVisibility(
    visible = visible,
    enter = expandVertically(tween(motion.medium, easing = motion.easeDecelerate)) +
      fadeIn(tween(motion.medium)),
    exit = shrinkVertically(tween(motion.short, easing = motion.easeAccelerate)) +
      fadeOut(tween(motion.short)),
  ) {
    content()
  }
}

@Composable
private fun EntriesArea(actions: IntakeActions, phone: Boolean, listRows: Int) {
  val session = actions.session
  val entries = session.entries
  val single = session.single
  when {
    entries.isEmpty() -> Unit
    single != null && single.waitsForFiles -> TorrentWaiting(actions, single)
    single != null -> PreviewCard(actions, single)
    else -> EntryList(actions, entries, phone, listRows)
  }
}

@Composable
private fun DiscoverOffer(actions: IntakeActions, query: String) {
  val session = actions.session
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier.intakeCard().padding(horizontal = spacing.s3, vertical = spacing.s2),
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
  // Changing options is all this sheet does, so they show in place rather than in a popover.
  OptionsPanel(
    session = session,
    modifier = Modifier.intakeCard().padding(KetchTheme.spacing.s4),
  )
}

/** Whether the sheet has a footer: an empty phone sheet has no button to show. */
private fun footerShown(session: IntakeSession, phone: Boolean): Boolean =
  !phone || session.confirmingClose || session.showsOptions

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
  // An empty sheet has nothing to add yet; its button appears with the first link.
  val primaryShown = session.showsOptions
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.fillMaxWidth(),
  ) {
    if (!phone) {
      Spacer(Modifier.weight(1f))
      if (primaryShown && session.canSubmit && !batchStage) SubmitHint(session)
    }
    val secondary: Pair<String, () -> Unit>? = when {
      batchStage -> null
      session.mode == IntakeMode.Add && session.allFailed -> "Add anyway" to actions::addAnyway
      stageEntry != null && session.mode == IntakeMode.Add -> "Download all" to {
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
    if (primaryShown || batchStage) {
      KetchButton(
        text = if (batchStage) "Done" else session.primaryLabel,
        onClick = if (batchStage) ({ session.torrentStage = null }) else actions::submit,
        enabled = batchStage || session.canSubmit,
        loading = session.submitting,
        size = if (phone) KetchButtonSize.Large else KetchButtonSize.Medium,
        modifier = if (phone) {
          Modifier.weight(1f).heightIn(min = IntakeSheetDefaults.PhoneButtonHeight)
        } else {
          Modifier.widthIn(max = IntakeSheetDefaults.PrimaryMaxWidth)
        },
      )
    }
  }
}

/** "↩ to download": the one chord worth knowing, next to the button it presses. */
@Composable
private fun SubmitHint(session: IntakeSession) {
  val chord = KetchCommands.IntakeAdd.shortcutLabel() ?: return
  Text(
    text = "$chord to ${session.submitVerb}",
    style = KetchTheme.typography.labelS,
    color = KetchTheme.colors.textTertiary,
    maxLines = 1,
    modifier = Modifier.padding(end = KetchTheme.spacing.s1),
  )
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
      val number = command?.let(KetchCommands::intakeTargetNumber)
      val target = number?.let { session.instances.value.getOrNull(it - 1) }
      if (target != null) session.selectTarget(target)
      target != null
    }
  }
}

/** Handles ↩ and ⌥↩ in the input before the field types a line break; ⇧↩ types one. */
internal fun handleInputKey(
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
