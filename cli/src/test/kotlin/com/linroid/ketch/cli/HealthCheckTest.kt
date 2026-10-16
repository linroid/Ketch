package com.linroid.ketch.cli

import com.linroid.ketch.config.ServerConfig
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
    val unusable =
      listOf(listOf("--port"), listOf("--port", "0"), listOf("--port", "x"), listOf("-x"))
    for (args in unusable) {
      assertIs<HealthArgs.Invalid>(parseHealthArgs(args), args.toString())
    }
  }

  @Test
  fun url_noRunningServer_asksWhereTheConfigSays() {
    assertEquals(
      "http://127.0.0.1:8642/api/health",
      healthCheckUrl(HealthArgs.Check(), ServerConfig(), running = null),
    )
    assertEquals(
      "http://192.168.1.20:9000/api/health",
      healthCheckUrl(HealthArgs.Check(), ServerConfig("192.168.1.20", 9000), running = null),
    )
  }

  @Test
  fun url_runningServer_asksWhereItListens() {
    // Started with `--port 9000`, which neither the config file nor the environment knows.
    val running = CliInstance.Info(SERVER_COMMAND, pid = 1, url = "http://127.0.0.1:9000")

    assertEquals(
      "http://127.0.0.1:9000/api/health",
      healthCheckUrl(HealthArgs.Check(), ServerConfig(), running),
    )
  }

  @Test
  fun url_options_winOverTheRunningServer() {
    val running = CliInstance.Info(SERVER_COMMAND, pid = 1, url = "http://127.0.0.1:9000")

    assertEquals(
      "http://127.0.0.1:7000/api/health",
      healthCheckUrl(HealthArgs.Check(port = 7000), ServerConfig(), running),
    )
    assertEquals(
      "http://[fd00::2]:8642/api/health",
      healthCheckUrl(HealthArgs.Check(host = "fd00::2"), ServerConfig(), running),
    )
  }

  @Test
  fun url_standaloneMcpRunning_ignored() {
    val mcp = CliInstance.Info("ketch mcp --standalone", pid = 1)

    assertEquals(
      "http://127.0.0.1:8642/api/health",
      healthCheckUrl(HealthArgs.Check(), ServerConfig(), mcp),
    )
  }

  @Test
  fun check_nothingListening_notReady() {
    val loopback = InetAddress.getLoopbackAddress()
    val port = ServerSocket(0, 1, loopback).use { it.localPort }
    val url = "${loopbackUrl(loopback.hostAddress, port)}/api/health"

    assertEquals(HealthExit.NOT_READY, checkHealth(url))
  }
}
