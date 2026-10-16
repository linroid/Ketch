package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A network interface of the instance that downloads. */
@Serializable
data class NetworkInterfaceInfo(
  /** An opaque ID to select it by. */
  val id: String,
  val name: String,
  /** Its current IP addresses. */
  val addresses: List<String>,
  /** What kind of network it is; [NetworkKind.OTHER] when the instance cannot tell. */
  val kind: NetworkKind = NetworkKind.OTHER,
)

/**
 * The kind of a [NetworkInterfaceInfo], for telling networks apart at a glance. Unknown values
 * decode as [OTHER].
 */
@Serializable(with = NetworkKindSerializer::class)
enum class NetworkKind {
  WIFI,
  CELLULAR,
  ETHERNET,

  /** A VPN or other tunnel, which runs over another network. */
  VPN,
  BLUETOOTH,

  /** Not known, or none of the others, such as a virtual bridge. */
  OTHER,
}

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

internal object NetworkKindSerializer : KSerializer<NetworkKind> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.api.NetworkKind", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: NetworkKind) {
    encoder.encodeString(value.name.lowercase())
  }

  override fun deserialize(decoder: Decoder): NetworkKind {
    val name = decoder.decodeString()
    return NetworkKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
      ?: NetworkKind.OTHER
  }
}
