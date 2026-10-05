package com.linroid.ketch.app.snapshot

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.swing.Swing
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.time.AbstractLongTimeSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The UI thread of the snapshots and render tests, on a virtual clock.
 *
 * Coroutines run on the AWT event thread, as on [Dispatchers.Swing], but their delays and
 * timeouts wait for virtual time rather than the wall clock, and scenes render at it. Time only
 * moves when something lets it pass: a delay or timeout of a test, a coroutine started with
 * [driving], moves it [FRAME] at a time until the delay is over, letting the UI thread run what
 * it has queued between steps. A test renders a frame and lets [FRAME] pass, as [frames] does,
 * so animations, debounces and Undo windows of the app and the scene see the times they would in
 * the app, whether the machine is fast or slow, and no test waits for real time to pass.
 *
 * The clock never goes back; scenes of one test JVM start where the last one left it.
 */
@OptIn(InternalCoroutinesApi::class)
internal object SnapshotClock : CoroutineDispatcher(), Delay {
  /** Virtual time a frame takes. */
  val FRAME: Duration = 16.milliseconds

  /** What a test coroutine runs on: the UI thread, with its delays moving the clock. */
  val driving: CoroutineContext = this + Driver

  private val swing: CoroutineDispatcher = Dispatchers.Swing
  private val timers = PriorityQueue(compareBy<Timer>({ it.at }, { it.order }))
  private var scheduled = 0L

  /** The virtual time, in nanoseconds, as a scene renders at. */
  @Volatile
  var nanos: Long = 0L
    private set

  /** The virtual time, in milliseconds, as pointer events are stamped with. */
  val millis: Long get() = nanos / NANOS_PER_MILLI

  /** The virtual time, for what measures time passing, such as connection rates. */
  val timeSource: TimeSource.WithComparableMarks =
    object : AbstractLongTimeSource(DurationUnit.NANOSECONDS) {
      override fun read(): Long = nanos
    }

  override fun isDispatchNeeded(context: CoroutineContext): Boolean =
    swing.isDispatchNeeded(context)

  override fun dispatch(context: CoroutineContext, block: Runnable) {
    swing.dispatch(context, block)
  }

  override fun scheduleResumeAfterDelay(
    timeMillis: Long,
    continuation: CancellableContinuation<Unit>,
  ) {
    val timer = schedule(timeMillis) { continuation.resume(Unit) }
    continuation.invokeOnCancellation { cancel(timer) }
    if (continuation.context[Driver] != null) drive(timer)
  }

  override fun invokeOnTimeout(
    timeMillis: Long,
    block: Runnable,
    context: CoroutineContext,
  ): DisposableHandle {
    val timer = schedule(timeMillis) { swing.dispatch(context, block) }
    if (context[Driver] != null) drive(timer)
    return DisposableHandle { cancel(timer) }
  }

  /**
   * Moves the clock [duration] forward, resuming every delay that ends by then in the order they
   * end; they run once the UI thread gets to them.
   */
  fun advanceBy(duration: Duration) {
    val target = nanos + duration.inWholeNanoseconds
    while (true) {
      val due = synchronized(this) {
        val next = timers.peek()?.takeIf { it.at <= target } ?: return@synchronized null
        timers.poll()
        next.done = true
        nanos = maxOf(nanos, next.at)
        next
      } ?: break
      due.action()
    }
    nanos = target
  }

  /** Moves the clock a frame at a time, each on a turn of the UI thread, until [timer] is due. */
  private fun drive(timer: Timer) {
    swing.dispatch(EmptyCoroutineContext) {
      if (!timer.done) {
        advanceBy(minOf(FRAME, (timer.at - nanos).nanoseconds))
        drive(timer)
      }
    }
  }

  private fun schedule(timeMillis: Long, action: () -> Unit): Timer = synchronized(this) {
    val at = if (timeMillis >= (Long.MAX_VALUE - nanos) / NANOS_PER_MILLI) {
      Long.MAX_VALUE
    } else {
      nanos + timeMillis.coerceAtLeast(0) * NANOS_PER_MILLI
    }
    Timer(at, scheduled++, action).also { timers += it }
  }

  private fun cancel(timer: Timer) {
    synchronized(this) {
      timer.done = true
      timers.remove(timer)
    }
  }

  private class Timer(val at: Long, val order: Long, val action: () -> Unit) {
    @Volatile
    var done: Boolean = false
  }

  /** Marks a coroutine whose delays move the clock rather than wait for it. */
  private object Driver : CoroutineContext.Element, CoroutineContext.Key<Driver> {
    override val key: CoroutineContext.Key<*> get() = this
  }

  private const val NANOS_PER_MILLI = 1_000_000L
}
