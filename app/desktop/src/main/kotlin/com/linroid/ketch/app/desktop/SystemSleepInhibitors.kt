package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.state.SleepInhibitor
import com.sun.jna.Function
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val log = KetchLogger("KeepAwake")

/**
 * This system's [SleepInhibitor], which names Ketch's [reason] where the system lists what keeps
 * it awake. Each keeps the system from sleeping while idle only: the display still turns off, and
 * the user can still put the system to sleep or close the lid. The system lets go of each when
 * Ketch ends, however it ends.
 */
internal fun systemSleepInhibitor(reason: String): SleepInhibitor = when (DesktopOs.current) {
  DesktopOs.MAC -> MacSleepAssertion(reason)
  DesktopOs.WINDOWS -> WindowsExecutionState()
  DesktopOs.LINUX -> LogindIdleInhibitor(reason)
}

/**
 * An IOKit power assertion that prevents idle system sleep, which `pmset -g assertions` lists by
 * [reason]. macOS releases it when the process ends.
 */
internal class MacSleepAssertion(private val reason: String) : SleepInhibitor {
  private var assertion: Int? = null
  private val natives by lazy { Natives() }

  override fun acquire() {
    if (assertion != null) return
    try {
      val natives = natives
      val type = natives.cfString(ASSERTION_TYPE)
      val name = natives.cfString(reason)
      try {
        val id = IntByReference()
        val result = natives.createAssertion.invokeInt(arrayOf(type, LEVEL_ON, name, id))
        if (result == SUCCESS) {
          assertion = id.value
          log.i { "Keeping the system awake" }
        } else {
          log.w { "Couldn't keep the system awake: IOKit error 0x${result.toUInt().toString(16)}" }
        }
      } finally {
        natives.release.invokeVoid(arrayOf(name))
        natives.release.invokeVoid(arrayOf(type))
      }
    } catch (e: Throwable) {
      log.w { "Couldn't keep the system awake: ${e.describeCauses()}" }
    }
  }

  override fun release() {
    val id = assertion ?: return
    assertion = null
    try {
      val result = natives.releaseAssertion.invokeInt(arrayOf<Any>(id))
      if (result == SUCCESS) {
        log.i { "Letting the system sleep" }
      } else {
        log.w { "Couldn't let the system sleep: IOKit error 0x${result.toUInt().toString(16)}" }
      }
    } catch (e: Throwable) {
      log.w { "Couldn't let the system sleep: ${e.describeCauses()}" }
    }
  }

  private class Natives {
    private val coreFoundation = NativeLibrary.getInstance("CoreFoundation")
    private val ioKit = NativeLibrary.getInstance("IOKit")
    private val createString: Function = coreFoundation.getFunction("CFStringCreateWithCString")
    val release: Function = coreFoundation.getFunction("CFRelease")
    val createAssertion: Function = ioKit.getFunction("IOPMAssertionCreateWithName")
    val releaseAssertion: Function = ioKit.getFunction("IOPMAssertionRelease")

    /** A new CFString of [text], which the caller releases. */
    fun cfString(text: String): Pointer {
      val bytes = (text + '\u0000').encodeToByteArray()
      return checkNotNull(createString.invokePointer(arrayOf(null, bytes, UTF8_ENCODING))) {
        "Couldn't create a CFString"
      }
    }
  }

  private companion object {
    // kIOPMAssertPreventUserIdleSystemSleep: the display may sleep, the system may not.
    const val ASSERTION_TYPE = "PreventUserIdleSystemSleep"
    const val LEVEL_ON = 255 // kIOPMAssertionLevelOn
    const val SUCCESS = 0 // kIOReturnSuccess
    const val UTF8_ENCODING = 0x08000100 // kCFStringEncodingUTF8
  }
}

/**
 * `SetThreadExecutionState`, which keeps Windows from sleeping while idle. The state belongs to
 * the thread that sets it and lasts until that thread changes it or ends, so one thread of its own
 * makes every call.
 */
internal class WindowsExecutionState : SleepInhibitor {
  private var held = false
  private val thread: ExecutorService by lazy {
    Executors.newSingleThreadExecutor { task ->
      Thread(task, "ketch-keep-awake").apply { isDaemon = true }
    }
  }
  private val setState: Function by lazy {
    Function.getFunction("kernel32", "SetThreadExecutionState", Function.ALT_CONVENTION)
  }

  override fun acquire() {
    if (held) return
    held = true
    thread.execute { set(ES_CONTINUOUS or ES_SYSTEM_REQUIRED, "Keeping the system awake") }
  }

  override fun release() {
    if (!held) return
    held = false
    thread.execute { set(ES_CONTINUOUS, "Letting the system sleep") }
  }

  private fun set(flags: Int, done: String) {
    try {
      // Returns the previous state, or 0 when it fails.
      if (setState.invokeInt(arrayOf<Any>(flags)) == 0) {
        log.w { "SetThreadExecutionState refused 0x${flags.toUInt().toString(16)}" }
      } else {
        log.i { done }
      }
    } catch (e: Throwable) {
      log.w { "Couldn't call SetThreadExecutionState: ${e.describeCauses()}" }
    }
  }

  private companion object {
    const val ES_CONTINUOUS = 0x80000000.toInt()
    const val ES_SYSTEM_REQUIRED = 0x00000001
  }
}

/**
 * A logind inhibitor lock on idle, held by `systemd-inhibit` while its child `cat` waits for input
 * from Ketch that never comes. Releasing closes that input; so does Ketch ending, however it ends,
 * and `cat` then exits, which releases the lock.
 *
 * It does not block sleep itself: logind asks for an administrator to let a user suspend while
 * another application blocks it, which would stop the user's own suspend and lid close. Desktops
 * that ignore logind's idle locks, such as GNOME, still sleep on their own idle timer.
 */
internal class LogindIdleInhibitor(private val reason: String) : SleepInhibitor {
  // Also read when the process exits, on another thread.
  @Volatile private var process: Process? = null

  // Without logind it fails each time downloads start, so it warns once.
  @Volatile private var failed = false

  override fun acquire() {
    if (process != null) return
    val command = listOf(
      "systemd-inhibit", "--what=idle", "--who=Ketch", "--why=$reason", "--mode=block", "cat"
    )
    val started = try {
      ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (e: IOException) {
      failure { "Couldn't keep the system awake: ${e.describeCauses()}" }
      return
    }
    process = started
    log.i { "Keeping the system awake" }
    started.onExit().thenAccept { exited ->
      // Only a lock that ended by itself, such as without logind, is worth a word.
      if (process !== exited) return@thenAccept
      val output = exited.inputStream.bufferedReader().use { it.readText() }.trim()
      failure { "systemd-inhibit exited with ${exited.exitValue()}: ${output.take(MAX_OUTPUT)}" }
    }
  }

  override fun release() {
    val held = process ?: return
    process = null
    try {
      held.outputStream.close()
    } catch (e: IOException) {
      log.d { "Couldn't close the input of systemd-inhibit: ${e.describeCauses()}" }
    }
    held.destroy()
    log.i { "Letting the system sleep" }
  }

  private fun failure(message: () -> String) {
    if (failed) {
      log.d(message = message)
    } else {
      failed = true
      log.w(message = message)
    }
  }

  private companion object {
    const val MAX_OUTPUT = 500
  }
}
