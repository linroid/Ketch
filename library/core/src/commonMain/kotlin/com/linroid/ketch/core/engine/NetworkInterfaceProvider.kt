package com.linroid.ketch.core.engine

import com.linroid.ketch.api.NetworkInterfaceInfo

/** Platform-specific discovery and transport creation for [ConfigurableNetworkHttpEngine]. */
interface NetworkInterfaceProvider {
  /** Returns currently usable interfaces with unique instance-local IDs. */
  suspend fun availableInterfaces(): List<NetworkInterfaceInfo>

  /** Creates a new owned engine using system-default routing. */
  fun createDefaultEngine(): HttpEngine

  /**
   * Creates a new owned engine bound to the selected interface, rechecking availability.
   * Must reject unavailable interfaces rather than falling back to default routing.
   */
  fun createEngine(networkInterface: NetworkInterfaceInfo): HttpEngine
}
