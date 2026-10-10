package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.AiDiscoverController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.PageAccessChoice
import com.linroid.ketch.app.state.PageApproval
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.app.ui.shell.LocalPhoneBottomBarHeight
import com.linroid.ketch.app.ui.shell.ShellNavigation
import com.linroid.ketch.app.ui.shell.reportsBottomChrome
import com.linroid.ketch.config.PageAccessMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_hero_title
import ketch.app.shared.generated.resources.discover_intro_body
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

/**
 * The Discover destination: an AI search for downloads, as a chat in the user's own words.
 *
 * Until discovery is set up it shows how to set it up, with a button per model provider. Once it
 * is, it shows the shown session's thread, each message followed by the agent's steps, the
 * requests to open websites that wait for an answer, its reply and the downloads it found,
 * which can be added at once or reviewed in the add sheet first; the composer at the bottom
 * sends the first message or a follow-up. A search asked for before setup, from the add sheet or
 * the phone's search, runs as soon as setup is done.
 *
 * The history of searches docks beside the chat on wide pages, floats over it on narrower ones
 * and opens as a sheet on phones; while Discover is not set up, a search picked from it shows
 * without the composer. ⇧⌘E starts a new search and ⇧⌘H shows or hides the history; the window
 * runs them too while the keyboard is not on the page, as on the setup page.
 */
@Composable
fun DiscoverScreen(state: AppState) {
  val ai = state.aiSettings
  val available = ai.available
  val controller = state.aiDiscover
  val phone = LocalKetchLayout.current.navigation == ShellNavigation.Phone
  // Outside the shell, as in previews, the page keeps its own.
  val ownChrome = remember { DiscoverChrome() }
  val chrome = LocalDiscoverChrome.current ?: ownChrome
  val focus = remember(controller) { DiscoverFocus() }
  LaunchedEffect(available) {
    if (available) controller.runPending()
  }
  ReportVisibility(controller)
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val placement = historyPlacement(phone, maxWidth)
    val history = rememberHistoryToggle(state, chrome, placement, available)
    val historyFocus = remember { FocusRequester() }
    val newSearch = {
      controller.newSession()
      chrome.historyOpen = false
      chrome.focusComposer()
    }
    val closePopup = {
      chrome.historyOpen = false
      chrome.focusComposer()
    }
    val popupOpen = placement != HistoryPlacement.Docked && chrome.historyOpen
    // The page's own keys, which the window also runs while the keyboard is not on the page, as
    // on the setup page or a search shown without its composer.
    val onPageCommand by rememberUpdatedState { command: KetchCommand ->
      when (command) {
        // Esc closes the floating history first; otherwise the chat stops its search.
        KetchCommands.DiscoverStop -> popupOpen.also { if (it) closePopup() }
        KetchCommands.DiscoverNewSearch -> true.also { newSearch() }
        KetchCommands.DiscoverHistory -> history.offered.also { if (it) history.toggle() }
        else -> false
      }
    }
    DisposableEffect(chrome) {
      chrome.pageCommand = { onPageCommand(it) }
      onDispose { chrome.pageCommand = null }
    }
    LaunchedEffect(popupOpen, placement) {
      // A pointer user who opens the floating history lands in it.
      if (popupOpen && placement == HistoryPlacement.Overlay && !isMobilePlatform) {
        withFrameNanos {}
        runCatching { historyFocus.requestFocus() }
      }
    }
    Column(
      Modifier
        .fillMaxSize()
        .onPreviewKeyEvent { event -> handlePageKey(event, controller, focus, onPageCommand) },
    ) {
      // Phones name the page in their top bar already, which holds its buttons too.
      if (!phone) DiscoverHeader(state, available, history, newSearch)
      Row(Modifier.weight(1f).fillMaxWidth()) {
        if (placement == HistoryPlacement.Docked) {
          DockedHistory(
            state = state,
            visible = history.shown && history.offered,
            onNewSearch = newSearch,
            modifier = Modifier.onFocusChanged { focus.historyFocused = it.hasFocus },
          )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
          when {
            available -> DiscoverChat(state, phone, focus, chrome, readOnly = false)
            // A search picked from the history reads as it was left.
            controller.current != null -> DiscoverChat(state, phone, focus, chrome, readOnly = true)
            else -> DiscoverSetup(state, phone, Modifier.fillMaxSize())
          }
          if (placement == HistoryPlacement.Overlay) {
            OverlayHistory(
              state = state,
              visible = popupOpen && history.offered,
              firstFocus = historyFocus,
              onNewSearch = newSearch,
              onClose = closePopup,
              modifier = Modifier.onFocusChanged { focus.historyFocused = it.hasFocus },
            )
          }
        }
      }
    }
    if (placement == HistoryPlacement.Sheet && popupOpen && history.offered) {
      HistorySheet(
        state = state,
        onNewSearch = newSearch,
        onDismissRequest = { chrome.historyOpen = false },
      )
    }
  }
}

