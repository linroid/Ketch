package com.linroid.ketch.app.ui.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.linroid.ketch.app.App
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.snapshot.BlenderPlan
import com.linroid.ketch.app.snapshot.BlenderSteps
import com.linroid.ketch.app.snapshot.Candidates
import com.linroid.ketch.app.snapshot.DiscoverScript
import com.linroid.ketch.app.snapshot.SavedSessions
import com.linroid.ketch.app.snapshot.SnapshotClock
import com.linroid.ketch.app.snapshot.SnapshotScene
import com.linroid.ketch.app.snapshot.SnapshotSize
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.snapshot.UBUNTU_ID
import com.linroid.ketch.app.snapshot.discovery
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.sendKey
import com.linroid.ketch.app.snapshot.withEnvironment
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.state.AccessNote
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.DiscoverTurn
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.TurnStatus
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.PageAccessSettings
import com.linroid.ketch.config.SiteNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Discover page of the desktop app, driven through what it renders. */
class DiscoverRenderTest {
  @Test
  fun composer_enter_sendsTheMessage() {
    runDiscover { state ->
      typeMessage(QUERY)
      sendKey(Key.Enter)
      frames(FRAMES)

      assertEquals(listOf(QUERY), state.aiDiscover.sessions.map { it.query })
      assertEquals("", state.aiDiscover.draft.text.text, "Sending empties the composer")
    }
  }

  @Test
  fun composer_shiftEnter_keepsTheMessageUnsent() {
    runDiscover { state ->
      typeMessage(QUERY)
      sendKey(Key.Enter, shift = true)
      frames(FRAMES)

      assertTrue(state.aiDiscover.sessions.isEmpty(), "⇧↩ starts a line instead of sending")
      assertTrue(state.aiDiscover.draft.text.text.startsWith(QUERY))
    }
  }

  @Test
  fun composer_escapeWhileSearching_stopsTheSearch() {
    runDiscover(DiscoverScript.Running) { state ->
      typeMessage(QUERY)
      sendKey(Key.Enter)
      frames(FRAMES)
      assertEquals(TurnStatus.Running, state.shown().turns.single().status)

      sendKey(Key.Escape)
      frames(FRAMES)

      assertEquals(TurnStatus.Stopped, state.shown().turns.single().status)
      assertTrue("Try again" in texts(), "The stopped search offers to run again: ${texts()}")
    }
  }

  @Test
  fun steps_details_listsEveryStepInOrderWithAllItSaid() {
    // More steps than the line of steps shows, so the first ones hide behind "3 earlier".
    val steps = BlenderSteps + (1..5).map {
      DiscoveryStep("Opened mirror $it", "Mirror $it serves the same file")
    }
    val session = savedSession(steps)
    runDiscover(history = listOf(session)) { state ->
      state.aiDiscover.open(session.id)
      frames(FRAMES)
      assertTrue("3 earlier" in texts(), "The first steps hide: ${texts()}")
      assertFalse(BlenderPlan.detail in texts(), "Details starts folded")
      // Screen readers hear it collapsed, then expanded.
      assertTrue(SemanticsActions.Expand in detailsToggle().config)

      detailsToggle().click()
      frames(FRAMES)
      assertTrue(SemanticsActions.Collapse in detailsToggle().config)

      // The plan among them, with its lines.
      val details = nodes().mapNotNull { it.ownText() }.filter { text ->
        steps.any { it.detail == text }
      }
      assertEquals(steps.map { it.detail }, details)
      assertTrue(steps.all { it.title in texts() }, "Every title shows: ${texts()}")
    }
  }

  @Test
  fun steps_runningPlan_showMoreUnfoldsItAndDetailsListsTheStepsBefore() {
    runDiscover(DiscoverScript.Planning) { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      val folded = planNodes().single().boundsInRoot.height
      assertTrue(SHOW_MORE in texts(), "A plan longer than four lines folds: ${texts()}")

      nodes().first { it.ownText() == SHOW_MORE }.click()
      frames(FRAMES)

      assertTrue(planNodes().single().boundsInRoot.height > folded, "Show more unfolds it")
      assertTrue(SHOW_LESS in texts())
      assertEquals(TurnStatus.Running, state.shown().turns.single().status)

      // Details lists the steps before the running one, which stays where it is.
      val toggle = nodes().first { it.ownText() == DETAILS }
      val top = toggle.boundsInRoot.top
      toggle.click()
      frames(FRAMES)
      assertTrue(BlenderSteps.first().detail in texts(), "The step before shows: ${texts()}")
      assertEquals(1, planNodes().size, "The plan shows once")
      assertTrue(SHOW_LESS in texts(), "The running step keeps its fold")
      assertEquals(top, nodes().first { it.ownText() == DETAILS }.boundsInRoot.top)
    }
  }

