package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.PairingAsk
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.notify_pairing_body
import ketch.app.shared.generated.resources.notify_pairing_title

/**
 * The notification about [ask], a device that wants this one's access code, posted while the
 * app is not in front so its owner comes to answer: "Pixel 9 wants to control this Mac".
 */
suspend fun pairingNotificationCopy(ask: PairingAsk): NotificationCopy = NotificationCopy(
  title = Res.string.notify_pairing_title.text(ask.name, localDeviceNounInSentence()).load(),
  body = Res.string.notify_pairing_body.text(ask.code).load(),
)