/**
 * Whether the history shows where [placement] puts it, and what its toggle does. Docked, it
 * follows the saved preference, but hides while there are no searches unless the user asks for
 * it then; floating, it opens and closes with [chrome].
 */
@Composable
private fun rememberHistoryToggle(
  state: AppState,
  chrome: DiscoverChrome,
  placement: HistoryPlacement,
  available: Boolean,
): HistoryToggle {
  val sessions = state.aiDiscover.sessions
  var emptyAsked by remember { mutableStateOf(false) }
  val offered = available || sessions.isNotEmpty()
  if (placement != HistoryPlacement.Docked) {
    return HistoryToggle(offered, chrome.historyOpen) { chrome.historyOpen = !chrome.historyOpen }
  }
  val docked = state.appSettings.ui.discoverHistory && (sessions.isNotEmpty() || emptyAsked)
  return HistoryToggle(offered, docked) {
    emptyAsked = !docked
    state.appSettings.saveUi { it.copy(discoverHistory = !docked) }
  }
}

/**
 * Runs the keys of the whole page through [onCommand]: Esc closes the floating history first,
 * ⇧⌘E starts a new search and ⇧⌘H toggles the history.
 */
private fun handlePageKey(
  event: KeyEvent,
  controller: AiDiscoverController,
  focus: DiscoverFocus,
  onCommand: (KetchCommand) -> Boolean,
): Boolean {
  val context = ShortcutContext(
    overlay = CommandScope.Discover,
    textFieldFocused = focus.typing,
    composing = focus.messageFocused && controller.draft.text.composition != null,
  )
  val command = DiscoverKeys.match(event, context) ?: return false
  return command in PageCommands && onCommand(command)
}

/** The Discover keys the page runs, wherever the keyboard is in it. */
private val PageCommands = setOf(
  KetchCommands.DiscoverStop,
  KetchCommands.DiscoverNewSearch,
  KetchCommands.DiscoverHistory,
)

/**
 * The shown session as a chat: its thread, or what Discover does before the first message, and
 * along the bottom the add bar and the composer, which the shell's toasts float above. When
 * [readOnly], as while Discover is not set up, a notice that says to set it up takes the
 * composer's place, and the thread offers no search to run again.
 *
 * A request to open a website takes the keyboard when it appears while the keyboard is in the
 * thread or on Stop, never out of the composer's fields, where the next key is meant for a
 * message and would answer a request the user has not read; once a request is answered from the
 * thread, the keyboard moves to the next one, or back to the composer. While the request is out
 * of view, "Needs your OK" floats over the thread and scrolls to it. Esc stops the search, ⌘↩
 * allows and ⌘⌫ denies, from the composer too while nothing is typed in it.
 */
