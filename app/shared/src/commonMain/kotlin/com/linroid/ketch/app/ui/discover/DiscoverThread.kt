package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverDraft
import com.linroid.ketch.app.state.PageAccessChoice
import com.linroid.ketch.app.state.PageApproval
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.TurnStatus
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The thread of the shown session: every message and what the agent did about it, in [items],
 * in a column at most [ThreadWidth] wide in the middle of the page. [listState] belongs to the
 * session shown, so switching sessions never carries one's scroll over to another.
 *
 * @param inset where the column's content starts, so the thread lines up with the composer.
 * @param narrow fits the result rows to a phone.
 * @param onAnswer answers a request to open a website.
 */
@Composable
internal fun DiscoverThread(
  state: AppState,
  items: List<ThreadItem>,
  listState: LazyListState,
  inset: Dp,
  narrow: Boolean,
  focus: DiscoverFocus,
  onAnswer: (PageApproval, PageAccessChoice) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  LazyColumn(
    state = listState,
    horizontalAlignment = Alignment.CenterHorizontally,
    contentPadding = PaddingValues(top = spacing.s2, bottom = spacing.s4),
    modifier = modifier.fillMaxSize(),
  ) {
    items(items, key = { it.key }, contentType = { it::class }) { item ->
      ThreadItemView(state, item, inset, narrow, focus, onAnswer)
    }
  }
}

@Composable
private fun LazyItemScope.ThreadItemView(
  state: AppState,
  item: ThreadItem,
  inset: Dp,
  narrow: Boolean,
  focus: DiscoverFocus,
  onAnswer: (PageApproval, PageAccessChoice) -> Unit,
) {
  val controller = state.aiDiscover
  val spacing = KetchTheme.spacing
  val turn = item.turn
  // Rows and cards come and go, as results are discarded and requests answered. Placeholder rows
  // leave at once: fading, they would show through the results or the failure that replace them.
  val animate = when {
    KetchTheme.reduceMotion -> Modifier
    item is ThreadItem.Skeleton -> Modifier.animateItem(fadeOutSpec = null)
    else -> Modifier.animateItem()
  }
  val column = Modifier
    .then(animate)
    .widthIn(max = ThreadWidth)
    .fillMaxWidth()
  val content = column.padding(horizontal = inset)
  val openSettings = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) }
  when (item) {
    is ThreadItem.Message -> UserMessage(
      turn = turn,
      modifier = content.padding(top = if (item.first) spacing.s2 else spacing.s8),
    )
    is ThreadItem.Steps -> DiscoverSteps(
      turnId = turn.id,
      steps = turn.steps,
      running = turn.status == TurnStatus.Running,
      interrupted = turn.status == TurnStatus.Stopped || turn.status == TurnStatus.Failed,
      modifier = content.padding(top = spacing.s3),
    )
    is ThreadItem.Queued -> QueuedNote(content.padding(top = spacing.s3))
    is ThreadItem.Access -> AccessLine(turn.access, content.padding(top = spacing.s2))
    is ThreadItem.Approval -> ApprovalCard(
      approval = item.approval,
      mode = state.aiSettings.settings.access.mode,
      narrow = narrow,
      focusRequester = focus.approval(item.approval.id),
      onAnswer = { onAnswer(item.approval, it) },
      onSettings = openSettings,
      modifier = content.padding(top = spacing.s3),
    )
    is ThreadItem.Skeleton -> SkeletonRow(
      index = item.index,
      narrow = narrow,
      modifier = content.padding(top = if (item.index == 0) spacing.s2 else spacing.s0_5),
    )
    is ThreadItem.Summary -> AgentSummary(turn.summary, content.padding(top = spacing.s3))
    is ThreadItem.Results -> ResultsHeader(
      candidates = item.candidates,
      selected = item.candidates.count { it.url in controller.selected },
      onSelectAll = { controller.selectAll(turn.id) },
      onClearSelection = { controller.clearSelection(turn.id) },
      onDiscardAll = { state.discardDiscovered(item.candidates.map { it.url }) },
      modifier = content.padding(top = spacing.s3),
    )
    // Rows reach a little past the column's content, so their highlight frames it.
    is ThreadItem.Result -> ResultRow(
      candidate = item.candidate,
      selected = item.candidate.url in controller.selected,
      onToggle = { controller.toggle(item.candidate) },
      onDiscard = { state.discardDiscovered(listOf(item.candidate.url)) },
      padding = spacing.s2,
      narrow = narrow,
      modifier = column.padding(horizontal = inset - spacing.s2).padding(top = spacing.s0_5),
    )
    is ThreadItem.Discarded -> DiscardedLine(
      count = item.candidates.size,
      onRestore = { state.restoreDiscovered(item.candidates.map { it.url }) },
      modifier = content.padding(top = spacing.s1),
    )
    is ThreadItem.Filtered -> FilteredLine(
      count = turn.filtered,
      onSettings = openSettings,
      modifier = content.padding(top = spacing.s1),
    )
    is ThreadItem.NoResults -> NoResults(
      onSearchEverywhere = if (item.canSearchEverywhere) controller::searchEverywhere else null,
      filtered = turn.filtered,
      onSettings = openSettings,
      modifier = content.padding(top = spacing.s3),
    )
    is ThreadItem.Failed -> ProblemCard(
      message = turn.errorText?.let(::verbatim) ?: Res.string.discover_failed.text(),
      onRetry = if (item.canRetry) controller::retry else null,
      onSettings = openSettings,
      modifier = content.padding(top = spacing.s3),
    )
    is ThreadItem.Stopped -> StoppedNote(
      onRetry = if (item.canRetry) controller::retry else null,
      modifier = content.padding(top = spacing.s3),
    )
  }
}

