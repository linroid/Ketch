package com.linroid.ketch.app.instance

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.config.TorrentSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KetchLogger("UploadNetwork")

/**
 * How long uploads must be allowed before they resume. Every resume rechecks each torrent meant
 * to seed, so a Wi-Fi connection that drops for a moment must not resume them each time.
 */
internal val UNPAUSE_SETTLE: Duration = 3.seconds

/**
 * Whether uploads pause under [settings] while the device is on a network that is [unmetered]
 * or not: while [TorrentSettings.uploadWifiOnly] is on and it is not, whatever
 * [TorrentSettings.upload] says, so choosing another upload mode on mobile data never lets an
 * upload through.
 */
fun uploadsPaused(settings: TorrentSettings, unmetered: Boolean): Boolean =
  settings.uploadWifiOnly && !unmetered

/**
 * Pauses and resumes uploads with [pause] as [settings] and the network change, until
 * cancelled: as soon as uploads must pause, and once they have been allowed for
 * [UNPAUSE_SETTLE] when they may resume. Nothing happens before both have a value, and each
 * change of what uploads may do calls [pause] once. A [pause] that fails is logged, and the next
 * change calls it again. A network that can no longer be followed counts as metered from then
 * on, so uploads never go on over mobile data unseen; [settings] are still followed.
 *
 * @param unmetered whether the device is on Wi-Fi or another unmetered network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun followUploadNetwork(
  settings: Flow<TorrentSettings>,
  unmetered: Flow<Boolean>,
  pause: suspend (Boolean) -> Unit,
) {
  val network = unmetered.catch { e ->
    log.w { "Stopped following the network for uploads: ${e.describeCauses()}" }
    emit(false)
  }
  combine(settings, network) { current, free -> uploadsPaused(current, free) }
    .distinctUntilChanged()
    .transformLatest { paused ->
      if (!paused) delay(UNPAUSE_SETTLE)
      emit(paused)
    }
    .distinctUntilChanged()
    .collect { paused ->
      try {
        pause(paused)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        val action = if (paused) "pause" else "resume"
        log.w { "Couldn't $action uploads: ${e.describeCauses()}" }
      }
    }
}