@Composable
private fun DiscoverChat(
  state: AppState,
  phone: Boolean,
  focus: DiscoverFocus,
  chrome: DiscoverChrome,
  readOnly: Boolean,
) {
  val controller = state.aiDiscover
  val session = controller.current
  val draft = controller.draft
  val spacing = KetchTheme.spacing
  val touch = KetchTheme.density == KetchDensity.Comfortable
  val inset = if (phone) KetchTheme.density.pagePadding else spacing.pageHeaderPadding
  // Whether the keyboard shows, not its height, which changes every frame it slides.
  val ime = WindowInsets.ime
  val density = LocalDensity.current
  val keyboardVisible by remember(ime, density) {
    derivedStateOf { ime.getBottom(density) > 0 }
  }
  val waiting = session?.let { controller.waitingIn(it.id) }.orEmpty()
  val items = remember(session, waiting, readOnly) {
    session?.let { threadItems(it, waiting, canRun = !readOnly) }.orEmpty()
  }
  // Each session scrolls on its own and opens at its end, the newest message in view.
  val listState = remember(session?.id) { LazyListState(items.lastIndex.coerceAtLeast(0)) }
  FollowThread(listState, items, turns = session?.turns?.size ?: 0)
  var move by remember { mutableStateOf<FocusMove?>(null) }
  val answer = { approval: PageApproval, choice: PageAccessChoice ->
    // An answer from the composer, by a click or ⌘↩, leaves the keyboard in it.
    val moves = focus.within && !focus.typing
    controller.answer(approval.id, choice)
    if (moves) move = FocusMove.Next(approval.sessionId)
  }
  ApprovalFocus(focus, listState, items, waiting.firstOrNull()) { move = it }
  MoveFocus(move, focus, listState, items, controller::waitingIn) { move = null }
  var seenRequests by remember { mutableIntStateOf(chrome.composerRequests) }
  LaunchedEffect(focus, controller.currentId, chrome.composerRequests, readOnly) {
    val asked = chrome.composerRequests != seenRequests
    seenRequests = chrome.composerRequests
    // With a keyboard the user lands in the composer, ready to type, as ⌘E and New search ask
    // too; a phone's keyboard would cover the chat.
    if (isMobilePlatform || readOnly) return@LaunchedEffect
    withFrameNanos {}
    // A keyboard user working through the history, opening searches with ↩ or deleting them
    // with ⌫, stays in it unless the keyboard fell out of it with a row.
    if (!asked && focus.historyFocused) return@LaunchedEffect
    runCatching { focus.message.requestFocus() }
  }
  val mode = state.aiSettings.settings.access.mode
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val column = minOf(maxWidth, ThreadWidth)
    val narrow = touch || column < WideWidth
    Column(
      Modifier
        .fillMaxSize()
        .onFocusChanged { focus.within = it.hasFocus }
        .onPreviewKeyEvent { event ->
          handleChatKey(event, controller, session, focus, waiting.firstOrNull(), mode, answer)
        },
    ) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        if (session == null) {
          DiscoverIntro(
            state = state,
            onExample = { example ->
              draft.text = TextFieldValue(example, TextRange(example.length))
              controller.send()
            },
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          DiscoverThread(
            state = state,
            items = items,
            listState = listState,
            inset = inset,
            narrow = narrow,
            focus = focus,
            onAnswer = answer,
          )
          ApprovalJumpButton(listState, items, waiting.firstOrNull()) {
            move = FocusMove.Approval(it)
          }
          // A hairline under the page header while the thread runs on above, as the bottom bar
          // has one while it runs on below. The phone's top bar folds away instead.
          if (!phone) ScrolledEdge(listState, Modifier.align(Alignment.TopCenter))
        }
      }
      BottomBar(
        state = state,
        session = session,
        listState = listState,
        phone = phone,
        touch = touch,
        keyboardVisible = keyboardVisible,
        inset = inset,
        stacked = column < StackedWidth,
        focus = focus,
        readOnly = readOnly,
      )
    }
  }
}

/**
 * The add bar and the composer along the bottom of the chat, in the thread's column, over a
 * hairline while the thread runs on under them. On a phone the add bar shows only while results
 * are selected and the keyboard is down, and the keyboard lifts both, minus the bottom bar it
 * covers.
 */
