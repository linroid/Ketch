package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

// Each test passes by returning before the timeout: the MCP stdio transport expects the
// server to stop once the client closes its input.
class KetchMcpServerTest {

  @Test
  fun `serveStdio returns when the client closes its input`() =
    runTest(timeout = 10.seconds) {
      val input = Buffer().apply { writeString(INITIALIZE_REQUEST + "\n") }
      serveStdio(ToolRegistry {}, input, Buffer())
    }

  @Test
  fun `serveStdio returns when the input is already at its end`() =
    runTest(timeout = 10.seconds) {
      serveStdio(ToolRegistry {}, Buffer(), Buffer())
    }

  private companion object {
    const val INITIALIZE_REQUEST = """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
      """"params":{"protocolVersion":"2025-06-18","capabilities":{},""" +
      """"clientInfo":{"name":"test","version":"1"}}}"""
  }
}