  @Test
  fun approvalCard_askForEachNewSite_allowLetsTheSiteInForTheChat() {
    runDiscover(DiscoverScript.Approval) { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      val shown = texts()
      assertTrue("Open www.blender.org?" in shown, "The card asks: $shown")
      assertTrue("Always allow blender.org" in shown)
      assertTrue("Allow all sites in this chat" in shown)
      assertTrue("Allows blender.org and its subdomains in this chat" in shown)
      assertFalse("Allow once" in shown)
      assertFalse(shown.any { it.startsWith("Redirected from") }, "Only a redirect says so")

      nodes().first { it.ownText() == "Allow" }.click()
      frames(FRAMES)

      val next = state.aiDiscover.approvals.single()
      assertEquals("mirror.example.edu", next.request.host)
      assertEquals(
        listOf(AccessNote("www.blender.org", allowed = true)),
        state.shown().turns.single().access,
      )
    }
  }

  @Test
  fun approvalCard_redirect_namesTheSiteItCameFrom() {
    runDiscover(DiscoverScript.Redirect) { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      val shown = texts()

      val host = state.aiDiscover.approvals.single().request.host
      assertTrue("Follow a redirect to $host?" in shown, "The card asks: $shown")
      assertTrue("Redirected from github.com" in shown, "It names where it came from: $shown")
    }
  }

  @Test
  fun approvalCard_askEveryTime_offersAllowOnceAndTheSiteForTheChat() {
    val access = PageAccessSettings(mode = PageAccessMode.AskEveryTime)
    runDiscover(DiscoverScript.Approval, access = access) { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      val shown = texts()
      assertTrue("Allow once" in shown, "The card asks every time: $shown")
      assertTrue("Allow blender.org in this chat" in shown)
      assertTrue("Always allow blender.org" in shown)
      assertFalse("Allows blender.org and its subdomains in this chat" in shown)

      nodes().first { it.ownText() == "Deny" }.click()
      frames(FRAMES)

      assertEquals("mirror.example.edu", state.aiDiscover.approvals.single().request.host)
      assertEquals(
        listOf(AccessNote("www.blender.org", allowed = false)),
        state.shown().turns.single().access,
      )
    }
  }

  @Test
  fun approvalCard_appearingWhileTheComposerHasTheKeyboard_leavesItThereAndPlainKeysNeverAnswer() {
    runDiscover(DiscoverScript.Approval) { state ->
      typeMessage(QUERY)
      sendKey(Key.Enter)
      frames(FRAMES)
      assertTrue(focusedNode()?.isField() == true, "The message field keeps the keyboard")

      // Keys meant for the next message.
      sendKey(Key.Spacebar)
      sendKey(Key.Enter)
      frames(FRAMES)
      assertTrue(state.shown().turns.single().access.isEmpty(), "Nothing is answered unread")
      assertEquals("www.blender.org", state.aiDiscover.approvals.single().request.host)

      pressPrimary(Key.Enter)
      assertEquals(
        listOf(AccessNote("www.blender.org", allowed = true)),
        state.shown().turns.single().access,
      )
      assertTrue(focusedNode()?.isField() == true, "An answer from the composer stays there")

      // ⌘⌫ denies from the empty message field, which has nothing to delete.
      pressPrimary(Key.Backspace)
      assertEquals(
        listOf(
          AccessNote("www.blender.org", allowed = true),
          AccessNote("mirror.example.edu", allowed = false),
        ),
        state.shown().turns.single().access,
      )
    }
  }

