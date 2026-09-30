package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.server.McpServerTransportType
import ai.koog.agents.mcp.server.configureMcpServer
import ai.koog.agents.mcp.server.startMcpServer
import com.linroid.ketch.api.KetchApi
import io.ktor.server.engine.ApplicationEngineFactory
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.InputStream
import java.io.OutputStream

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
   * Reads JSON-RPC messages from [input] and writes responses to [output].
   * This is the standard transport for MCP clients like Claude Desktop.
   *
   * Nothing else may write to [output]. A process that also logs to
   * stdout can redirect `System.out` to stderr and pass the original
   * stream here.
   *
   * This function suspends until the client closes [input], as the MCP
   * stdio transport expects the server to stop at that point.
   *
   * @param input the stream to read requests from, stdin by default
   * @param output the stream to write responses to, stdout by default
   */
  suspend fun startStdio(
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
  ) {
    serveStdio(toolRegistry, input.asSource().buffered(), output.asSink().buffered())
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
    val server = startMcpServer(
      factory = factory,
      tools = toolRegistry,
      port = port,
      host = host,
      transport = McpServerTransportType.SSE,
    )
    val done = Job()
    server.onClose { done.complete() }
    done.join()
  }
}

/**
 * Serves [tools] over the MCP stdio transport on [input] and [output], suspending until
 * [input] reaches its end.
 */
internal suspend fun serveStdio(tools: ToolRegistry, input: Source, output: Sink) {
  val server = configureMcpServer(tools)
  val transport = StdioServerTransport(input, output)
  // Server.onClose only fires on Server.close(), so wait for the transport, which closes
  // at the end of input. Registered before the session starts reading, so an input that
  // is already at its end cannot close it unobserved.
  val closed = Job()
  transport.onClose { closed.complete() }
  server.createSession(transport)
  try {
    closed.join()
  } finally {
    withContext(NonCancellable) { server.close() }
  }
}
