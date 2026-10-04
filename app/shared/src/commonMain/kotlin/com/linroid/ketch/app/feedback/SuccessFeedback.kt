package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.state.AiDiscoverController
import com.linroid.ketch.config.NotificationSettings
import kotlinx.coroutines.awaitCancellation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Plays success feedback on one platform: the [Chime], a short vibration, or both. It stays quiet
 * the way the system's notifications do where it can tell: silent mode and Do Not Disturb mute
 * it, and vibrate mode keeps only the vibration. It never throws.
 */
interface SuccessFeedbackPlayer {
  /**
   * Plays the chime when [sound], and vibrates briefly when [vibrate] and the device can. Called
   * from the main thread, never with both `false`.
   */
  fun play(sound: Boolean, vibrate: Boolean)
}

/**
 * When the hosts play success feedback: as a download finishes and as a Discover search finds
 * downloads, with the sound and the vibration the user's [NotificationSettings] turn on. Feedback
 * within [GAP] of the last is left out, so a batch of downloads finishing, or results that come
 * with them, plays once. The hosts share it so every platform decides alike. Meant to be used
 * from the main thread.
 *
 * @param timeSource measures the [GAP].
 */
class SuccessFeedback(
  private val player: SuccessFeedbackPlayer,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private var last: TimeMark? = null

  /** Plays when a turn of [discover] ends with results, as [settings] allow; until canceled. */
  suspend fun follow(
    discover: AiDiscoverController,
    settings: () -> NotificationSettings,
  ): Nothing {
    discover.found.collect { play(settings()) }
    awaitCancellation()
  }

  /**
   * Plays when [event] is a download finishing that the host reports, as its [delivery] says.
   *
   * @param notificationsAlert whether the host's system notifications sound or vibrate on their
   *   own, as Android's channels do; a finished download posted as one then leaves it to them.
   */
  fun reported(
    event: ActivityEvent,
    delivery: ActivityRouting.Delivery,
    settings: NotificationSettings,
    notificationsAlert: Boolean,
  ) {
    if (event !is ActivityEvent.Completed && event !is ActivityEvent.CompletedBatch) return
    val alerted = delivery.notify && notificationsAlert
    if ((delivery.toast || delivery.notify) && !alerted) play(settings)
  }

  private fun play(settings: NotificationSettings) {
    if (!settings.successSound && !settings.successVibration) return
    if (last?.let { it.elapsedNow() < GAP } == true) return
    last = timeSource.markNow()
    player.play(sound = settings.successSound, vibrate = settings.successVibration)
  }

  companion object {
    /** Shortest time between two plays. */
    val GAP: Duration = 1500.milliseconds
  }
}

/**
 * The soft chime of the success feedback: two quiet, rising bell-like notes
 * that fade out within half a second, synthesized rather than shipped as a file.
 */
object Chime {
  /** Samples per second of [pcm]. */
  const val SAMPLE_RATE: Int = 44_100

  /** Loudest the chime gets, as a fraction of full scale; it stays well below it. */
  const val PEAK: Double = 0.3

  private const val DURATION_SECONDS = 0.55
  private const val ATTACK_SECONDS = 0.004
  private const val RELEASE_SECONDS = 0.03
  private const val DECAY_SECONDS = 0.11

  // E6 then A6, the second a fourth above and a little quieter.
  private val notes = listOf(
    Note(frequency = 1318.51, start = 0.0, gain = 1.0),
    Note(frequency = 1760.0, start = 0.09, gain = 0.8),
  )

  private class Note(val frequency: Double, val start: Double, val gain: Double)

  /** The chime as mono 16-bit PCM at [SAMPLE_RATE]; starts and ends silent, so it never clicks. */
  fun pcm(): ShortArray {
    val count = (DURATION_SECONDS * SAMPLE_RATE).roundToInt()
    val raw = DoubleArray(count) { i ->
      val t = i.toDouble() / SAMPLE_RATE
      var value = 0.0
      for (note in notes) {
        val local = t - note.start
        if (local < 0) continue
        val attack = min(1.0, local / ATTACK_SECONDS)
        // A faint octave overtone, which fades faster, makes it ring like a small bell.
        val tone = sin(2 * PI * note.frequency * local) +
          0.2 * exp(-local / DECAY_SECONDS) * sin(4 * PI * note.frequency * local)
        value += note.gain * attack * exp(-local / DECAY_SECONDS) * tone
      }
      // Fades the tail out, so the last sample is silent.
      value * min(1.0, (DURATION_SECONDS - t) / RELEASE_SECONDS).coerceAtLeast(0.0)
    }
    val loudest = raw.maxOf { abs(it) }
    val scale = if (loudest == 0.0) 0.0 else PEAK * Short.MAX_VALUE / loudest
    return ShortArray(count) { (raw[it] * scale).roundToInt().toShort() }
  }

  /** [pcm] as little-endian bytes, as audio APIs that take bytes expect. */
  fun pcmBytes(): ByteArray {
    val samples = pcm()
    val bytes = ByteArray(samples.size * 2)
    samples.forEachIndexed { i, sample ->
      bytes[2 * i] = sample.toInt().toByte()
      bytes[2 * i + 1] = (sample.toInt() shr 8).toByte()
    }
    return bytes
  }
}