  @Test
  fun approvalCard_appearingWhileStopHasTheKeyboard_takesItAndAnswersMoveItOn() {
    runDiscover(DiscoverScript.Results, followUp = DiscoverScript.Approval) { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      typeMessage(FOLLOW_UP, placeholder = FOLLOW_UP_PLACEHOLDER)
      sendKey(Key.Enter)
      // Before the search asks: Stop shows once the scene recomposes, and the agent waits for a
      // frame to run.
      Snapshot.sendApplyNotifications()
      render(SnapshotClock.nanos)
      nodes().first { it.ownText() == STOP }.focus()
      assertEquals(STOP, focusedText())
      frames(FRAMES)
      assertEquals("Allow", focusedText(), "The card's first button takes the keyboard")

      sendKey(Key.Enter)
      frames(FRAMES)
      assertEquals(
        listOf(AccessNote("www.blender.org", allowed = true)),
        state.shown().turns.last().access,
      )
      assertEquals("mirror.example.edu", state.aiDiscover.approvals.single().request.host)
      assertEquals("Allow", focusedText(), "The next card the agent asks takes the keyboard")

      sendKey(Key.Enter)
      frames(FRAMES)
      assertTrue(state.aiDiscover.approvals.isEmpty())
      assertTrue(focusedNode()?.isField() == true, "With no card left, the composer has it")
    }
  }

  @Test
  fun composer_tab_movesToTheWebsiteChipWithoutTypingATab() {
    runDiscover { state ->
      typeMessage(QUERY)

      sendKey(Key.Tab)
      frames(FRAMES)

      assertEquals(SITES_CHIP, focusedText())
      assertEquals(QUERY, state.aiDiscover.draft.text.text)
      sendKey(Key.Tab, shift = true)
      frames(FRAMES)
      assertTrue(focusedNode()?.isField() == true, "⇧Tab goes back to the message")
    }
  }

  @Test
  fun historyRow_deleteKeyOnTheShownSearch_keepsTheKeyboardInTheHistory() {
    runDiscover(history = SavedSessions) { state ->
      state.aiDiscover.open(UBUNTU_ID)
      frames(FRAMES)
      val rows = nodes().filter { it.hasCustomAction(DELETE_SEARCH) }
      rows.first().config[SemanticsActions.RequestFocus].action?.invoke()
      frames(FRAMES)
      val before = state.aiDiscover.sessions.size

      sendKey(if (KeyboardPlatform.current.isApple) Key.Backspace else Key.Delete)
      frames(FRAMES)
      assertEquals(before - 1, state.aiDiscover.sessions.size)
      assertEquals(null, state.aiDiscover.currentId)
      assertTrue(focusedNode()?.hasCustomAction(DELETE_SEARCH) == true, "The next row has it")

      // So the next ⌫ deletes the next search too.
      sendKey(if (KeyboardPlatform.current.isApple) Key.Backspace else Key.Delete)
      frames(FRAMES)
      assertEquals(before - 2, state.aiDiscover.sessions.size)
    }
  }

  @Test
  fun notSetUp_historyKey_togglesTheHistoryWithNothingFocused() {
    runDiscover(configured = false, history = SavedSessions) { state ->
      val shown = state.appSettings.ui.discoverHistory

      pressPrimary(Key.H, shift = true)
      assertEquals(!shown, state.appSettings.ui.discoverHistory, "On the setup page")

      state.aiDiscover.open(UBUNTU_ID)
      frames(FRAMES)
      pressPrimary(Key.H, shift = true)
      assertEquals(shown, state.appSettings.ui.discoverHistory, "On a search without a composer")
    }
  }

  @Test
  fun notSetUp_searchAskedForWhileASavedSearchShows_showsTheSetupPageSayingItWaits() {
    runDiscover(configured = false, history = SavedSessions) { state ->
      state.aiDiscover.open(UBUNTU_ID)
      frames(FRAMES)

      state.openDiscover(DiscoverRequest("ubuntu 24.04 iso"))
      frames(FRAMES)

      assertEquals(null, state.aiDiscover.currentId)
      assertTrue(texts().any { "ubuntu 24.04 iso" in it }, "The setup page says: ${texts()}")
    }
  }

