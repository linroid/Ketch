package com.linroid.ketch.app.platform

import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_this_browser
import ketch.app.shared.generated.resources.device_this_browser_in_sentence
import ketch.app.shared.generated.resources.device_this_computer
import ketch.app.shared.generated.resources.device_this_computer_in_sentence
import ketch.app.shared.generated.resources.device_this_ipad
import ketch.app.shared.generated.resources.device_this_ipad_in_sentence
import ketch.app.shared.generated.resources.device_this_mac
import ketch.app.shared.generated.resources.device_this_mac_in_sentence
import ketch.app.shared.generated.resources.device_this_pc
import ketch.app.shared.generated.resources.device_this_pc_in_sentence
import ketch.app.shared.generated.resources.device_this_phone
import ketch.app.shared.generated.resources.device_this_phone_in_sentence
import ketch.app.shared.generated.resources.device_this_tablet
import ketch.app.shared.generated.resources.device_this_tablet_in_sentence
import org.jetbrains.compose.resources.StringResource

/**
 * What kind of device the app runs on, which names the embedded device.
 *
 * @property noun how the app names it on its own: "This Mac".
 * @property inSentence how a sentence names it: "this Mac", as in "Control this Mac from your
 *   phone".
 */
enum class LocalDeviceKind(val noun: StringResource, val inSentence: StringResource) {
  Mac(Res.string.device_this_mac, Res.string.device_this_mac_in_sentence),
  Pc(Res.string.device_this_pc, Res.string.device_this_pc_in_sentence),
  Computer(Res.string.device_this_computer, Res.string.device_this_computer_in_sentence),
  Phone(Res.string.device_this_phone, Res.string.device_this_phone_in_sentence),
  Tablet(Res.string.device_this_tablet, Res.string.device_this_tablet_in_sentence),
  IPad(Res.string.device_this_ipad, Res.string.device_this_ipad_in_sentence),
  Browser(Res.string.device_this_browser, Res.string.device_this_browser_in_sentence),
}

/** The kind of device the app runs on. */
expect fun localDeviceKind(): LocalDeviceKind

/**
 * How the app names the device it runs on, the embedded one: "This Mac", "This PC",
 * "This computer", "This phone", "This tablet" (Android), "This iPad" or "This browser".
 * Its host name is secondary text.
 */
fun localDeviceNoun(): UiText = localDeviceKind().noun.text()

/** [localDeviceNoun] as a sentence names it: "this Mac". */
fun localDeviceNounInSentence(): UiText = localDeviceKind().inSentence.text()
