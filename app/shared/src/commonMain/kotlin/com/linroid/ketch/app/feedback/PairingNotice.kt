package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.PairingAsk
import com.linroid.ketch.app.instance.PairingRequests
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.notify_pairing_body
import ketch.app.shared.generated.resources.notify_pairing_title
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The notification about [ask], a device that wants this one's access code, posted while the
 * app is not in front so its owner comes to answer: "Pixel 9 wants to control this Mac".
 */
suspend fun pairingNotificationCopy(ask: PairingAsk): NotificationCopy = NotificationCopy(
  title = Res.string.notify_pairing_title.text(ask.name, localDeviceNounInSentence()).load(),
  body = Res.string.notify_pairing_body.text(ask.code).load(),
)

/**
 * How the hosts raise system notifications for pairing requests, which the owner answers in
 * Settings › Sharing; inside the app, a toast leads there instead (see
 * [com.linroid.ketch.app.state.AppState]).
 */
object PairingNotices {
  /**
   * Posts each request [requests] waits on once while the app is not [inFront]: when it arrives,
   * or later, when the app leaves the front. Each is [withdraw]n once it is answered, withdrawn
   * or expired. Runs until canceled, then withdraws the notifications still up.
   *
   * @param inFront whether the app is in front, as it changes.
   */
  suspend fun follow(
    requests: PairingRequests,
    inFront: Flow<Boolean>,
    post: suspend (PairingAsk) -> Unit,
    withdraw: (Long) -> Unit,
  ): Nothing {
    val posted = LinkedHashSet<Long>()
    try {
      combine(requests.pending, inFront.distinctUntilChanged(), ::Pair).collect { (asks, front) ->
        val waiting = asks.mapTo(HashSet()) { it.id }
        val gone = posted.filterNot { it in waiting }
        posted.removeAll(gone.toSet())
        gone.forEach(withdraw)
        if (front) return@collect
        for (ask in asks) {
          if (posted.add(ask.id)) post(ask)
        }
      }
      awaitCancellation()
    } finally {
      posted.forEach(withdraw)
    }
  }
}
