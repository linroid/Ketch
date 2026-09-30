package com.linroid.ketch.mcp

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.ReadBuffer
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.shared.serializeMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP stdio transport that answers the messages it has read before it closes.
 *
 * The SDK's `StdioServerTransport` closes as soon as its input ends and cancels the messages it
 * has not handled or sent yet, so a client that writes a request and then closes its input gets
 * no reply. This transport stops reading at the end of [input], handles the messages it has
 * already read, writes their replies to [output] and only then closes. [close] stops it without
 * waiting.
 *
 * Messages are handled one at a time, in the order they arrive, like the SDK transport does.
 */
internal class StdioTransport(
  private val input: Source,
  private val output: Sink,
) : AbstractTransport() {
  private val log = KetchLogger("McpStdio")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val outgoing = Channel<JSONRPCMessage>(Channel.UNLIMITED)
  private val started = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)

  override suspend fun start() {
    check(started.compareAndSet(false, true)) { "StdioTransport already started" }
    scope.launch { writeOutput() }
    scope.launch {
      readInput()
      // The writer sends the replies queued so far, then closes the transport
      outgoing.close()
    }
  }

  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    outgoing.send(message)
  }

  override suspend fun close() {
    finish()
  }

  /** Reads and handles messages until [input] ends or fails. */
  private suspend fun readInput() {
    val messages = ReadBuffer()
    val chunk = Buffer()
    try {
      while (true) {
        currentCoroutineContext().ensureActive()
        // Blocks this IO thread until the client writes or closes its end
        if (input.readAtMostTo(chunk, READ_SIZE) == -1L) break
        messages.append(chunk.readByteArray())
        while (true) {
          val message = try {
            messages.readMessage()
          } catch (e: Exception) {
            log.w(e) { "Failed to parse MCP input" }
            _onError(e)
            null
          } ?: break
          handle(message)
        }
      }
      log.d { "Input ended" }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w(e) { "Failed to read MCP input" }
      _onError(e)
    } finally {
      runCatching { input.close() }
        .onFailure { log.d { "Failed to close MCP input: ${it.describeCauses()}" } }
    }
  }

  private suspend fun handle(message: JSONRPCMessage) {
    try {
      _onMessage(message)
    } catch (e: CancellationException) {
      // Only closing the transport stops the loop: a handler's own timeout must not end reading
      currentCoroutineContext().ensureActive()
      log.w(e) { "MCP message handler was cancelled" }
      _onError(e)
    } catch (e: Exception) {
      log.w(e) { "Failed to handle MCP message" }
      _onError(e)
    }
  }

  /** Writes queued messages until [outgoing] is closed and drained, then closes the transport. */
  private suspend fun writeOutput() {
    try {
      for (message in outgoing) {
        output.writeString(serializeMessage(message))
        output.flush()
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w(e) { "Failed to write MCP output" }
      _onError(e)
    } finally {
      runCatching { output.close() }
        .onFailure { log.d { "Failed to close MCP output: ${it.describeCauses()}" } }
      finish()
    }
  }

  private fun finish() {
    if (!closed.compareAndSet(false, true)) return
    scope.cancel()
    outgoing.close()
    invokeOnCloseCallback()
  }

  private companion object {
    const val READ_SIZE = 8192L
  }
}