/**
 * Where the keyboard is on a Discover page, and the requesters that move it: to the message or
 * website field, or to the first button of a request to open a website.
 */
@Stable
internal class DiscoverFocus {
  /** Moves the keyboard to the composer's message field. */
  val message: FocusRequester = FocusRequester()

  /** Moves the keyboard to the composer's website field, while it shows. */
  val sites: FocusRequester = FocusRequester()

  /** Whether the message field has the keyboard. */
  var messageFocused: Boolean by mutableStateOf(false)

  /** Whether the website field has the keyboard. */
  var sitesFocused: Boolean by mutableStateOf(false)

  /** Whether the keyboard is anywhere in the thread or the composer, Stop included. */
  var within: Boolean by mutableStateOf(false)

  /** Whether the keyboard is in the history, docked beside the chat or floating over it. */
  var historyFocused: Boolean by mutableStateOf(false)

  private val approvals = mutableMapOf<String, FocusRequester>()

  /** Whether a field of the composer has the keyboard. */
  val typing: Boolean get() = messageFocused || sitesFocused

  /**
   * Whether the user is writing: in the website field, or in the message field while [draft]
   * holds text. A key meant for the text then never lands on a request's buttons.
   */
  fun typingIn(draft: DiscoverDraft): Boolean =
    sitesFocused || messageFocused && draft.text.text.isNotEmpty()

  /** Moves the keyboard to the first button of the approval with [id]. */
  fun approval(id: String): FocusRequester = approvals.getOrPut(id) { FocusRequester() }
}

/**
 * Keeps the thread at its end while the user reads there: a message sent scrolls to it, and the
 * newest message's steps, card and results keep the end in view as they arrive, unless the user
 * has scrolled up to read; scrolling back to the end follows again. The thread also stays at its
 * end as the keyboard takes the window's height.
 */
@Composable
internal fun FollowThread(listState: LazyListState, items: List<ThreadItem>, turns: Int) {
  val follow = remember(listState) { ThreadFollow() }
  val latest by rememberUpdatedState(items)
  val turnCount by rememberUpdatedState(turns)
  val reduceMotion by rememberUpdatedState(KetchTheme.reduceMotion)
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
      .collect { (scrolling, more) ->
        // Content growing under a still thread says nothing about where the user reads.
        if (!follow.auto && (scrolling || !more)) follow.following = !more
      }
  }
  LaunchedEffect(listState) {
    var seenTurns = turnCount
    snapshotFlow { Triple(latest, turnCount, listState.layoutInfo.viewportSize.height) }
      .collect { (shown, count, _) ->
        val sent = count > seenTurns
        seenTurns = count
        if (sent) follow.following = true
        if (!follow.following || shown.isEmpty()) return@collect
        follow.auto = true
        try {
          scrollUnlessInterrupted {
            if (sent && !reduceMotion) {
              listState.animateScrollToItem(shown.lastIndex)
            } else {
              listState.scrollToItem(shown.lastIndex)
            }
          }
        } finally {
          follow.auto = false
        }
      }
  }
}

/**
 * Runs [scroll], which the user's own scrolling interrupts with a cancellation of the scroll
 * alone; the caller carries on then, and stops only when it is cancelled itself.
 */
internal suspend fun scrollUnlessInterrupted(scroll: suspend () -> Unit) {
  try {
    scroll()
  } catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
  }
}

/** Whether a thread follows its end, and whether it is scrolling there itself. */
private class ThreadFollow {
  var following = true
  var auto = false
}

/** Widest the thread, the add bar and the composer grow, in the middle of wider pages. */
internal val ThreadWidth: Dp = 760.dp
