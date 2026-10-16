package com.linroid.ketch.app.instance

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the device's default network is Wi-Fi or another unmetered one, as it changes, for
 * [InstanceFactory]'s `uploadNetwork`: the answer [isUnmeteredNow] gives first, then each change
 * the system reports. Losing the network counts as metered.
 */
fun unmeteredNetworkChanges(context: Context): Flow<Boolean> = callbackFlow {
  val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java)) {
    "No connectivity service"
  }
  val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
      trySend(capabilities.isUnmetered())
    }

    override fun onLost(network: Network) {
      trySend(false)
    }
  }
  send(isUnmeteredNow(context))
  connectivity.registerDefaultNetworkCallback(callback)
  awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.conflate().distinctUntilChanged()

/**
 * Whether the device's default network is Wi-Fi or another unmetered one now; `false` without a
 * network, so uploads that follow it stay paused until one is known.
 */
fun isUnmeteredNow(context: Context): Boolean {
  val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
  val network = connectivity.activeNetwork ?: return false
  return connectivity.getNetworkCapabilities(network)?.isUnmetered() ?: false
}

// Carriers can lift metering for a while, such as on 5G plans without a cap.
private fun NetworkCapabilities.isUnmetered(): Boolean =
  hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
    hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
