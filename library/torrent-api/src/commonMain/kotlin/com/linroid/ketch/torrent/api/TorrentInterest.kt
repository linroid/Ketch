package com.linroid.ketch.torrent.api

import com.linroid.ketch.api.Interest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable

/**
 * Full snapshots of the torrent task with [taskId], followed with
 * [com.linroid.ketch.api.KetchApi.subscribe]: its current one first, then each change. `null` is
 * a tombstone and ends the flow; an unknown task, or one that is not a torrent, reports `null`
 * and ends. The backend bounds how many are followed; a slow collector receives the latest full
 * snapshot, not an unbounded queue of updates. Merge snapshots with commands' results using
 * [TorrentRevision.compareIncoming].
 *
 * Instances listing [TorrentFeatures.CONTROL] report it. A remote instance's flow fails with
 * [TorrentCommandError.CONNECTION_CHANGED] when its connection to the server changes, and the
 * caller follows it again; revisions within an epoch never decrease.
 */
@Serializable
@SerialName("torrent")
data class TorrentInterest(val taskId: String) : Interest<TorrentSnapshot?> {
  init {
    require(taskId.isNotBlank() && taskId.length <= 128) { "Invalid torrent task ID" }
  }

  override val valueSerializer: KSerializer<TorrentSnapshot?>
    get() = TorrentSnapshot.serializer().nullable
}
