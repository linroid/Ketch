package com.linroid.ketch.torrent.portmap

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.net.LocalIpv4Network
import com.linroid.ketch.torrent.net.localIpv4Networks
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.numericHost
import com.linroid.ketch.torrent.net.publicTorrentAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What port mapping reached: [layers] NAT layers forward our port, the outermost on
 * [externalPort] at [externalAddress]. [public] says that address is public, so peers reach us
 * through every layer; otherwise another NAT outside them does not forward the port.
 */
internal data class PortMapLayers(
  val layers: Int,
  val externalPort: Int,
  val externalAddress: String?,
  val public: Boolean,
)

/**
 * Asks the routers in front of us to forward our listen port, so peers behind other NATs can
 * reach us, through as many NAT layers as answer, up to [MAX_LAYERS]: the home router first,
 * then, while a router's own external address is still private, the gateway outside it (guessed
 * from that address, [outerGateways]), asked to forward the same port to the router. Each layer
 * tries NAT-PMP, then PCP, then UPnP. TCP and UDP (DHT and uTP) map to one external port per
 * layer; [onMapped] hands the outermost one to the announces. Mappings are renewed at half their
 * lifetime and removed by [stop]. Without a gateway it looks again every 10 minutes, as the
 * network may change.
 */
internal class TorrentPortMapper(
  private val scope: CoroutineScope,
  private val tcpPort: Int,
  /** The UDP port DHT and uTP listen on, when one is bound. */
  private val udpPort: suspend () -> Int?,
  private val onMapped: (Int?) -> Unit,
  /**
   * The outermost mapped router's external address while a mapping holds, as [numericHost]
   * writes it, else null.
   */
  private val onExternalAddress: (String?) -> Unit = {},
  /** What the mapping reached each time it changes; null without a mapping. */
  private val onLayers: (PortMapLayers?) -> Unit = {},
  private val natPmp: NatPmpClient,
  private val upnp: UpnpClient,
  /** PCP (RFC 6887); null leaves it out. */
  private val pcp: PcpClient? = null,
  private val networks: suspend () -> List<LocalIpv4Network> = ::localIpv4Networks,
  /** Where to look for the gateway outside a NAT whose external address this is. */
  private val outerGateways: (ByteArray) -> List<ByteArray> = ::outerGatewayGuesses,
  private val retryMs: Long = 10 * 60_000,
  /** How often a mapped port checks whether the UDP port appeared or changed. */
  private val udpCheckMs: Long = 60_000,
) {
  /** Where the mappings of one layer live, to renew and remove them. */
  private sealed interface Gateway {
    val protocol: String

    data class NatPmp(val address: ByteArray) : Gateway {
      override val protocol get() = "NAT-PMP"
    }

    data class Pcp(val address: ByteArray, val client: ByteArray, val nonce: ByteArray) : Gateway {
      override val protocol get() = "PCP"
    }

    data class Upnp(val gateway: UpnpGateway, val client: String) : Gateway {
      override val protocol get() = "UPnP"
    }
  }

  /** One layer's mappings and the external address its gateway reports. */
  private class Mapped(
    val gateway: Gateway,
    val mappings: List<PortMapping>,
    val lifetime: Long,
    val externalAddress: String? = null,
  ) {
    val tcpExternal: Int get() = mappings.first { it.protocol == MappedProtocol.TCP }.externalPort
    val udpExternal: Int? get() = mappings.firstOrNull { it.protocol == MappedProtocol.UDP }
      ?.externalPort
  }

  /**
   * What one layer forwards: TCP's and UDP's internal ports, and the address the gateway sees
   * them from, or null on the first layer, where it is ours on the gateway's network.
   */
  private class Target(val tcp: Int, val udp: Int?, val client: ByteArray?)

  private val log = KetchLogger("TorrentEngine")
  private val mutex = Mutex()
  // Guarded by mutex. Innermost first.
  private var current: List<Mapped> = emptyList()
  private var job: Job? = null

  fun start() {
    check(job == null) { "Port mapping already started" }
    job = scope.launch {
      while (isActive) {
        val layers = try {
          mutex.withLock { map() }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.d { "Port mapping failed: ${e.describeWithoutUrls()}" }
          emptyList()
        }
        if (layers.isEmpty()) {
          onMapped(null)
          onExternalAddress(null)
          onLayers(null)
          delay(retryMs)
        } else {
          awaitRenewal(layers)
        }
      }
    }
  }

  /**
   * Waits until the first of [layers] is due for renewal, at half its lifetime (between one and
   * 30 minutes), or until the UDP port changes, as when DHT binds its socket after the first
   * pass.
   */
  private suspend fun awaitRenewal(layers: List<Mapped>) {
    val mappedUdp = layers.first().mappings
      .firstOrNull { it.protocol == MappedProtocol.UDP }?.internalPort
    var remaining = (layers.minOf { it.lifetime } * 500).coerceIn(60_000, 30 * 60_000)
    while (remaining > 0) {
      val step = minOf(remaining, udpCheckMs)
      delay(step)
      remaining -= step
      if (udpPort() != mappedUdp) return
    }
  }

  /** Stops renewing and removes the mappings, giving the gateways at most [timeoutMs]. */
  suspend fun stop(timeoutMs: Long = 2_000) {
    job?.cancel()
    withContext(NonCancellable) {
      withTimeoutOrNull(timeoutMs) {
        mutex.withLock {
          current.asReversed().forEach { unmap(it) }
          current = emptyList()
        }
      }
    }
  }

  /** Maps (or renews) the ports through every layer that answers; empty when none did. */
  private suspend fun map(): List<Mapped> {
    val renewing = current
    val networks = networks()
    if (networks.isEmpty()) {
      log.d { "No private IPv4 network: not mapping ports" }
      return emptyList()
    }
    val layers = mutableListOf<Mapped>()
    var target = Target(tcpPort, udpPort(), client = null)
    var guesses = networks.flatMap { it.gatewayGuesses() }
    while (layers.size < MAX_LAYERS) {
      val layer = mapLayer(renewing.getOrNull(layers.size), target, guesses, networks) ?: break
      layers += layer
      val address = layer.externalAddress?.let(::numericAddress) ?: break
      if (publicTorrentAddress(address)) break
      // The same gateway answering again is no further layer.
      if (layers.size >= 2 && layers[layers.size - 2].externalAddress == layer.externalAddress) {
        break
      }
      target = Target(layer.tcpExternal, layer.udpExternal, address)
      guesses = outerGateways(address)
    }
    // Layers renewal no longer reaches are removed, outermost first.
    renewing.drop(layers.size).asReversed().forEach { unmap(it) }
    if (layers.isEmpty()) {
      if (renewing.isEmpty()) {
        log.i { "No NAT-PMP, PCP or UPnP gateway answered; incoming peers need a forwarded port" }
      } else {
        log.w { "Port mapping could not be renewed" }
      }
      current = emptyList()
      return emptyList()
    }
    val outer = layers.last()
    val reached = PortMapLayers(layers.size, outer.tcpExternal, outer.externalAddress,
      outer.externalAddress?.let(::numericAddress)?.let(::publicTorrentAddress) == true)
    if (renewing.map { it.mappings.map { m -> m.externalPort } } !=
      layers.map { it.mappings.map { m -> m.externalPort } }) {
      logLayers(layers, reached)
    }
    current = layers
    onMapped(reached.externalPort)
    onExternalAddress(reached.externalAddress)
    onLayers(reached)
    return layers
  }

  private fun logLayers(layers: List<Mapped>, reached: PortMapLayers) {
    val via = layers.joinToString(", ") { it.gateway.protocol }
    log.i {
      "Mapped port $tcpPort to external port ${reached.externalPort} through " +
        "${layers.size} NAT layer(s) via $via"
    }
    if (!reached.public) {
      log.w {
        "Port $tcpPort is forwarded through ${layers.size} NAT layer(s), but no gateway outside " +
          "them forwards it: peers cannot connect to this device. Hole punching still reaches " +
          "peers behind NATs"
      }
    }
  }

  /** Maps [target] at one layer: NAT-PMP, then PCP, then UPnP. */
  private suspend fun mapLayer(
    renewing: Mapped?,
    target: Target,
    guesses: List<ByteArray>,
    networks: List<LocalIpv4Network>,
  ): Mapped? = mapNatPmp(renewing, target, guesses)
    ?: mapPcp(renewing, target, guesses, networks)
    ?: mapUpnp(renewing, target, guesses, networks)

  private suspend fun mapNatPmp(
    renewing: Mapped?,
    target: Target,
    guesses: List<ByteArray>,
  ): Mapped? {
    val known = (renewing?.gateway as? Gateway.NatPmp)?.address
    // Asked again on each renewal, as the router's external address may change.
    val (gateway, address) = known?.let { it to natPmp.externalAddress(it) }
      ?: natPmp.discover(guesses)
      ?: return null
    val previous = renewing?.mappings?.firstOrNull { it.protocol == MappedProtocol.TCP }
    val tcp = natPmp.map(gateway, MappedProtocol.TCP, target.tcp,
      previous?.externalPort ?: target.tcp, LIFETIME_SECONDS) ?: return null
    // UDP asks for the external port TCP got, so one port reaches all three. A UDP mapping on
    // another port is removed rather than kept: peers are told TCP's port.
    val udpMapping = target.udp?.let {
      natPmp.map(gateway, MappedProtocol.UDP, it, tcp.externalPort, LIFETIME_SECONDS)
    }?.let { mapping ->
      if (mapping.externalPort == tcp.externalPort) mapping else {
        log.d { "NAT-PMP mapped UDP to ${mapping.externalPort}, not ${tcp.externalPort}" }
        natPmp.map(gateway, MappedProtocol.UDP, mapping.internalPort, 0, 0)
        null
      }
    }
    val mappings = listOfNotNull(tcp, udpMapping)
    return Mapped(Gateway.NatPmp(gateway), mappings, mappings.minOf { it.lifetimeSeconds },
      address?.let(::numericHost))
  }

  private suspend fun mapPcp(
    renewing: Mapped?,
    target: Target,
    guesses: List<ByteArray>,
    networks: List<LocalIpv4Network>,
  ): Mapped? {
    val pcp = pcp ?: return null
    val known = renewing?.gateway as? Gateway.Pcp
    val candidates = known?.let { listOf(it.address) }
      ?: guesses.distinctBy { it.toList() }.take(MAX_PCP_GATEWAYS)
    for (gateway in candidates) {
      // The address the gateway sees us from: ours on its network, or the inner NAT's.
      val client = known?.client ?: target.client
        ?: networks.firstOrNull { it.contains(gateway) }?.address ?: continue
      val nonce = known?.nonce ?: PcpClient.nonce()
      val previous = renewing?.mappings?.firstOrNull { it.protocol == MappedProtocol.TCP }
      val tcp = pcp.map(gateway, client, MappedProtocol.TCP, target.tcp,
        previous?.externalPort ?: target.tcp, LIFETIME_SECONDS, nonce) ?: continue
      val external = tcp.mapping.externalPort
      val udpMapping = target.udp?.let {
        pcp.map(gateway, client, MappedProtocol.UDP, it, external, LIFETIME_SECONDS, nonce)
      }?.mapping?.let { mapping ->
        if (mapping.externalPort == external) mapping else {
          log.d { "PCP mapped UDP to ${mapping.externalPort}, not $external" }
          pcp.map(gateway, client, MappedProtocol.UDP, mapping.internalPort, 0, 0, nonce)
          null
        }
      }
      val mappings = listOfNotNull(tcp.mapping, udpMapping)
      return Mapped(Gateway.Pcp(gateway, client, nonce), mappings,
        mappings.minOf { it.lifetimeSeconds }, numericHost(tcp.externalAddress))
    }
    return null
  }

  private suspend fun mapUpnp(
    renewing: Mapped?,
    target: Target,
    guesses: List<ByteArray>,
    networks: List<LocalIpv4Network>,
  ): Mapped? {
    val known = renewing?.gateway as? Gateway.Upnp
    val outer = target.client
    // The first layer's router answers a multicast search on our networks; one outside a NAT
    // only a search sent to it.
    val gateway = known?.gateway
      ?: (if (outer == null) upnp.discover(networks) else
        upnp.discoverAt(guesses.distinctBy { it.toList() }.map(::numericHost)))
      ?: return null
    val address = numericAddress(gateway.host) ?: return null
    val client = known?.client ?: outer?.let(::numericHost)
      ?: networks.firstOrNull { it.contains(address) }?.address?.let(::numericHost)
      ?: return null
    val previous = renewing?.mappings?.firstOrNull { it.protocol == MappedProtocol.TCP }
    var lease = LIFETIME_SECONDS
    // The preferred port, then a few others when another device holds it (error 718).
    val candidates = listOfNotNull(previous?.externalPort, target.tcp) +
      (1..4).map { target.tcp + it * 7 }.filter { it in 1024..65535 }
    for (external in candidates.distinct()) {
      val mappings = mutableListOf<PortMapping>()
      try {
        for ((protocol, internal) in listOfNotNull(MappedProtocol.TCP to target.tcp,
          target.udp?.let { MappedProtocol.UDP to it })) {
          try {
            upnp.addPortMapping(gateway, protocol, external, internal, client, lease)
          } catch (e: UpnpException) {
            // Some gateways only keep permanent mappings; those are renewed the same way.
            if (e.code != 725 || lease == 0L) throw e
            lease = 0
            upnp.addPortMapping(gateway, protocol, external, internal, client, lease)
          }
          mappings += PortMapping(protocol, internal, external, lease)
        }
        return Mapped(Gateway.Upnp(gateway, client), mappings,
          if (lease == 0L) LIFETIME_SECONDS else lease, upnpExternalAddress(gateway))
      } catch (e: UpnpException) {
        // What this port got mapped already is removed before trying another or giving up.
        for (added in mappings) {
          try {
            upnp.deletePortMapping(gateway, added.protocol, added.externalPort)
          } catch (cleanup: Exception) {
            if (cleanup is CancellationException) throw cleanup
            log.d { "Removing a partial UPnP mapping failed: ${cleanup.describeWithoutUrls()}" }
          }
        }
        if (e.code != 718) {
          log.d { "UPnP port mapping refused: ${e.message}" }
          return null
        }
      }
    }
    return null
  }

  /** The UPnP gateway's external address, or null when it does not say. */
  private suspend fun upnpExternalAddress(gateway: UpnpGateway): String? = try {
    upnp.externalAddress(gateway)?.let { numericAddress(it) }?.let(::numericHost)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.d { "UPnP external address unavailable: ${e.describeWithoutUrls()}" }
    null
  }

  private suspend fun unmap(mapped: Mapped) {
    for (mapping in mapped.mappings) {
      try {
        when (val gateway = mapped.gateway) {
          is Gateway.NatPmp ->
            natPmp.map(gateway.address, mapping.protocol, mapping.internalPort, 0, 0)
          is Gateway.Pcp -> pcp?.map(gateway.address, gateway.client, mapping.protocol,
            mapping.internalPort, 0, 0, gateway.nonce)
          is Gateway.Upnp ->
            upnp.deletePortMapping(gateway.gateway, mapping.protocol, mapping.externalPort)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.d { "Removing a port mapping failed: ${e.describeWithoutUrls()}" }
      }
    }
  }

  companion object {
    /** Requested mapping lifetime: an hour, renewed every half hour. */
    const val LIFETIME_SECONDS = 3600L

    /** NAT layers mapped at most: a home router, a second router and a carrier's NAT. */
    const val MAX_LAYERS = 3

    /** Gateways a layer's PCP request goes to, one after another. */
    private const val MAX_PCP_GATEWAYS = 4
  }
}

/**
 * Where the gateway outside a NAT whose external address is [address] likely is: the first and
 * last host of its /24, and the first of its private range (`10.0.0.1`, `100.64.0.1`,
 * `172.x.0.1`), never [address] itself.
 */
internal fun outerGatewayGuesses(address: ByteArray): List<ByteArray> {
  require(address.size == 4)
  val (a, b, c) = address.take(3).map { it.toInt() and 0xff }
  fun ip(vararg octets: Int) = ByteArray(4) { octets[it].toByte() }
  val guesses = listOfNotNull(
    ip(a, b, c, 1),
    ip(a, b, c, 254),
    when {
      a == 10 -> ip(10, 0, 0, 1)
      a == 100 && b in 64..127 -> ip(100, 64, 0, 1)
      a == 172 && b in 16..31 -> ip(172, b, 0, 1)
      else -> null
    },
  )
  return guesses.filterNot { it.contentEquals(address) }.distinctBy { it.toList() }
}
