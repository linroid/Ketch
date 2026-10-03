package com.linroid.ketch.app.i18n

import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.language_tag

/**
 * The BCP 47 tag of the language the app's strings are in, such as "de" or "zh-Hant": the
 * system's language when the app is translated to it, else "en".
 */
suspend fun appLanguageTag(): String = Res.string.language_tag.text().load()
