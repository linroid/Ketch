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
import kotlinx.coroutines.coroutineScope
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

/** The gateways a [TorrentPortMapper] asks, and the networks it looks for them on. */
internal class PortMapGateways(
  val natPmp: NatPmpClient,
  val upnp: UpnpClient,
  val pcp: PcpClient?,
  val networks: suspend () -> List<LocalIpv4Network> = ::localIpv4Networks,
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

    /** Whether this and [other] are one gateway holding one set of our mappings. */
    fun sameAs(other: Gateway): Boolean

    /** Whether removing [a] here also removes [b]: the gateway keys them alike. */
    fun sameMapping(a: PortMapping, b: PortMapping): Boolean =
      a.protocol == b.protocol && a.internalPort == b.internalPort

    class NatPmp(val address: ByteArray) : Gateway {
      override val protocol get() = "NAT-PMP"

      override fun sameAs(other: Gateway) =
        other is NatPmp && other.address.contentEquals(address)
    }

    class Pcp(val address: ByteArray, val client: ByteArray, val nonce: ByteArray) : Gateway {
      override val protocol get() = "PCP"

      override fun sameAs(other: Gateway) = other is Pcp && other.address.contentEquals(address) &&
        other.client.contentEquals(client) && other.nonce.contentEquals(nonce)
    }

    data class Upnp(val gateway: UpnpGateway, val client: String) : Gateway {
      override val protocol get() = "UPnP"

      override fun sameAs(other: Gateway) = other == this

      // UPnP removes a mapping by its external port.
      override fun sameMapping(a: PortMapping, b: PortMapping) =
        a.protocol == b.protocol && a.externalPort == b.externalPort
    }
  }

  /**
   * A mapping [gateway] of NAT [layer] may hold for us: asked for, and not removed since;
   * [permanent] once it was asked for with UPnP's lease 0, which the gateway keeps until it is
   * removed. [answered] once the gateway answered us: NAT-PMP and UPnP gateways did before any
   * request, as discovery found them, a PCP gateway only once a MAP got its reply. A request to
   * a guessed gateway that never answered may have reached no gateway at all.
   */
  private class Requested(
    val gateway: Gateway,
    val mapping: PortMapping,
    val layer: Int,
    var permanent: Boolean,
    var answered: Boolean,
  ) {
    /** Whether the gateway confirmed, once asked to remove it, that it holds no such mapping. */
    var removed = false

    fun matches(gateway: Gateway, mapping: PortMapping) =
      this.gateway.sameAs(gateway) && gateway.sameMapping(this.mapping, mapping)
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
  // Guarded by mutex. Every mapping asked for since it was last removed, noted before the
  // request goes out: a gateway may grant one whose reply never arrives, as when stop() cancels
  // the pass waiting for it, and those are removed too. Requests to guessed PCP gateways that
  // never answered are noted as well, and their removal sent once.
  private val requested = mutableListOf<Requested>()
  // Guarded by mutex. The layer the running pass maps, 0 innermost.
  private var asking = 0
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

  /**
   * Stops renewing and removes the mappings, all at once, giving the gateways at most
   * [timeoutMs], and logs once whether they did. A mapping whose removal its gateway did not
   * confirm may stay until its lease ends, or until the router drops it where UPnP leased it
   * permanently.
   */
  suspend fun stop(timeoutMs: Long = 2_000) {
    job?.cancel()
    withContext(NonCancellable) {
      // Every mapping asked for, the ones of a pass stop() cut short included, read once that
      // pass let go.
      var removing: List<Requested>? = null
      val finished = withTimeoutOrNull(timeoutMs) {
        mutex.withLock {
          val asked = requested.toList()
          removing = asked
          unmapAll(asked)
          requested.clear()
          current = emptyList()
        }
      } != null
      logRemoval(removing, finished, timeoutMs)
    }
  }

  /**
   * Logs how removing [removing], every mapping asked for, went, naming only the port and the
   * NAT layers: a layer may still forward the port when its gateway did not confirm every
   * removal, whether or not it ever confirmed the mapping. Only gateways that answered, before or
   * by confirming the removal, name layers: a guessed gateway that never answered its probe may
   * not be there. [removing] is null when the removal timed out before it began; nothing asked of
   * a gateway that answered, nothing is logged.
   */
  private fun logRemoval(removing: List<Requested>?, finished: Boolean, timeoutMs: Long) {
    val heard = removing?.filter { it.answered || it.removed }
    if (heard != null && heard.isEmpty()) return
    val head = if (finished) "Removing the mapping of port $tcpPort" else {
      "Removing the mapping of port $tcpPort timed out after ${timeoutMs}ms"
    }
    val layers = heard?.distinctBy { it.layer }?.size
    val kept = heard?.filterNot { it.removed }
    log.i {
      when {
        layers == null || kept == null ->
          "$head; the gateways may still forward the port ${until(null)}"
        kept.isEmpty() -> "Removed the mapping of port $tcpPort through $layers NAT layer(s)"
        else -> {
          val keptLayers = kept.distinctBy { it.layer }.size
          "$head: ${layers - keptLayers} of $layers NAT layer(s) confirmed it; " +
            "the others may still forward the port ${until(kept)}"
        }
      }
    }
  }

  /** Until when the gateways may forward the port: [kept] mappings, or unknown ones when null. */
  private fun until(kept: List<Requested>?): String = when {
    kept == null || kept.any { it.permanent } && !kept.all { it.permanent } ->
      "until their lease ends, a permanent UPnP lease until the router drops it"
    kept.all { it.permanent } -> "until the router drops it, as UPnP leased it for good"
    else -> "until their lease ends"
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
      asking = layers.size
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
    // What this pass no longer holds is removed: layers renewal no longer reaches, ports a
    // gateway changed, and requests that got no answer.
    prune(layers)
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
    val requestTcp = previous?.externalPort ?: target.tcp
    requesting(Gateway.NatPmp(gateway), MappedProtocol.TCP, target.tcp, requestTcp)
    val tcp = natPmp.map(gateway, MappedProtocol.TCP, target.tcp, requestTcp, LIFETIME_SECONDS)
      ?: return null
    // UDP asks for the external port TCP got, so one port reaches all three. A UDP mapping on
    // another port is removed rather than kept: peers are told TCP's port.
    val udpMapping = target.udp?.let {
      requesting(Gateway.NatPmp(gateway), MappedProtocol.UDP, it, tcp.externalPort)
      natPmp.map(gateway, MappedProtocol.UDP, it, tcp.externalPort, LIFETIME_SECONDS)
    }?.let { mapping ->
      if (mapping.externalPort == tcp.externalPort) mapping else {
        log.d { "NAT-PMP mapped UDP to ${mapping.externalPort}, not ${tcp.externalPort}" }
        release(Gateway.NatPmp(gateway), mapping)
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
      val pcpGateway = Gateway.Pcp(gateway, client, nonce)
      val requestTcp = previous?.externalPort ?: target.tcp
      requesting(pcpGateway, MappedProtocol.TCP, target.tcp, requestTcp)
      val tcp = pcp.map(gateway, client, MappedProtocol.TCP, target.tcp, requestTcp,
        LIFETIME_SECONDS, nonce) ?: continue
      heardFrom(pcpGateway)
      val external = tcp.mapping.externalPort
      val udpMapping = target.udp?.let {
        requesting(pcpGateway, MappedProtocol.UDP, it, external)
        pcp.map(gateway, client, MappedProtocol.UDP, it, external, LIFETIME_SECONDS, nonce)
      }?.mapping?.let { mapping ->
        if (mapping.externalPort == external) mapping else {
          log.d { "PCP mapped UDP to ${mapping.externalPort}, not $external" }
          release(pcpGateway, mapping)
          null
        }
      }
      val mappings = listOfNotNull(tcp.mapping, udpMapping)
      return Mapped(pcpGateway, mappings,
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
    val upnpGateway = Gateway.Upnp(gateway, client)
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
          val added = requesting(upnpGateway, protocol, internal, external, lease)
          try {
            try {
              upnp.addPortMapping(gateway, protocol, external, internal, client, lease)
            } catch (e: UpnpException) {
              // Some gateways only keep permanent mappings; those are renewed the same way.
              if (e.code != 725 || lease == 0L) throw e
              lease = 0
              requesting(upnpGateway, protocol, internal, external, lease)
              upnp.addPortMapping(gateway, protocol, external, internal, client, lease)
            }
          } catch (e: UpnpException) {
            // Refused: the gateway made nothing to remove, and the port may be another device's.
            // A renewal refused keeps its entry, as the gateway still holds what it granted.
            added?.let { requested -= it }
            throw e
          }
          mappings += PortMapping(protocol, internal, external, lease)
        }
        return Mapped(upnpGateway, mappings,
          if (lease == 0L) LIFETIME_SECONDS else lease, upnpExternalAddress(gateway))
      } catch (e: UpnpException) {
        // What this port got mapped already is removed before trying another or giving up.
        for (added in mappings) release(upnpGateway, added)
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

  /**
   * Notes that [gateway] may forward [internal] on [external] from now on, for good when [lease]
   * is UPnP's 0; call before asking. Returns the entry when this request added it, or null when
   * one held already, as on renewal.
   */
  private fun requesting(
    gateway: Gateway,
    protocol: MappedProtocol,
    internal: Int,
    external: Int,
    lease: Long = LIFETIME_SECONDS,
  ): Requested? {
    val mapping = PortMapping(protocol, internal, external, LIFETIME_SECONDS)
    requested.firstOrNull { it.matches(gateway, mapping) }?.let { held ->
      if (lease == 0L) held.permanent = true
      return null
    }
    val answered = gateway !is Gateway.Pcp ||
      requested.any { it.answered && it.gateway.sameAs(gateway) }
    return Requested(gateway, mapping, asking, permanent = lease == 0L, answered = answered)
      .also { requested += it }
  }

  /** Notes that [gateway] answered, so the mappings asked of it are a NAT layer's. */
  private fun heardFrom(gateway: Gateway) {
    for (request in requested) {
      if (request.gateway.sameAs(gateway)) request.answered = true
    }
  }

  /** Removes [mapping] from [gateway] now. */
  private suspend fun release(gateway: Gateway, mapping: PortMapping) {
    unmap(gateway, mapping)
    requested.removeAll { it.matches(gateway, mapping) }
  }

  /** Removes every requested mapping [layers] do not hold. */
  private suspend fun prune(layers: List<Mapped>) {
    val stale = requested.filterNot { request ->
      layers.any { layer -> layer.mappings.any { request.matches(layer.gateway, it) } }
    }
    // Forgotten once asked to go, so a stop() that cuts the removal short sends it again.
    unmapAll(stale)
    requested -= stale.toSet()
  }

  /**
   * Removes [requests] side by side, so a gateway that never answers holds none of the others
   * up, and marks those whose gateway confirmed it. Order does not matter: an outer
   * layer still forwarding the port to an inner one that no longer does reaches nothing.
   */
  private suspend fun unmapAll(requests: List<Requested>) = coroutineScope {
    for (request in requests) {
      launch { request.removed = unmap(request.gateway, request.mapping, request.answered) }
    }
  }

  /**
   * Removes [mapping] from [gateway]; true once the gateway confirmed it holds no such mapping.
   * Unless the gateway [answered] before, the removal goes out once, briefly: a guessed gateway
   * that never answered is likely not there.
   */
  private suspend fun unmap(
    gateway: Gateway,
    mapping: PortMapping,
    answered: Boolean = true,
  ): Boolean = try {
    when (gateway) {
      is Gateway.NatPmp ->
        natPmp.map(gateway.address, mapping.protocol, mapping.internalPort, 0, 0) != null
      is Gateway.Pcp -> pcp?.map(gateway.address, gateway.client, mapping.protocol,
        mapping.internalPort, 0, 0, gateway.nonce, once = !answered) != null
      is Gateway.Upnp -> {
        upnp.deletePortMapping(gateway.gateway, mapping.protocol, mapping.externalPort)
        true
      }
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: UpnpException) {
    // NoSuchEntryInArray: the router holds no such mapping, as when it already lost it.
    val gone = e.code == UPNP_NO_SUCH_ENTRY
    if (!gone) log.d { "Removing a port mapping failed: ${e.describeWithoutUrls()}" }
    gone
  } catch (e: Exception) {
    log.d { "Removing a port mapping failed: ${e.describeWithoutUrls()}" }
    false
  }

  companion object {
    /** Requested mapping lifetime: an hour, renewed every half hour. */
    const val LIFETIME_SECONDS = 3600L

    /** NAT layers mapped at most: a home router, a second router and a carrier's NAT. */
    const val MAX_LAYERS = 3

    /** Gateways a layer's PCP request goes to, one after another. */
    private const val MAX_PCP_GATEWAYS = 4

    /** The UPnP fault a router answers for a mapping it does not hold. */
    private const val UPNP_NO_SUCH_ENTRY = 714
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