@Composable
private fun BottomBar(
  state: AppState,
  session: DiscoverSession?,
  listState: LazyListState,
  phone: Boolean,
  touch: Boolean,
  keyboardVisible: Boolean,
  inset: Dp,
  stacked: Boolean,
  focus: DiscoverFocus,
  readOnly: Boolean,
) {
  val controller = state.aiDiscover
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val bar = LocalPhoneBottomBarHeight.current
  val underneath by remember(listState) { derivedStateOf { listState.canScrollForward } }
  val addBar = session != null && if (phone) {
    !keyboardVisible && controller.selectedCandidates().isNotEmpty()
  } else {
    controller.selected.isNotEmpty() || session.turns.any { session.visible(it).isNotEmpty() }
  }
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .fillMaxWidth()
      .reportsBottomChrome()
      .background(colors.surface)
      .then(
        if (phone) {
          Modifier.windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets(bottom = bar)))
        } else {
          Modifier
        },
      ),
  ) {
    Spacer(
      Modifier
        .fillMaxWidth()
        .height(HairlineWidth)
        .background(if (session != null && underneath) colors.hairline else colors.surface),
    )
    Column(
      modifier = Modifier
        .widthIn(max = ThreadWidth)
        .fillMaxWidth()
        .padding(horizontal = inset)
        .padding(top = spacing.s2, bottom = if (phone) spacing.s3 else spacing.s4),
    ) {
      AnimatedVisibility(
        visible = addBar,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
      ) {
        if (session != null) {
          DiscoverAddBar(
            state = state,
            session = session,
            phone = phone,
            stacked = stacked,
            modifier = Modifier.padding(bottom = spacing.s2),
          )
        }
      }
      if (readOnly) {
        SetupNotice(
          onSettings = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
        )
      } else {
        DiscoverComposer(
          draft = controller.draft,
          firstMessage = session == null,
          running = session?.running == true,
          touch = touch,
          // Enter follows the platform's keyboard, not the density a desktop user may choose.
          softKeyboard = isMobilePlatform,
          keyboardVisible = keyboardVisible,
          focus = focus,
          onSend = controller::send,
          onStop = { controller.stop() },
          model = { DiscoverModelChip(state) },
          // Until results bring the add bar and its chip, so the device can be picked before a
          // search, which picks builds for it. A phone's add bar keeps it in a menu.
          target = if (!phone && !addBar) {
            { DiscoverTargetChip(state) }
          } else {
            null
          },
        )
      }
    }
  }
}

/** A hairline across the top of the thread while it is scrolled away from its start. */
@Composable
private fun ScrolledEdge(listState: LazyListState, modifier: Modifier) {
  val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }
  if (!scrolled) return
  Spacer(modifier.fillMaxWidth().height(HairlineWidth).background(KetchTheme.colors.hairline))
}

/**
 * "Needs your OK ↓" over the end of the thread while [first]'s card is out of view, or only a
 * sliver of it shows, so the button never hides a card that can be read. It waits a moment
 * before it shows, so a thread that scrolls to a new card itself never flashes it.
 */
@Composable
private fun ApprovalJumpButton(
  listState: LazyListState,
  items: List<ThreadItem>,
  first: PageApproval?,
  onJump: (PageApproval) -> Unit,
) {
  val key = first?.let { approvalKey(it.turnId, it.id) }
  val readable = with(LocalDensity.current) { CardInView.roundToPx() }
  val hidden by remember(listState, key, readable) {
    derivedStateOf { key != null && !listState.showsCard(key, readable) }
  }
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(hidden) {
    if (hidden) delay(JUMP_DELAY)
    shown = hidden
  }
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(
      visible = shown && items.any { it.key == key },
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = KetchTheme.spacing.s3),
    ) {
      ApprovalJump(onClick = { first?.let(onJump) })
    }
  }
}

/**
 * Moves the keyboard to [first]'s card as it appears while the keyboard is in the thread or on
 * Stop. It never leaves the composer's fields, even an empty message field just after sending:
 * Space or Enter meant for the next message would answer the card unread. ⌘↩ and ⌘⌫ answer from
 * there, and a screen reader announces the card.
 */
