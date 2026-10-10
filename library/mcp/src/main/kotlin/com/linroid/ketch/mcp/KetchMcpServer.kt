package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.mcp.server.McpServerTransportType
import ai.koog.agents.mcp.server.addTool
import ai.koog.agents.mcp.server.startMcpServer
import com.linroid.ketch.api.KetchApi
import io.ktor.server.engine.ApplicationEngineFactory
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
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
 * mcp.startStdio()  // suspends until the client closes stdin
 * ```
 *
 * @param connect returns the instance a tool call works on, asked on every call (see
 *   [KetchToolSet]); what it throws fails that call with its message.
 */
class KetchMcpServer(
  connect: suspend () -> KetchApi,
) {
  /** A server whose tools always work on [ketch]. */
  constructor(ketch: KetchApi) : this({ ketch })

  private val toolRegistry = ToolRegistry {
    tools(KetchToolSet(connect).tools())
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
   * This function suspends until the client closes [input], which is how
   * the MCP stdio transport ends a session. Requests read before then are
   * still answered. The session closes both streams.
   *
   * @param input the stream to read requests from, stdin by default
   * @param output the stream to write responses to, stdout by default
   */
  suspend fun startStdio(
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
  ) {
    serveStdio(toolRegistry, input.asSource(), output.asSink())
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
 * Serves [tools] over the MCP stdio transport on [input] and [output], suspending until [input]
 * ends and the replies to the requests read before then are written.
 */
internal suspend fun serveStdio(tools: ToolRegistry, input: RawSource, output: RawSink) {
  val server = stdioServer(tools)
  val transport = StdioTransport(input, output)
  // Server.onClose only fires on Server.close(), so wait for the transport instead
  val closed = Job()
  transport.onClose { closed.complete() }
  try {
    server.createSession(transport)
    // createSession subscribes the session to the server's notifications after it starts the
    // transport. Closing the transport unsubscribes it, so the input may only end it from now on.
    transport.startReading()
    closed.join()
  } finally {
    withContext(NonCancellable) {
      // Server.close() waits until each subscribed session sees an end event, which one that has
      // not started collecting yet misses. Closing the transport first unsubscribes the session.
      transport.close()
      server.close()
    }
  }
}

/**
 * An MCP server offering [tools], which never change, so it does not announce tool list changes.
 * Koog's `configureMcpServer` does: the SDK then notifies sessions of each tool it registers from a
 * coroutine of its own, and a session that starts in the same millisecond can get the notification
 * after it closed, which throws "Not connected".
 */
private fun stdioServer(tools: ToolRegistry): Server {
  val server = Server(
    Implementation(name = "ketch", version = KetchApi.VERSION),
    ServerOptions(ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
  )
  tools.tools.forEach { server.addTool(it) }
  return server
}
