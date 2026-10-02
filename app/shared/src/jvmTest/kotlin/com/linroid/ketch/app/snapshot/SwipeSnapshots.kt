package com.linroid.ketch.app.snapshot

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Swiping a touch row on a phone: a slow swipe that stops short of the threshold springs back,
 * and one past it toward the start asks before removing the download; see [SnapshotHarness] for
 * how to run it.
 */
class SwipeSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun swipe_slowAndShort_springsBack() {
    phoneSnapshots("swipe-short") {
      // Past Material's 56 dp but short of the threshold, slow enough not to count as a fling.
      scene.drag(SwipeStart, UbuntuRow, SwipeStart - 100.dp, UbuntuRow, steps = SLOW_STEPS)
    }
  }

  @Test
  fun swipe_towardTheStart_asksBeforeRemoving() {
    phoneSnapshots("swipe-remove") {
      scene.drag(SwipeStart, UbuntuRow, SwipeStart - 260.dp, UbuntuRow, steps = SLOW_STEPS)
    }
  }

  private fun phoneSnapshots(name: String, setup: suspend AppScenario.() -> Unit) {
    for (theme in SnapshotTheme.entries) {
      appSnapshot(name, SnapshotSize.Phone, theme, setup = setup)
    }
  }

  private companion object {
    /** Middle of the ubuntu row, the second download, on [SnapshotSize.Phone]. */
    val UbuntuRow: Dp = 248.dp
    val SwipeStart: Dp = 340.dp

    /** About two seconds of moves, so the finger leaves the screen well under fling speed. */
    const val SLOW_STEPS = 120
  }
}
