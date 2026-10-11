package com.linroid.ketch.hooks

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Starts the processes of command hooks; tests replace it. */
fun interface ProcessLauncher {
  /**
   * Starts [command], the program and its arguments, never through a shell, with [environment]
   * added to the variables this process has, in [workingDirectory] or this process's own.
   *
   * @throws IOException when the program cannot be started
   */
  fun launch(
    command: List<String>,
    environment: Map<String, String>,
    workingDirectory: File?,
  ): HookProcess

  companion object {
    /** Starts real processes, discarding what they print. */
    val System: ProcessLauncher = ProcessLauncher { command, environment, workingDirectory ->
      val process = ProcessBuilder(command)
        .directory(workingDirectory)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .apply { environment().putAll(environment) }
        .start()
      SystemProcess(process)
    }
  }
}

/** A started hook process. */
interface HookProcess {
  /** The process's standard input. */
  val stdin: OutputStream

  /** Waits up to [timeout] for the process to exit; `true` when it did. */
  fun waitFor(timeout: Duration): Boolean

  /** The exit status, once it exited. */
  val exitCode: Int

  /** Stops the process and the processes it started. */
  fun kill()
}

private class SystemProcess(private val process: Process) : HookProcess {
  override val stdin: OutputStream get() = process.outputStream

  override fun waitFor(timeout: Duration): Boolean =
    process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

  override val exitCode: Int get() = process.exitValue()

  override fun kill() {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
  }
}

/** How a hook run ended. */
internal sealed interface HookOutcome {
  data object Done : HookOutcome

  /** It failed; [reason] never quotes URLs, headers or arguments. */
  data class Failed(val reason: String) : HookOutcome
}

/** Runs command hooks through [launcher]. */
internal class CommandRunner(
  private val launcher: ProcessLauncher,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * Runs [command] with [payload] on its standard input and in its environment, and waits for it
   * up to [timeout], killing it when it takes longer. Cancelling the call stops waiting but leaves
   * the process running.
   */
  suspend fun run(
    command: List<String>,
    workingDirectory: String?,
    timeout: Duration,
    payload: HookPayload,
  ): HookOutcome = runInterruptible(io) {
    val process = try {
      launcher.launch(command, payload.environment(), workingDirectory?.let(::File))
    } catch (e: IOException) {
      return@runInterruptible HookOutcome.Failed("could not start (${e::class.simpleName})")
    }
    try {
      process.stdin.use { it.write(payload.toJson().toByteArray()) }
    } catch (_: IOException) {
      // The command does not read its input, and may have exited already: not a failure.
    }
    if (!process.waitFor(timeout)) {
      process.kill()
      return@runInterruptible HookOutcome.Failed("timed out after $timeout, stopped")
    }
    val code = process.exitCode
    if (code == 0) HookOutcome.Done else HookOutcome.Failed("exited with status $code")
  }
}