  @Test
  fun approvalJump_cardScrolledAway_bringsItBackWithTheKeyboardOnAllow() {
    runDiscover(DiscoverScript.Results, followUp = DiscoverScript.Approval) { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      typeMessage(FOLLOW_UP, placeholder = FOLLOW_UP_PLACEHOLDER)
      sendKey(Key.Enter)
      frames(FRAMES)
      assertEquals(0, jumpButtons().size, "The card shows at the end of the thread")

      // Reads back up the thread, and the card leaves the screen.
      SnapshotScene(this, scale = 1f).scroll(x = 900.dp, y = 300.dp, ticks = -SCROLL_TICKS)
      frames(JUMP_FRAMES)
      val jump = jumpButtons().single()

      jump.click()
      frames(JUMP_FRAMES)

      assertEquals("Allow", focusedText(), "The card's first button takes the keyboard")
      assertEquals(0, jumpButtons().size, "The card is back in view")
    }
  }

  @Test
  fun resultRow_discardKey_discardsTheFocusedRowAndFocusesTheNextOne() {
    runDiscover { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      val rows = nodes().filter { it.hasCustomAction(DISCARD) }
      rows.first().config[SemanticsActions.RequestFocus].action?.invoke()
      frames(FRAMES)

      sendKey(if (KeyboardPlatform.current.isApple) Key.Backspace else Key.Delete)
      frames(FRAMES)

      assertEquals(setOf(SiteNames.canonicalUrl(Candidates[0].url)), state.shown().discarded)
      val focused = nodes().single { it.config.getOrNull(SemanticsProperties.Focused) == true }
      assertTrue(focused.hasCustomAction(DISCARD), "The next row has the keyboard: $focused")
    }
  }

  @Test
  fun resultRow_discardAction_hidesItWithUndoAndRestoreBringsItBack() {
    runDiscover { state ->
      state.openDiscover(DiscoverRequest(QUERY))
      frames(FRAMES)
      val first = Candidates.first()

      customAction(DISCARD).invoke()
      frames(FRAMES)

      assertEquals(setOf(SiteNames.canonicalUrl(first.url)), state.shown().discarded)
      val shown = texts()
      assertTrue("Discarded 1 result" in shown, "A toast offers Undo: $shown")
      assertTrue(shown.any { it.startsWith("1 discarded") }, "The turn notes it: $shown")

      nodes().first { it.ownText() == "Restore" }.click()
      frames(FRAMES)

      assertTrue(state.shown().discarded.isEmpty())
    }
  }

  @Test
  fun historyRow_deleteAction_deletesTheSearchWithUndo() {
    runDiscover(history = SavedSessions) { state ->
      state.aiDiscover.open(UBUNTU_ID)
      frames(FRAMES)
      val before = state.aiDiscover.sessions.size

      customAction(DELETE_SEARCH).invoke()
      frames(FRAMES)

      assertEquals(before - 1, state.aiDiscover.sessions.size)
      assertTrue(state.aiDiscover.sessions.none { it.id == UBUNTU_ID }, "The first row is deleted")
      assertEquals(null, state.aiDiscover.currentId, "A new search shows in its place")
      assertTrue("Undo" in texts(), "A toast offers Undo: ${texts()}")
    }
  }

  @Test
  fun page_windowLeavesTheFront_reportsItWithoutAFrame() {
    val window = WindowLifecycle()
    runDiscover(window = window) { state ->
      assertTrue(state.aiDiscover.visible, "Discover shows in a window in front")

      // A window hidden in the tray renders no frame, so the page cannot wait for one.
      window.registry.currentState = Lifecycle.State.STARTED
      assertTrue(state.aiDiscover.shown)
      assertFalse(state.aiDiscover.visible)

      window.registry.currentState = Lifecycle.State.RESUMED
      assertTrue(state.aiDiscover.visible)
      state.showDownloads()
      frames(FRAMES)
      assertFalse(state.aiDiscover.shown)
      assertFalse(state.aiDiscover.visible)
    }
  }

  @Test
  fun notSetUp_savedSearch_showsItWithoutTheComposer() {
    runDiscover(configured = false, history = SavedSessions) { state ->
      state.aiDiscover.open(UBUNTU_ID)
      frames(FRAMES)
      val shown = texts()

      assertTrue("Set up Discover to continue" in shown, "The composer gives way: $shown")
      assertTrue("Only the arm64 image, for a Raspberry Pi" in shown, "The thread shows")
      assertFalse(FOLLOW_UP_PLACEHOLDER in shown, "No message can be typed")
      assertFalse("Try again" in shown)
    }
  }

