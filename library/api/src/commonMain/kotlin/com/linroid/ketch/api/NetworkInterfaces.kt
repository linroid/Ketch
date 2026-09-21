package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/** An interface available on the instance that performs downloads, not on a remote client. */
@Serializable
data class NetworkInterfaceInfo(
  /** Opaque instance-local identifier. Use this ID when selecting interfaces. */
  val id: String,
  /** Human-readable interface name. */
  val name: String,
  /** Numeric IP addresses currently assigned to this interface. */
  val addresses: List<String>,
)

/** Runtime HTTP interface selection. An empty list restores system-default routing. */
@Serializable
data class NetworkInterfaceConfig(
  /** Ordered, distinct IDs from [NetworkInterfaces.available], used for round-robin dispatch. */
  val interfaceIds: List<String> = emptyList(),
) {
  init {
    require(interfaceIds.all { it.isNotBlank() }) { "Interface IDs must not be blank" }
    require(interfaceIds.distinct().size == interfaceIds.size) { "Interface IDs must be distinct" }
  }
}

/** Point-in-time interface discovery and selection for an instance. */
@Serializable
data class NetworkInterfaces(
  /** False if this backend cannot configure HTTP network interfaces. */
  val supported: Boolean = false,
  /** Interfaces currently available for selection. */
  val available: List<NetworkInterfaceInfo> = emptyList(),
  /** Selected IDs can remain here after an interface disappears from [available]. */
  val config: NetworkInterfaceConfig = NetworkInterfaceConfig(),
)
