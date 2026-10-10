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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
 * and `cat` then exits, which releases the lock. A lock that ends by itself while still wanted,
 * such as when logind restarts, is taken again after [retryDelay], doubling up to [maxRetries]
 * times in a row; one that held for a minute or more starts that count over.
 *
 * It does not block sleep itself: logind asks for an administrator to let a user suspend while
 * another application blocks it, which would stop the user's own suspend and lid close. Desktops
 * that ignore logind's idle locks, such as GNOME, still sleep on their own idle timer.
 *
 * @param command runs the lock until its input ends.
 */
internal class LogindIdleInhibitor(
  reason: String,
  private val command: List<String> = listOf(
    "systemd-inhibit", "--what=idle", "--who=Ketch", "--why=$reason", "--mode=block", "cat"
  ),
  private val retryDelay: Duration = 5.seconds,
  private val maxRetries: Int = 5,
) : SleepInhibitor {
  // Guards the fields below: the process exits and retries run on other threads.
  private val lock = Any()
  private var wanted = false
  private var process: Process? = null
  private var retry: ScheduledFuture<*>? = null
  private var retries = 0

  // Without logind it fails each time downloads start, so it warns once.
  private var failed = false

  private val scheduler: ScheduledExecutorService by lazy {
    Executors.newSingleThreadScheduledExecutor { task ->
      Thread(task, "ketch-keep-awake").apply { isDaemon = true }
    }
  }

  override fun acquire() {
    synchronized(lock) {
      if (wanted) return
      wanted = true
      retries = 0
      start()
    }
    log.i { "Keeping the system awake" }
  }

  override fun release() {
    val held = synchronized(lock) {
      if (!wanted) return
      wanted = false
      retry?.cancel(false)
      retry = null
      process.also { process = null }
    }
    if (held != null) stop(held)
    log.i { "Letting the system sleep" }
  }

  // Called while holding the lock.
  private fun start() {
    val started = try {
      ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (e: IOException) {
      failure { "Couldn't keep the system awake: ${e.describeCauses()}" }
      retryLater()
      return
    }
    process = started
    val startedAt = TimeSource.Monotonic.markNow()
    started.onExit().thenAccept { exited -> onExit(exited, startedAt) }
  }

  private fun onExit(exited: Process, startedAt: TimeMark) {
    // A lock Ketch released ended as it should.
    if (synchronized(lock) { process !== exited }) return
    val output = exited.inputStream.bufferedReader().use { it.readText() }.trim()
    synchronized(lock) {
      if (process !== exited) return
      process = null
      failure { "systemd-inhibit exited with ${exited.exitValue()}: ${output.take(MAX_OUTPUT)}" }
      if (startedAt.elapsedNow() >= STABLE) retries = 0
      retryLater()
    }
  }

  // Called while holding the lock.
  private fun retryLater() {
    if (!wanted) return
    if (retries >= maxRetries) {
      failure { "Gave up keeping the system awake after $retries retries" }
      return
    }
    val delay = retryDelay * (1 shl retries)
    retries++
    val restart = Runnable {
      synchronized(lock) {
        retry = null
        if (wanted && process == null) start()
      }
    }
    retry = scheduler.schedule(restart, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
  }

  private fun stop(held: Process) {
    try {
      held.outputStream.close()
    } catch (e: IOException) {
      log.d { "Couldn't close the input of systemd-inhibit: ${e.describeCauses()}" }
    }
    held.destroy()
  }

  // Called while holding the lock.
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
    val STABLE = 1.minutes
  }
}