  @Test
  fun menu_turnOff_hidesDiscoverUntilUndo() {
    runDiscover { state ->
      val more = nodes().filter {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(MORE) == true
      }
      more.single().click()
      frames(FRAMES)
      nodes().first { it.ownText() == TURN_OFF }.click()
      frames(FRAMES)

      assertFalse(state.aiSettings.offered)
      assertFalse(state.aiDiscover.shown, "The shell leaves Discover")
      assertTrue(TURNED_OFF in texts(), "A toast says so: ${texts()}")

      nodes().first { it.ownText() == UNDO }.click()
      frames(FRAMES)

      assertTrue(state.aiSettings.offered)
      assertTrue(state.aiDiscover.shown, "Undo shows Discover again")
    }
  }

  @Test
  fun notSetUp_turnOff_hidesDiscover() {
    runDiscover(configured = false) { state ->
      assertTrue(state.aiDiscover.shown, "Discover shows before it is set up")

      nodes().first { it.ownText() == TURN_OFF }.click()
      frames(FRAMES)

      assertFalse(state.aiSettings.settings.enabled)
      assertFalse(state.aiDiscover.shown, "The shell leaves Discover")
      assertFalse(texts().any { it == "Discover" }, "Discover leaves the sidebar: ${texts()}")
    }
  }

  /**
   * Renders the desktop app on Discover, set up with Anthropic unless not [configured], with
   * the sample discovery running [script] for a chat's first message and [followUp] for the
   * others, and runs [test] on it.
   */
  private fun runDiscover(
    script: DiscoverScript = DiscoverScript.Results,
    followUp: DiscoverScript = script,
    configured: Boolean = true,
    history: List<DiscoverSession> = emptyList(),
    access: PageAccessSettings = PageAccessSettings(),
    window: LifecycleOwner? = null,
    test: suspend ImageComposeScene.(AppState) -> Unit,
  ) {
    withEnvironment(
      create = {
        discovery(
          theme = SnapshotTheme.Light,
          size = SnapshotSize.Desktop,
          script = script,
          followUp = followUp,
          configured = configured,
          history = history,
          access = access,
        )
      },
    ) { environment ->
      val state = environment.controller.state
      val content = @Composable {
        if (window == null) {
          App(environment.controller)
        } else {
          CompositionLocalProvider(LocalLifecycleOwner provides window) {
            App(environment.controller)
          }
        }
      }
      withScene(WIDTH, HEIGHT, content = content) {
        frames(FRAMES)
        state.runInShell(KetchCommands.Discover)
        frames(FRAMES)
        test(state)
      }
    }
  }

  /**
   * Puts the keyboard in the composer's message field, found by its [placeholder], and types
   * [text] into it.
   */
  private suspend fun ImageComposeScene.typeMessage(
    text: String,
    placeholder: String = PLACEHOLDER,
  ) {
    val field = nodes().first { node ->
      node.isField() && node.subtree().any { it.ownText() == placeholder }
    }
    field.config[SemanticsActions.RequestFocus].action?.invoke()
    field.config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
    frames(FRAMES)
  }

  /** The button that folds Details open or closed. */
  private fun ImageComposeScene.detailsToggle(): SemanticsNode =
    checkNotNull(nodes().first { it.ownText() == DETAILS }.clickableAround())

  /** The texts that show the running plan of [BlenderPlan]. */
  private fun ImageComposeScene.planNodes(): List<SemanticsNode> =
    nodes().filter { it.ownText() == BlenderPlan.detail }

  /** A finished search for [QUERY] that reported [steps] and found nothing. */
  private fun savedSession(steps: List<DiscoveryStep>): DiscoverSession {
    val at = Instant.parse("2026-10-01T13:06:00Z")
    val turn = DiscoverTurn(
      id = "turn-steps",
      message = QUERY,
      sites = emptyList(),
      startedAt = at,
      status = TurnStatus.Done,
      steps = steps,
    )
    return DiscoverSession("saved-steps", QUERY, createdAt = at, updatedAt = at, listOf(turn))
  }

