package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.server.startSseMcpServer
import ai.koog.agents.mcp.server.startStdioMcpServer
import com.linroid.ketch.api.KetchApi
import io.ktor.server.engine.ApplicationEngineFactory
import kotlinx.coroutines.Job

/**
 * Exposes a [KetchApi] instance as an MCP (Model Context Protocol)
 * server, allowing AI agents to manage downloads via MCP tools.
 *
 * Supports two transport modes:
 * - **stdio** — for CLI/editor integration (Claude Desktop, VS Code, etc.)
 * - **SSE** — for remote HTTP access
 *
 * Usage:
 * ```kotlin
 * val ketch = Ketch(httpEngine = KtorHttpEngine())
 * val mcp = KetchMcpServer(ketch)
 * mcp.startStdio()  // suspends until closed
 * ```
 */
class KetchMcpServer(
  private val ketch: KetchApi,
) {
  private val toolRegistry = ToolRegistry {
    tools(KetchToolSet(ketch))
  }

  /**
   * Starts the MCP server using stdio transport.
   * Reads JSON-RPC messages from stdin and writes responses to stdout.
   * This is the standard transport for MCP clients like Claude Desktop.
   *
   * This function suspends until the server is closed.
   */
  suspend fun startStdio() {
    val server = startStdioMcpServer(toolRegistry)
    val done = Job()
    server.onClose { done.complete() }
    done.join()
  }

  /**
   * Starts the MCP server using SSE (Server-Sent Events) transport
   * over HTTP.
   *
   * @param factory the Ktor server engine factory (e.g., `CIO`)
   * @param port the port to listen on
   * @param host the host to bind to
   */
  suspend fun startSse(
    factory: ApplicationEngineFactory<*, *>,
    port: Int = 3001,
    host: String = "localhost",
  ) {
    val server = startSseMcpServer(factory, port, host, toolRegistry)
    val done = Job()
    server.onClose { done.complete() }
    done.join()
  }
}
