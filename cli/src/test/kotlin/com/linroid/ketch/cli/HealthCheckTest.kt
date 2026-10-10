package com.linroid.ketch.cli

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HealthCheckTest {

  @Test
  fun parse_options_readIntoTheCheck() {
    assertEquals(
      HealthArgs.Check(host = "10.0.0.2", port = 9000, configPath = "/config/config.toml"),
      parseHealthArgs(
        listOf("--port", "9000", "--host", "10.0.0.2", "--config", "/config/config.toml"),
      ),
    )
    assertEquals(HealthArgs.Check(), parseHealthArgs(emptyList()))
    assertEquals(HealthArgs.Help, parseHealthArgs(listOf("--port", "9000", "-h")))
  }

  @Test
  fun parse_unusableArguments_invalid() {
    val unusable = listOf(listOf("--port"), listOf("--port", "0"), listOf("--port", "x"), listOf("-x"))
    for (args in unusable) {
      assertIs<HealthArgs.Invalid>(parseHealthArgs(args), args.toString())
    }
  }

  @Test
  fun host_serverOnEveryInterface_asksLoopback() {
    assertEquals("127.0.0.1", healthCheckHost("0.0.0.0"))
    assertEquals("::1", healthCheckHost("::"))
    assertEquals("192.168.1.20", healthCheckHost("192.168.1.20"))
    assertEquals("fd00::2", healthCheckHost("[fd00::2]"))
  }

  @Test
  fun url_ipv6Host_bracketed() {
    assertEquals("http://127.0.0.1:8642/api/health", healthUrl("127.0.0.1", 8642))
    assertEquals("http://[::1]:8642/api/health", healthUrl("::1", 8642))
  }

  @Test
  fun check_nothingListening_notReady() {
    val loopback = InetAddress.getLoopbackAddress()
    val port = ServerSocket(0, 1, loopback).use { it.localPort }

    assertEquals(HealthExit.NOT_READY, checkHealth(healthUrl(loopback.hostAddress, port)))
  }
}
