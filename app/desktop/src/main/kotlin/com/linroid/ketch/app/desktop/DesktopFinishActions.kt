package com.linroid.ketch.app.desktop

import com.linroid.ketch.app.state.FinishAction
import com.linroid.ketch.app.state.FinishActions
import com.sun.jna.Function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Quits Ketch, puts this computer to sleep or shuts it down once downloads finish
 * (`WhenDownloadsFinish`). Sleep and shut down ask the system as the user would, without an
 * administrator: macOS through System Events (which asks the user once to let Ketch control it;
 * `pmset sleepnow` needs root), Windows through `SetSuspendState` and `shutdown`, Linux through
 * logind's `systemctl`. Shutting down lets the system quit Ketch as it quits every app.
 *
 * @param os the system this runs on.
 * @param quit quits Ketch without asking, as installing an update does.
 * @param run runs a command and throws when it fails, for tests.
 */
internal class DesktopFinishActions(
  private val os: DesktopOs = DesktopOs.current,
  private val quit: () -> Unit,
  private val run: (List<String>) -> Unit = ::runCommand,
) : FinishActions {
  override val supported: List<FinishAction> =
    listOf(FinishAction.Quit, FinishAction.Sleep, FinishAction.ShutDown)

  override suspend fun perform(action: FinishAction) {
    when (action) {
      FinishAction.Nothing -> Unit
      FinishAction.Quit -> quit()
      FinishAction.Sleep, FinishAction.ShutDown -> withContext(Dispatchers.IO) {
        if (action == FinishAction.Sleep && os == DesktopOs.WINDOWS) {
          windowsSleep()
        } else {
          run(finishCommand(os, action))
        }
      }
    }
  }
}

/** The command that carries out [action] on [os], other than sleep on Windows. */
internal fun finishCommand(os: DesktopOs, action: FinishAction): List<String> = when (action) {
  FinishAction.Sleep -> when (os) {
    DesktopOs.MAC -> systemEvents("sleep")
    DesktopOs.WINDOWS -> error("Windows sleeps through SetSuspendState")
    DesktopOs.LINUX -> listOf("systemctl", "suspend")
  }
  FinishAction.ShutDown -> when (os) {
    DesktopOs.MAC -> systemEvents("shut down")
    DesktopOs.WINDOWS -> listOf("shutdown", "/s", "/t", "0")
    DesktopOs.LINUX -> listOf("systemctl", "poweroff")
  }
  FinishAction.Nothing, FinishAction.Quit -> error("$action runs no command")
}

private fun systemEvents(command: String) =
  listOf("osascript", "-e", "tell application \"System Events\" to $command")

/**
 * `SetSuspendState(FALSE, FALSE, FALSE)`: sleep rather than hibernate, letting applications
 * object, with wake events on. It returns once the computer wakes.
 */
private fun windowsSleep() {
  val suspend = Function.getFunction("powrprof", "SetSuspendState", Function.ALT_CONVENTION)
  if (suspend.invokeInt(arrayOf<Any>(false, false, false)) == 0) {
    throw IOException("SetSuspendState refused to put the computer to sleep")
  }
}

private fun runCommand(command: List<String>) {
  val process = ProcessBuilder(command).redirectErrorStream(true).start()
  if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    process.destroy()
    throw IOException("${command.first()} did not finish in $COMMAND_TIMEOUT_SECONDS s")
  }
  if (process.exitValue() != 0) {
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    throw IOException(
      "${command.first()} exited with ${process.exitValue()}: ${output.take(MAX_OUTPUT)}",
    )
  }
}

// Shutting down asks every app to quit first, which may take a while.
private const val COMMAND_TIMEOUT_SECONDS = 60L
private const val MAX_OUTPUT = 200