@Composable
private fun ApprovalFocus(
  focus: DiscoverFocus,
  listState: LazyListState,
  items: List<ThreadItem>,
  first: PageApproval?,
  onMove: (FocusMove) -> Unit,
) {
  LaunchedEffect(first?.id) {
    val approval = first ?: return@LaunchedEffect
    val key = approvalKey(approval.turnId, approval.id)
    if (!focus.takesCard() || items.none { it.key == key }) return@LaunchedEffect
    // Give a thread that follows its end a few frames to scroll to the new card; a card the user
    // scrolled away from leaves the keyboard where it is, and the jump button points to it.
    repeat(SETTLE_FRAMES) {
      // The user may have clicked into the composer meanwhile.
      if (!focus.takesCard()) return@LaunchedEffect
      if (listState.shows(key)) {
        onMove(FocusMove.Approval(approval, appeared = true))
        return@LaunchedEffect
      }
      withFrameNanos {}
    }
  }
}

/** Whether a card that appears may take the keyboard: it is in the chat, but not typing. */
private fun DiscoverFocus.takesCard(): Boolean = within && !typing

/**
 * Runs [move]: scrolls an approval's card into the thread when needed, then focuses it, or after
 * an answer finds the next card of the session, which [waitingIn] lists.
 */
@Composable
private fun MoveFocus(
  move: FocusMove?,
  focus: DiscoverFocus,
  listState: LazyListState,
  items: List<ThreadItem>,
  waitingIn: (String) -> List<PageApproval>,
  onDone: () -> Unit,
) {
  val shown by rememberUpdatedState(items)
  LaunchedEffect(move) {
    when (move) {
      null -> return@LaunchedEffect
      is FocusMove.Next -> {
        // The agent often asks again at once: it gets a few frames to show its next card before
        // the keyboard goes back to the composer.
        fun next() = waitingIn(move.sessionId).firstOrNull { approval ->
          val key = approvalKey(approval.turnId, approval.id)
          shown.any { it.key == key }
        }
        var next = next()
        var frames = 0
        while (next == null && frames++ < SETTLE_FRAMES) {
          withFrameNanos {}
          next = next()
        }
        when {
          // The user went on in the composer meanwhile.
          focus.typing -> Unit
          next == null -> runCatching { focus.message.requestFocus() }
          else -> focusCard(next, appeared = false, focus, listState, shown)
        }
      }
      is FocusMove.Approval -> focusCard(move.approval, move.appeared, focus, listState, shown)
    }
    onDone()
  }
}

/**
 * Scrolls [approval]'s card into the thread when needed, then puts the keyboard on its first
 * button; one that just [appeared] leaves the keyboard alone if it went to the composer on the
 * way. Focusing the button also brings the whole card into view.
 */
private suspend fun focusCard(
  approval: PageApproval,
  appeared: Boolean,
  focus: DiscoverFocus,
  listState: LazyListState,
  items: List<ThreadItem>,
) {
  val key = approvalKey(approval.turnId, approval.id)
  val index = items.indexOfFirst { it.key == key }
  if (index >= 0 && !listState.showsWhole(key)) {
    // Scrolling by hand on the way there still leaves the keyboard on the card.
    scrollUnlessInterrupted { listState.animateScrollToItem(index) }
  }
  withFrameNanos {}
  if (!appeared || focus.takesCard()) {
    runCatching { focus.approval(approval.id).requestFocus() }
  }
}

/** Where the keyboard goes next in a chat. */
private sealed interface FocusMove {
  /**
   * To the first button of [approval]'s card: asked for, as by the jump button, or because the
   * card [appeared].
   */
  data class Approval(val approval: PageApproval, val appeared: Boolean = false) : FocusMove

  /**
   * After an answer, to the next card of the session with [sessionId], or else back to the
   * composer's message field.
   */
  data class Next(val sessionId: String) : FocusMove
}

