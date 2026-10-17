package com.linroid.ketch.server.mdns

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * [MdnsRegistrar] implementation using the native `dns-sd` command
 * on macOS.
 *
 * JmDNS conflicts with macOS's built-in mDNSResponder daemon, so
 * this delegates to `dns-sd -R` which registers through the system
 * mDNS stack. The subprocess stays alive to keep the service
 * advertised.
 *
 * `dns-sd` runs under [stdinBoundCommand], which stops it once its
 * stdin pipe from this process ends: on [unregister], which closes
 * the pipe, and when this process exits however it exits, as a
 * `kill -9` leaves no hook to run but still closes the pipe. A plain
 * child would keep advertising a dead server.
 */
internal class NativeMdnsRegistrar : MdnsRegistrar {
  private val log = KetchLogger("NativeMdnsRegistrar")
  @Volatile private var process: Process? = null

  override suspend fun register(
    serviceType: String,
    serviceName: String,
    port: Int,
    metadata: Map<String, String>,
  ) {
    unregister()
    val cmd = mutableListOf(
      "dns-sd", "-R",
      serviceName, serviceType, ".", port.toString(),
    )
    metadata.forEach { (key, value) -> cmd.add("$key=$value") }
    log.d { "Exec: ${cmd.joinToString(" ")}" }
    process = withContext(Dispatchers.IO) {
      ProcessBuilder(stdinBoundCommand(cmd))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectErrorStream(true)
        .start()
    }
  }

  override suspend fun unregister() {
    val proc = process ?: return
    process = null
    // Runs in the finally of a cancelled coroutine too, and waits so that a following register
    // never finds the old registration still holding the name.
    withContext(NonCancellable + Dispatchers.IO) {
      runCatching { proc.outputStream.close() }
      if (!proc.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        log.w { "dns-sd did not stop within ${STOP_TIMEOUT_MS}ms; killing it" }
        runCatching { proc.descendants().forEach { it.destroyForcibly() } }
        proc.destroyForcibly()
      }
    }
  }

  private companion object {
    const val STOP_TIMEOUT_MS = 2_000L
  }
}

/**
 * Runs [command] under `/bin/sh` until the shell's stdin reaches end of file, then stops it with
 * `SIGTERM` and waits for it to exit.
 *
 * The shell stops the command too when it gets `SIGTERM` or `SIGHUP`. It ignores `SIGINT` and
 * `SIGQUIT`, which the command ignores already (a shell without job control starts background
 * commands that way), so a Ctrl-C in a terminal leaves the end to the parent, whose exit closes
 * the pipe. The arguments are positional parameters, never parsed by the shell.
 */
internal fun stdinBoundCommand(command: List<String>): List<String> {
  require(command.isNotEmpty()) { "command is empty" }
  return listOf("/bin/sh", "-c", STDIN_BOUND_SCRIPT, "ketch-mdns") + command
}

private val STDIN_BOUND_SCRIPT = listOf(
  "trap '' INT QUIT",
  "\"\$@\" &",
  "child=\$!",
  "trap 'kill \"\$child\" 2>/dev/null; exit 143' HUP TERM",
  "read -r _",
  "kill \"\$child\" 2>/dev/null",
  "wait \"\$child\"",
).joinToString("\n")
