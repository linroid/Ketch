package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KetchLogger("HostName")

private val COMMAND_TIMEOUT = 2.seconds
private val LOOKUP_TIMEOUT = 2.seconds

/** Used when no source reports a name. */
internal const val FALLBACK_HOST_NAME = "Ketch"

/**
 * This computer's name, as the OS reports it without a trailing `.local`, for an instance whose
 * config sets no name.
 *
 * `InetAddress.getLocalHost().hostName` is the same name, but it also resolves the name to an
 * address, which blocks for tens of seconds on a Mac whose name does not resolve quickly. The
 * sources below return the same name without a lookup: the kernel's name on Linux, and the
 * `hostname` command elsewhere. The environment and a time-bounded lookup are the last resorts.
 */
internal fun localHostName(
  kernelHostName: () -> String? = ::readKernelHostName,
  hostnameCommand: () -> String? = { commandOutput(listOf("hostname")) },
  env: (String) -> String? = System::getenv,
  lookUp: () -> String? = { lookUpHostName() },
): String {
  val sources = sequenceOf(
    kernelHostName,
    hostnameCommand,
    { env("COMPUTERNAME") },
    { env("HOSTNAME") },
    lookUp,
  )
  return sources.firstNotNullOfOrNull { source ->
    source()?.trim()?.removeSuffix(".local")?.ifEmpty { null }
  } ?: FALLBACK_HOST_NAME.also {
    log.w { "No host name found; naming this instance $it" }
  }
}

/**
 * Runs `InetAddress.getLocalHost().hostName` (or [lookUp]) on its own daemon thread and gives up
 * after [timeout], leaving the thread to finish on its own since a name-service lookup cannot be
 * interrupted.
 */
internal fun lookUpHostName(
  timeout: Duration = LOOKUP_TIMEOUT,
  lookUp: () -> String = { InetAddress.getLocalHost().hostName },
): String? {
  val task = FutureTask { lookUp() }
  Thread(task, "ketch-host-name").apply { isDaemon = true }.start()
  return try {
    task.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
  } catch (e: TimeoutException) {
    log.w { "Host name lookup did not finish within $timeout" }
    null
  } catch (e: ExecutionException) {
    log.w { "Host name lookup failed: ${(e.cause ?: e).describeCauses()}" }
    null
  }
}

/** The name Linux's `gethostname()` returns, without running a command. */
private fun readKernelHostName(): String? {
  val file = File("/proc/sys/kernel/hostname")
  if (!file.isFile) return null
  return try {
    file.readText()
  } catch (e: IOException) {
    log.d { "Failed to read ${file.path}: ${e.describeCauses()}" }
    null
  }
}

/** Standard output of [command], or null if it cannot run, fails or takes too long. */
private fun commandOutput(command: List<String>): String? {
  val process = try {
    ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
  } catch (e: IOException) {
    log.d { "Failed to run ${command.first()}: ${e.describeCauses()}" }
    return null
  }
  process.outputStream.close()
  // The output is a single short line, so it fits in the pipe until the process exits.
  if (!process.waitFor(COMMAND_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
    process.destroyForcibly()
    log.w { "${command.first()} did not finish within $COMMAND_TIMEOUT" }
    return null
  }
  if (process.exitValue() != 0) {
    log.d { "${command.first()} exited with ${process.exitValue()}" }
    return null
  }
  return process.inputStream.bufferedReader().use { it.readText() }
}
