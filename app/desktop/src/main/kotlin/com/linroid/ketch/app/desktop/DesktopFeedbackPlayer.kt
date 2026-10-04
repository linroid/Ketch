package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.Chime
import com.linroid.ketch.app.feedback.SuccessFeedbackPlayer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import kotlin.concurrent.thread

/**
 * Plays success feedback's [Chime] through Java Sound on the system's default output. Computers
 * do not vibrate, so only the sound plays; without an audio device nothing does.
 */
internal class DesktopFeedbackPlayer : SuccessFeedbackPlayer {
  private val log = KetchLogger("FeedbackPlayer")
  private val pcm by lazy { Chime.pcmBytes() }

  override fun play(sound: Boolean, vibrate: Boolean) {
    if (!sound) return
    // Opening a line can block for a moment, so it never runs on the UI thread.
    thread(isDaemon = true, name = "ketch-chime") {
      try {
        val format = AudioFormat(Chime.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val clip = AudioSystem.getClip()
        clip.addLineListener { event -> if (event.type == LineEvent.Type.STOP) clip.close() }
        clip.open(format, pcm, 0, pcm.size)
        clip.start()
      } catch (e: Exception) {
        log.d { "Couldn't play the chime: ${e.describeCauses()}" }
      }
    }
  }
}
