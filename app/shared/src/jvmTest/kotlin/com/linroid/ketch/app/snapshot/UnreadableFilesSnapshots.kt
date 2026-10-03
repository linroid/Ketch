package com.linroid.ketch.app.snapshot

import com.linroid.ketch.app.feedback.UnreadableFile
import com.linroid.ketch.app.feedback.postUnreadable
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The warnings an app shows when it started without a file it could not read; see
 * [SnapshotHarness] for how to run it.
 */
class UnreadableFilesSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun app_filesMovedAside_warnWhereTheyWent() {
    appSnapshots(
      "unreadable-files",
      sizes = listOf(SnapshotSize.Desktop, SnapshotSize.Phone),
      themes = listOf(SnapshotTheme.Light),
    ) {
      state.messages.postUnreadable(
        UnreadableFile(
          UnreadableFile.Kind.Settings,
          "/Users/alex/Library/Application Support/ketch/config.toml.broken-20261003T142530Z",
        )
      )
      state.messages.postUnreadable(
        UnreadableFile(
          UnreadableFile.Kind.Downloads,
          "/Users/alex/Library/Application Support/ketch/ketch.db.broken-20261003T142530Z",
        )
      )
    }
  }
}
