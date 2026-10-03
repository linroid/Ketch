package com.linroid.ketch.app.i18n

import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.allPluralStringResources
import ketch.app.shared.generated.resources.allStringResources
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/** This text as tests read it, in English; `null` for no text. */
suspend fun UiText?.load(): String? = this?.load()

/** Each of these texts as tests read them, in English. */
suspend fun List<UiText>.load(): List<String> = map { it.load() }

/** The text of verbatim text, such as a fixture's device name, without loading anything. */
val UiText.plain: String
  get() = (this as? UiText.Verbatim)?.text ?: error("$this is not verbatim text")

/**
 * Loads every string once. Compose resources read a string the first time on a thread of their
 * own, which a test's virtual time does not wait for; once loaded, reading it never suspends.
 * Tests whose models read strings while time advances call this first.
 */
suspend fun warmStrings() {
  Res.allStringResources.values.forEach { getString(it) }
  Res.allPluralStringResources.values.forEach { getPluralString(it, 1) }
}
