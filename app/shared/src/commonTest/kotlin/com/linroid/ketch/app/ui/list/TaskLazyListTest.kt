package com.linroid.ketch.app.ui.list

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.RowGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskLazyListTest {
  private val queued = ListFixtures.row("queued", DownloadState.Queued)
  private val canceled = ListFixtures.row("canceled", DownloadState.Canceled)

  @Test
  fun listEntries_titledGroups_putAHeaderBeforeTheirRows() {
    val groups = listOf(
      RowGroup("smart:waiting", verbatim("Waiting"), listOf(queued)),
      RowGroup("smart:attention", verbatim("Needs attention"), listOf(canceled))
    )

    val entries = listEntries(groups, GroupCollapse())

    val expected = listOf(
      "group:smart:waiting",
      queued.key.encode(),
      "group:smart:attention",
      canceled.key.encode()
    )
    assertEquals(expected, entries.map { it.key })
  }

  @Test
  fun listEntries_untitledGroup_hasNoHeader() {
    val entries = listEntries(
      listOf(RowGroup("all", verbatim(""), listOf(queued))),
      GroupCollapse()
    )

    assertEquals(listOf(queued.key.encode()), entries.map { it.key })
  }

  @Test
  fun listEntries_collapsedGroup_keepsOnlyItsHeader() {
    val group = RowGroup("smart:waiting", verbatim("Waiting"), listOf(queued))
    val collapse = GroupCollapse()
    collapse.toggle(group)

    val entries = listEntries(listOf(group), collapse)

    val header = entries.single() as ListEntry.Header
    assertTrue(header.collapsed)
  }

  @Test
  fun isCollapsed_largeEarlierGroup_startsCollapsedUntilOpened() {
    val group = RowGroup(
      id = "smart:earlier",
      title = verbatim("Added earlier"),
      rows = listOf(queued),
      collapsedByDefault = true,
    )
    val collapse = GroupCollapse()

    assertTrue(collapse.isCollapsed(group))
    collapse.expand(group)
    assertEquals(false, collapse.isCollapsed(group))
  }
}
