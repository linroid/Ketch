package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
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
 * Asks the home router to forward our listen port, so peers behind other NATs can reach us:
 * NAT-PMP first, then UPnP. TCP and UDP (DHT and uTP) map to one external port, which
 * [onMapped] hands to the announces; mappings are renewed at half their lifetime and removed by
 * [stop]. Without a gateway it looks again every 10 minutes, as the network may change.
 */
internal class TorrentPortMapper(
  private val scope: CoroutineScope,
  private val tcpPort: Int,
  /** The UDP port DHT and uTP listen on, when one is bound. */
  private val udpPort: suspend () -> Int?,
  private val onMapped: (Int?) -> Unit,
  private val natPmp: NatPmpClient,
  private val upnp: UpnpClient,
  private val networks: suspend () -> List<LocalIpv4Network> = ::localIpv4Networks,
  private val retryMs: Long = 10 * 60_000,
  /** How often a mapped port checks whether the UDP port appeared or changed. */
  private val udpCheckMs: Long = 60_000,
) {
  /** Where the mappings live, to renew and remove them. */
  private sealed interface Gateway {
    data class NatPmp(val address: ByteArray) : Gateway
    data class Upnp(val gateway: UpnpGateway, val client: String) : Gateway
  }

  private class Mapped(val gateway: Gateway, val mappings: List<PortMapping>, val lifetime: Long)

  private val log = KetchLogger("TorrentEngine")
  private val mutex = Mutex()
  // Guarded by mutex.
  private var current: Mapped? = null
  private var job: Job? = null

  fun start() {
    check(job == null) { "Port mapping already started" }
    job = scope.launch {
      while (isActive) {
        val mapped = try {
          mutex.withLock { map() }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          log.d { "Port mapping failed: ${e.describeWithoutUrls()}" }
          null
        }
        if (mapped == null) {
          onMapped(null)
          delay(retryMs)
        } else {
          awaitRenewal(mapped)
        }
      }
    }
  }

  /**
   * Waits until [mapped] is due for renewal, at half its lifetime (between one and 30 minutes),
   * or until the UDP port changes, as when DHT binds its socket after the first pass.
   */
  private suspend fun awaitRenewal(mapped: Mapped) {
    val mappedUdp = mapped.mappings.firstOrNull { it.protocol == MappedProtocol.UDP }?.internalPort
    var remaining = (mapped.lifetime * 500).coerceIn(60_000, 30 * 60_000)
    while (remaining > 0) {
      val step = minOf(remaining, udpCheckMs)
      delay(step)
      remaining -= step
      if (udpPort() != mappedUdp) return
    }
  }

  /** Stops renewing and removes the mappings, giving the gateway at most [timeoutMs]. */
  suspend fun stop(timeoutMs: Long = 2_000) {
    job?.cancel()
    withContext(NonCancellable) {
      withTimeoutOrNull(timeoutMs) {
        mutex.withLock {
          current?.let { unmap(it) }
          current = null
        }
      }
    }
  }

  /** Maps (or renews) the ports; null when no gateway mapped them. */
  private suspend fun map(): Mapped? {
    val renewing = current
    val networks = networks()
    if (networks.isEmpty()) {
      log.d { "No private IPv4 network: not mapping ports" }
      return null
    }
    val udp = udpPort()
    val mapped = mapNatPmp(renewing, networks, udp) ?: mapUpnp(renewing, networks, udp)
    if (mapped == null) {
      if (renewing == null) {
        log.i { "No NAT-PMP or UPnP gateway answered; incoming peers need a forwarded port" }
      } else {
        log.w { "Port mapping could not be renewed" }
      }
      current = null
      return null
    }
    val external = mapped.mappings.first { it.protocol == MappedProtocol.TCP }.externalPort
    if (renewing == null ||
      renewing.mappings.map { it.externalPort } != mapped.mappings.map { it.externalPort }) {
      log.i {
        "Mapped port $tcpPort to external port $external via " +
          (if (mapped.gateway is Gateway.NatPmp) "NAT-PMP" else "UPnP") +
          (udp?.let { " (TCP, and UDP $it)" } ?: " (TCP)")
      }
    }
    current = mapped
    onMapped(external)
    return mapped
  }

  private suspend fun mapNatPmp(
    renewing: Mapped?,
    networks: List<LocalIpv4Network>,
    udp: Int?,
  ): Mapped? {
    val gateway = (renewing?.gateway as? Gateway.NatPmp)?.address
      ?: natPmp.discover(networks.flatMap { it.gatewayGuesses() })?.first
      ?: return null
    val previous = renewing?.mappings?.firstOrNull { it.protocol == MappedProtocol.TCP }
    val tcp = natPmp.map(gateway, MappedProtocol.TCP, tcpPort,
      previous?.externalPort ?: tcpPort, LIFETIME_SECONDS) ?: return null
    // UDP asks for the external port TCP got, so one port reaches all three. A UDP mapping on
    // another port is removed rather than kept: peers are told TCP's port.
    val udpMapping = udp?.let {
      natPmp.map(gateway, MappedProtocol.UDP, it, tcp.externalPort, LIFETIME_SECONDS)
    }?.let { mapping ->
      if (mapping.externalPort == tcp.externalPort) mapping else {
        log.d { "NAT-PMP mapped UDP to ${mapping.externalPort}, not ${tcp.externalPort}" }
        natPmp.map(gateway, MappedProtocol.UDP, mapping.internalPort, 0, 0)
        null
      }
    }
    val mappings = listOfNotNull(tcp, udpMapping)
    return Mapped(Gateway.NatPmp(gateway), mappings, mappings.minOf { it.lifetimeSeconds })
  }

  private suspend fun mapUpnp(
    renewing: Mapped?,
    networks: List<LocalIpv4Network>,
    udp: Int?,
  ): Mapped? {
    val known = renewing?.gateway as? Gateway.Upnp
    val gateway = known?.gateway ?: upnp.discover(networks) ?: return null
    val address = numericAddress(gateway.host) ?: return null
    val client = known?.client ?: networks.first { it.contains(address) }.address
      .let(::numericHost)
    val previous = renewing?.mappings?.firstOrNull { it.protocol == MappedProtocol.TCP }
    var lease = LIFETIME_SECONDS
    // The preferred port, then a few others when another device holds it (error 718).
    val candidates = listOfNotNull(previous?.externalPort, tcpPort) +
      (1..4).map { tcpPort + it * 7 }.filter { it in 1024..65535 }
    for (external in candidates.distinct()) {
      val mappings = mutableListOf<PortMapping>()
      try {
        for ((protocol, internal) in listOfNotNull(MappedProtocol.TCP to tcpPort,
          udp?.let { MappedProtocol.UDP to it })) {
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
          if (lease == 0L) LIFETIME_SECONDS else lease)
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

  private suspend fun unmap(mapped: Mapped) {
    for (mapping in mapped.mappings) {
      try {
        when (val gateway = mapped.gateway) {
          is Gateway.NatPmp ->
            natPmp.map(gateway.address, mapping.protocol, mapping.internalPort, 0, 0)
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
  }
}