/** Whether the item with [key] is on screen, if only in part. */
private fun LazyListState.shows(key: String): Boolean =
  layoutInfo.visibleItemsInfo.any { it.key == key }

/** Whether the whole item with [key] is on screen. */
private fun LazyListState.showsWhole(key: String): Boolean {
  val info = layoutInfo
  val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
  return item.offset >= info.viewportStartOffset &&
    item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
}

/**
 * Whether the card with [key] is in view: all of it, or at least [readable] pixels of it, which
 * show its question or its buttons, as on a phone with the keyboard up.
 */
private fun LazyListState.showsCard(key: String, readable: Int): Boolean {
  val info = layoutInfo
  val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
  val top = maxOf(item.offset, info.viewportStartOffset)
  val bottom = minOf(item.offset + item.size, info.viewportEndOffset - info.afterContentPadding)
  return bottom - top >= minOf(item.size, readable)
}

/**
 * Runs the Discover keys the chat itself handles: Esc, ⌘↩ and ⌘⌫. ⌘⌫ denies from an empty
 * message field too, where it has no text to delete.
 */
private fun handleChatKey(
  event: KeyEvent,
  controller: AiDiscoverController,
  session: DiscoverSession?,
  focus: DiscoverFocus,
  first: PageApproval?,
  mode: PageAccessMode,
  answer: (PageApproval, PageAccessChoice) -> Unit,
): Boolean {
  val context = ShortcutContext(
    overlay = CommandScope.Discover,
    textFieldFocused = focus.typingIn(controller.draft),
    composing = focus.messageFocused && controller.draft.text.composition != null,
  )
  return when (DiscoverKeys.match(event, context)) {
    KetchCommands.DiscoverStop -> {
      if (session?.running != true) return false
      controller.stop()
      true
    }
    KetchCommands.DiscoverAllow -> {
      answer(first ?: return false, approvalChoices(mode).primary)
      true
    }
    KetchCommands.DiscoverDeny -> {
      answer(first ?: return false, PageAccessChoice.Deny)
      true
    }
    else -> false
  }
}

/** Before the first message: what Discover does, and searches to try, which send at a click. */
@Composable
private fun DiscoverIntro(
  state: AppState,
  onExample: (String) -> Unit,
  modifier: Modifier,
) {
  val provider = state.aiSettings.settings.llm.provider
  DiscoverHero(
    title = stringResource(Res.string.discover_hero_title),
    body = stringResource(Res.string.discover_intro_body, provider.shortLabel.resolve()),
    modifier = modifier,
  ) {
    DiscoverExamples(onClick = onExample)
  }
}

/** Matches the keys of Discover's scope, shared by its composer, rows and chat. */
internal val DiscoverKeys: ShortcutMatcher by lazy { ShortcutMatcher() }

/** Below this column width, result rows fit a phone. */
private val WideWidth: Dp = 600.dp

/** Below this column width, the add bar's buttons take a row of their own. */
private val StackedWidth: Dp = 520.dp

private val HairlineWidth: Dp = 1.dp

// Frames a new card has to come into view, as the thread scrolls to it or the agent asks again
// after an answer, before the keyboard stays put.
private const val SETTLE_FRAMES = 5

// How long a card stays out of view before the jump button shows.
private val JUMP_DELAY = 400.milliseconds

// How much of a card shows when it counts as in view: its question, or its buttons.
private val CardInView: Dp = 96.dp

/**
 * Tells [controller] that the Discover page shows, and whether its window is in front: a request
 * to open a website notifies unless its session shows there. That follows the lifecycle, which
 * is resumed only while the window is in front and changes without a frame, as a hidden window
 * never renders the one that would report it lost focus. On Android, sheets and dialogs over the
 * page leave it resumed.
 */
@Composable
private fun ReportVisibility(controller: AiDiscoverController) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(controller, lifecycle) {
    controller.shown = true
    val observer = LifecycleEventObserver { _, _ ->
      controller.inFront = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      controller.shown = false
      controller.inFront = false
    }
  }
}
