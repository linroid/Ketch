package com.linroid.ketch.torrent

import com.linroid.ketch.torrent.control.TorrentRevisions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TorrentRevisionsTest {
  private val seeding = TorrentRevisions.Control(generation = 0, seeding = true)
  private val stopped = TorrentRevisions.Control(generation = 0, seeding = false)

  @Test
  fun markControl_contentReadBeforeTheRecordSaves_keepsTheCommandRevisionCurrent() = runTest {
    val revisions = TorrentRevisions("epoch")
    revisions.publish("t", "seeding", seeding)
    val command = revisions.markControl("t", stopped)
    // Read before the command saved its record: new content, still the old control.
    revisions.publish("t", "stopping", seeding)
    revisions.publish("t", "stopped", stopped)

    revisions.requireCurrent("t", command)
  }

  @Test
  fun publish_controlChangedAgainAfterTheCommand_movesTheControlSequence() = runTest {
    val revisions = TorrentRevisions("epoch")
    revisions.publish("t", "seeding", seeding)
    val command = revisions.markControl("t", stopped)
    revisions.publish("t", "stopped", stopped)
    // Seeding again without a command, as restoring it after a restart does.
    revisions.publish("t", "seeding again", seeding)

    val error = kotlin.runCatching { revisions.requireCurrent("t", command) }.exceptionOrNull()
    kotlin.test.assertIs<com.linroid.ketch.torrent.api.TorrentCommandException>(error)
  }
}
