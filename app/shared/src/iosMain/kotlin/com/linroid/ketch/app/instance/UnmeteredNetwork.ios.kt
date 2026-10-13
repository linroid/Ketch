package com.linroid.ketch.app.instance

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * Whether the device is on Wi-Fi or another unmetered network, as it changes, for
 * [InstanceFactory]'s `uploadNetwork`: the path the system reports as soon as monitoring starts,
 * then each change. See [pathAllowsUploads].
 */
internal fun unmeteredNetworkChanges(): Flow<Boolean> = callbackFlow {
  val monitor = nw_path_monitor_create()
  nw_path_monitor_set_update_handler(monitor) { path ->
    trySend(
      pathAllowsUploads(
        satisfied = nw_path_get_status(path) == nw_path_status_satisfied,
        expensive = nw_path_is_expensive(path),
        constrained = nw_path_is_constrained(path),
      ),
    )
  }
  // A serial queue of its own: the handler never waits for the main thread.
  val queue = dispatch_queue_create("com.linroid.ketch.upload-network", null)
  nw_path_monitor_set_queue(monitor, queue)
  nw_path_monitor_start(monitor)
  awaitClose { nw_path_monitor_cancel(monitor) }
}.conflate().distinctUntilChanged()

/**
 * Whether a network path lets uploads run: it is [satisfied] (usable), not [expensive] (cellular
 * or a phone's hotspot) and not [constrained] (Low Data Mode).
 */
internal fun pathAllowsUploads(
  satisfied: Boolean,
  expensive: Boolean,
  constrained: Boolean,
): Boolean = satisfied && !expensive && !constrained
