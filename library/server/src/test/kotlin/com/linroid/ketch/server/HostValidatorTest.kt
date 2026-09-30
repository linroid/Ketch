package com.linroid.ketch.server

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostValidatorTest {

  private fun validator(
    allowedHosts: List<String> = emptyList(),
    interfaceAddresses: () -> Set<InetAddress> = { emptySet() },
    machineHostNames: List<String> = emptyList(),
  ) = HostValidator(allowedHosts, interfaceAddresses, { machineHostNames })

  @Test
  fun `loopback names and addresses are accepted with or without a port`() {
    val validator = validator()
    for (host in listOf(
      "localhost", "LOCALHOST:8642", "localhost.", "app.localhost:8642",
      "127.0.0.1", "127.8.9.10:8642", "[::1]", "[::1]:8642",
    )) {
      assertTrue(validator.isAllowed(host), host)
    }
  }

  @Test
  fun `rebound and malformed hosts are rejected`() {
    val validator = validator()
    for (host in listOf(
      "", "attacker.example", "attacker.example:8642",
      "localhost.attacker.example", "127.0.0.1.attacker.example", "evil/x.localhost",
      "0.0.0.0", "256.0.0.1", "[::1", "[::1]x", "localhost:port",
    )) {
      assertFalse(validator.isAllowed(host), host)
    }
  }

  @Test
  fun `request without a host header is accepted`() {
    assertTrue(validator().isAllowed(null))
  }

  @Test
  fun `interface addresses are accepted and others rejected`() {
    val validator = validator(
      interfaceAddresses = {
        setOf(InetAddress.getByName("192.0.2.10"), InetAddress.getByName("2001:db8::10"))
      },
    )
    assertTrue(validator.isAllowed("192.0.2.10:8642"))
    assertTrue(validator.isAllowed("[2001:db8:0:0::10]:8642"))
    assertFalse(validator.isAllowed("192.0.2.11:8642"))
  }

  @Test
  fun `interfaces are re-read when an unknown address arrives`() {
    var reads = 0
    val validator = validator(
      interfaceAddresses = {
        reads++
        if (reads == 1) setOf(InetAddress.getByName("192.0.2.10"))
        else setOf(InetAddress.getByName("192.0.2.20"))
      },
    )
    assertTrue(validator.isAllowed("192.0.2.10"))
    assertTrue(validator.isAllowed("192.0.2.20"))
    assertEquals(2, reads)
    // Known addresses do not trigger another read.
    assertTrue(validator.isAllowed("192.0.2.20"))
    assertEquals(2, reads)
  }

  @Test
  fun `machine host name and its mdns name are accepted`() {
    val validator = validator(machineHostNames = listOf("Lins-MBP.lan", "Lins-MacBook-Pro.local"))
    assertTrue(validator.isAllowed("lins-mbp.lan:8642"))
    assertTrue(validator.isAllowed("Lins-MBP.local:8642"))
    assertTrue(validator.isAllowed("lins-macbook-pro.local."))
    assertFalse(validator.isAllowed("other.local"))
  }

  @Test
  fun `localhost as machine host name does not allow localhost dot local`() {
    val validator = validator(machineHostNames = listOf("localhost.localdomain"))
    assertFalse(validator.isAllowed("localhost.local"))
    assertFalse(validator.isAllowed("localhost.localdomain"))
  }

  @Test
  fun `allowed names and addresses are accepted in any notation`() {
    val validator = validator(
      allowedHosts = listOf("NAS.example.", "192.168.1.20", "fd00::5", "[fd00::6]:8642"),
    )
    assertTrue(validator.isAllowed("nas.example:8642"))
    assertTrue(validator.isAllowed("192.168.1.20:8642"))
    assertTrue(validator.isAllowed("[fd00:0:0::5]:8642"))
    assertTrue(validator.isAllowed("[fd00::6]"))
    assertFalse(validator.isAllowed("sub.nas.example"))
  }
}
