package com.linroid.ketch.api.torrent

import kotlinx.serialization.Serializable

/** Stable capability names; unknown advertised names are retained for forward compatibility. */
enum class TorrentCapability(val wireName: String) {
  INSPECT("inspect"),
  FILE_SELECTION("file-selection"),
  STREAMING("verified-streaming"),
  TRANSFER_LIMITS("transfer-limits"),
  SEEDING("seeding"),
  RECHECK("recheck"),
  TRACKERS("trackers"),
  RELOCATE("relocate"),
  RENAME("rename"),
  IMPORT("import"),
  EXPORT("export"),
  CREATE("create"),
  REMOVE_OWNED_DATA("remove-owned-data"),
  V1("v1"),
  V2("v2"),
  HYBRID("hybrid"),
  UTP("utp"),
  ENCRYPTION("mse-pe"),
  PROXY("proxy"),
}

/**
 * Capabilities of the connected backend, not the frontend platform.
 *
 * Names are strings so a newer backend can advertise features an older SDK does not know.
 * An incompatible major version must never be interpreted as supporting a known command.
 * Limits are negotiated ceilings, not a promise that admission will succeed under current load.
 * [backgroundTransfers] describes unattended execution; foreground transfers may still work.
 */
@Serializable
data class TorrentCapabilities(
  val protocolMajor: Int = 1,
  val names: Set<String> = emptySet(),
  val maxPageSize: Int = 100,
  val maxSubscriptions: Int = 16,
  val backgroundTransfers: Boolean = false,
) {
  init {
    require(protocolMajor > 0)
    require(names.size <= 128 && names.all { it.length in 1..64 })
    require(maxPageSize in 1..1000)
    require(maxSubscriptions in 1..1024)
  }

  /** Whether this SDK can safely use the advertised feature. Unknown major versions fail closed. */
  fun supports(capability: TorrentCapability): Boolean =
    protocolMajor == 1 && capability.wireName in names
}
