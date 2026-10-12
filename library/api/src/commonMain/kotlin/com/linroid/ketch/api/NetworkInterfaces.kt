package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/** A network interface of the instance that downloads. */
@Serializable
data class NetworkInterfaceInfo(
  /** An opaque ID to select it by. */
  val id: String,
  val name: String,
  /** Its current IP addresses. */
  val addresses: List<String>,
)

/** The interfaces HTTP requests use; empty for default routing. */
@Serializable
data class NetworkInterfaceConfig(
  /** Distinct IDs from [NetworkInterfaces.available], used round-robin. */
  val interfaceIds: List<String> = emptyList(),
) {
  init {
    require(interfaceIds.all { it.isNotBlank() }) { "Interface IDs must not be blank" }
    require(interfaceIds.distinct().size == interfaceIds.size) { "Interface IDs must be distinct" }
  }
}

/** An instance's interfaces and selection. */
@Serializable
data class NetworkInterfaces(
  /** Whether the instance can select interfaces. */
  val supported: Boolean = false,
  val available: List<NetworkInterfaceInfo> = emptyList(),
  /** May name interfaces no longer [available]. */
  val config: NetworkInterfaceConfig = NetworkInterfaceConfig(),
)
