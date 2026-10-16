package com.linroid.ketch.cli

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.remote.ConnectionState
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The instance `ketch mcp` gives its tools, found and connected to by [open] when a tool first
 * needs it and again once the connection is lost, as when the desktop app restarts on another
 * port. While no instance runs, each tool call fails with what [open] throws.
 *
 * @param isConnected whether an instance [open] returned is still connected
 */
internal class InstanceConnection<T : KetchApi>(
  private val open: suspend () -> T,
  private val isConnected: (T) -> Boolean,
) : AutoCloseable {
  private val mutex = Mutex()
  private var current: T? = null

  /** The connected instance, connecting first when there is none. */
  suspend fun get(): KetchApi = mutex.withLock {
    current?.let { if (isConnected(it)) return it else it.close() }
    current = null
    open().also { current = it }
  }

  override fun close() {
    current?.close()
    current = null
  }

  companion object {
    /**
     * Connects to the instances [locate] finds, telling [onAttach] about each one.
     */
    fun remote(
      locate: () -> InstanceEndpoint,
      onAttach: (InstanceEndpoint) -> Unit = {},
    ): InstanceConnection<RemoteKetch> = InstanceConnection(
      open = {
        // The desktop app may still be starting: the locator waits for it on this thread.
        val endpoint = withContext(Dispatchers.IO) { locate() }
        attach(endpoint).ketch.also { onAttach(endpoint) }
      },
      isConnected = { it.connectionState.value == ConnectionState.Connected },
    )
  }
}