  /** The first screen-reader action labelled [label]. */
  private fun ImageComposeScene.customAction(label: String): () -> Boolean {
    val action = nodes().firstNotNullOfOrNull { node ->
      node.config.getOrNull(SemanticsActions.CustomActions)?.firstOrNull { it.label == label }
    }
    return assertNotNull(action, "Nothing offers $label").action
  }

  /** Presses [key] with ⌘, or Ctrl off Apple keyboards, and with Shift when [shift] is set. */
  private suspend fun ImageComposeScene.pressPrimary(key: Key, shift: Boolean = false) {
    val apple = KeyboardPlatform.current.isApple
    sendKey(key, meta = apple, ctrl = !apple, shift = shift)
    frames(FRAMES)
  }

  /** The control that has the keyboard, or `null`. */
  private fun ImageComposeScene.focusedNode(): SemanticsNode? =
    nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }

  /** The text of the control that has the keyboard, or `null`. */
  private fun ImageComposeScene.focusedText(): String? =
    focusedNode()?.subtree()?.firstNotNullOfOrNull { it.ownText() }

  /** "Needs your OK" over the thread, as buttons; the history's status line says it too. */
  private fun ImageComposeScene.jumpButtons(): List<SemanticsNode> =
    nodes().filter { node ->
      node.ownText() == NEEDS_OK && node.clickableAround()?.let {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Button
      } == true
    }

  private fun SemanticsNode.clickableAround(): SemanticsNode? {
    var node: SemanticsNode? = this
    while (node != null && SemanticsActions.OnClick !in node.config) node = node.parent
    return node
  }

  private fun SemanticsNode.hasCustomAction(label: String): Boolean =
    config.getOrNull(SemanticsActions.CustomActions).orEmpty().any { it.label == label }

  private fun AppState.shown(): DiscoverSession = checkNotNull(aiDiscover.current)

  /** Every text shown. */
  private fun ImageComposeScene.texts(): Set<String> = nodes().mapNotNull { it.ownText() }.toSet()

  private fun SemanticsNode.ownText(): String? =
    config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }

  private fun SemanticsNode.isField(): Boolean = SemanticsActions.SetText in config

  private fun SemanticsNode.subtree(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.subtree() }

  /** Puts the keyboard on [this] node, or the closest focusable one around it. */
  private fun SemanticsNode.focus() {
    var node: SemanticsNode? = this
    while (node != null && SemanticsActions.RequestFocus !in node.config) node = node.parent
    val action = node?.config?.get(SemanticsActions.RequestFocus)?.action
    assertTrue(action != null, "Nothing to focus around $this")
    action()
  }

  /** Clicks [this] node, or the closest clickable one around it. */
  private fun SemanticsNode.click() {
    var node: SemanticsNode? = this
    while (node != null && SemanticsActions.OnClick !in node.config) node = node.parent
    val action = node?.config?.get(SemanticsActions.OnClick)?.action
    assertTrue(action != null, "Nothing to click around $this")
    action()
  }

  private companion object {
    const val WIDTH = 1280
    const val HEIGHT = 800
    const val FRAMES = 12
    const val QUERY = "Blender 4.2 for Apple silicon"
    const val PLACEHOLDER = "Describe what to find…"
    const val FOLLOW_UP_PLACEHOLDER = "Ask a follow-up…"
    const val FOLLOW_UP = "Only the LTS release, from a source with TLS"
    const val NEEDS_OK = "Needs your OK"
    const val SCROLL_TICKS = 40f

    // Enough frames to outlast the jump button's wait before it shows.
    const val JUMP_FRAMES = 40
    const val DISCARD = "Discard"
    const val DELETE_SEARCH = "Delete this search"
    const val STOP = "Stop"
    const val SITES_CHIP = "Limit to websites"
    const val DETAILS = "Details"
    const val MORE = "More"
    const val TURN_OFF = "Turn off Discover"
    const val TURNED_OFF = "Discover is off"
    const val UNDO = "Undo"
    const val SHOW_MORE = "Show more"
    const val SHOW_LESS = "Show less"
  }
}

/** The lifecycle of a window the test moves in and out of the front. */
private class WindowLifecycle : LifecycleOwner {
  val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
    currentState = Lifecycle.State.RESUMED
  }

  override val lifecycle: Lifecycle
    get() = registry
}
