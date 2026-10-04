package com.linroid.ketch.app.feedback

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses

/**
 * Plays success feedback: the [Chime] as a notification sound and a light double-tap vibration.
 *
 * It stays quiet the way notifications do: Do Not Disturb silences both, vibrate mode keeps only
 * the vibration, silent mode silences both, and the sound follows the notification volume.
 */
class AndroidFeedbackPlayer(private val context: Context) : SuccessFeedbackPlayer {
  private val log = KetchLogger("SuccessFeedbackPlayer")
  private val pcm by lazy { Chime.pcm() }

  override fun play(sound: Boolean, vibrate: Boolean) {
    try {
      val notifications = context.getSystemService(NotificationManager::class.java)
      val filter = notifications.currentInterruptionFilter
      if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
        filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
      ) {
        return
      }
      val ringer = context.getSystemService(AudioManager::class.java).ringerMode
      if (sound && ringer == AudioManager.RINGER_MODE_NORMAL) chime()
      if (vibrate && ringer != AudioManager.RINGER_MODE_SILENT) buzz()
    } catch (e: Exception) {
      log.d { "Couldn't play success feedback: ${e.describeCauses()}" }
    }
  }

  private fun chime() {
    val track = AudioTrack.Builder()
      .setAudioAttributes(attributes)
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(Chime.SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build(),
      )
      .setTransferMode(AudioTrack.MODE_STATIC)
      .setBufferSizeInBytes(pcm.size * 2)
      .build()
    try {
      track.write(pcm, 0, pcm.size)
      // Released on the thread that created it, the main one, once the last sample played.
      track.notificationMarkerPosition = pcm.size
      track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
        override fun onMarkerReached(track: AudioTrack) = track.release()

        override fun onPeriodicNotification(track: AudioTrack) = Unit
      })
      track.play()
    } catch (e: Exception) {
      track.release()
      throw e
    }
  }

  private fun buzz() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.getSystemService(Vibrator::class.java)
    }
    if (vibrator?.hasVibrator() != true) return
    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    } else {
      VibrationEffect.createOneShot(BUZZ_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE)
    }
    // As a notification's, so it follows the notification vibration setting and still plays
    // while Ketch is in the background.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      vibrator.vibrate(
        effect,
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
      )
    } else {
      @Suppress("DEPRECATION")
      vibrator.vibrate(effect, attributes)
    }
  }

  private companion object {
    const val BUZZ_MILLIS = 40L

    val attributes: AudioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()
  }
}
