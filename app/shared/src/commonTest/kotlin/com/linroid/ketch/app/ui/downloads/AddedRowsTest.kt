package com.linroid.ketch.app.ui.downloads

import com.linroid.ketch.app.state.TaskKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddedRowsTest {
  private val a = TaskKey("local", "a")
  private val b = TaskKey("local", "b")
  private val c = TaskKey("local", "c")

  @Test
  fun shown_firstFreshRow_getsTheLane() {
    val rows = AddedRows().apply { add(listOf(a)) }

    assertEquals(AddedAction.Fly, rows.shown(a, canFly = true))
    assertEquals(AddedAction.None, rows.shown(a, canFly = true))
  }

  @Test
  fun shown_cannotFly_glowsAtOnce() {
    // Under reduce motion, or without the Add button on screen.
    val rows = AddedRows().apply { add(listOf(a)) }

    assertEquals(AddedAction.Glow, rows.shown(a, canFly = false))
  }

  @Test
  fun landed_rowsShownMeanwhile_glowWithTheLanesRow() {
    val rows = AddedRows().apply { add(listOf(a, b, c)) }
    rows.shown(a, canFly = true)

    assertEquals(AddedAction.Hold, rows.shown(b, canFly = true))
    assertEquals(listOf(a, b), rows.landed(a))
    // A row that shows after the landing gets a lane of its own.
    assertEquals(AddedAction.Fly, rows.shown(c, canFly = true))
  }

  @Test
  fun settle_rowNotShownInTime_glowsWithoutALaneOnceItShows() {
    val rows = AddedRows().apply { add(listOf(a)) }

    rows.settle(listOf(a))

    assertTrue(rows.tracks(a))
    assertEquals(AddedAction.Glow, rows.shown(a, canFly = true))
  }

  @Test
  fun forget_rowNeverShown_stopsTrackingIt() {
    val rows = AddedRows().apply { add(listOf(a, b)) }
    rows.shown(b, canFly = false)
    rows.settle(listOf(a, b))

    rows.forget(listOf(a, b))

    assertFalse(rows.tracks(a))
    // A row still glowing is left to finish.
    assertTrue(rows.tracks(b))
    rows.glowed(b)
    assertFalse(rows.tracks(b))
  }

  @Test
  fun settle_afterTheRowShowed_leavesItBe() {
    val rows = AddedRows().apply { add(listOf(a)) }
    rows.shown(a, canFly = true)

    rows.settle(listOf(a))
    rows.forget(listOf(a))

    assertEquals(listOf(a), rows.landed(a))
  }
}
