package com.linroid.ketch.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.rememberResourceEnvironment
import org.jetbrains.compose.resources.stringResource
import kotlin.concurrent.Volatile

/**
 * Text for the user, kept as string resources and their arguments until it is shown, so it reads
 * in the language of the screen that shows it.
 *
 * State and model code returns it; composables turn it into a string with [resolve] and other
 * code with [load]. A format argument may be a [UiText] itself, which resolves in the same
 * language. Build it with [StringResource.text], [PluralStringResource.text], [verbatim] and
 * [joinText].
 */
@Immutable
sealed interface UiText {
  /** The string [resource] with its format [args]: strings, numbers or [UiText]. */
  @Immutable
  data class Resource(val resource: StringResource, val args: List<Any> = emptyList()) : UiText {
    override fun toString(): String = marker(resource.key, args)
  }

  /** The form of the plural [resource] for [quantity], with its format [args]. */
  @Immutable
  data class Plural(
    val resource: PluralStringResource,
    val quantity: Int,
    val args: List<Any>,
  ) : UiText {
    override fun toString(): String = marker("${resource.key}[$quantity]", args)
  }

  /** [text] as it is: a name, a host, a path, a number or anything else never translated. */
  @Immutable
  data class Verbatim(val text: String) : UiText {
    override fun toString(): String = "⟦\"$text\"⟧"
  }

  /** [parts] in a row with [separator] between them, such as "Paused · 40%". */
  @Immutable
  data class Joined(val parts: List<UiText>, val separator: String) : UiText {
    override fun toString(): String = parts.joinToString(separator)
  }

  companion object {
    /** No text. */
    val Empty: UiText = Verbatim("")
  }
}

/**
 * What [UiText.toString] prints: the resource key and arguments in brackets, so text that was
 * put in a string template by mistake stands out instead of reading like a translation.
 */
private fun marker(key: String, args: List<Any>): String =
  if (args.isEmpty()) "⟦$key⟧" else "⟦$key(${args.joinToString()})⟧"

/** This string with its format [args], which may be [UiText] themselves. */
fun StringResource.text(vararg args: Any): UiText = UiText.Resource(this, args.toList())

/**
 * The form of this plural for [quantity] with its format [args]. Without [args] the quantity
 * itself is the only argument, as `%1$d` in "%1$d files".
 */
fun PluralStringResource.text(quantity: Int, vararg args: Any): UiText =
  UiText.Plural(this, quantity, if (args.isEmpty()) listOf(quantity) else args.toList())

/** [text] shown as it is, never translated: names, hosts, paths, file names, error messages. */
fun verbatim(text: String): UiText = UiText.Verbatim(text)

/** These parts with [separator] between them. */
fun List<UiText>.joinText(separator: String = SEPARATOR): UiText = when (size) {
  0 -> UiText.Empty
  1 -> single()
  else -> UiText.Joined(this, separator)
}

/** Separator of the parts of a line of details, as in "Paused · 40%". */
const val SEPARATOR: String = " · "

/** Whether this is [UiText.Empty] or verbatim text with nothing in it. */
fun UiText.isEmpty(): Boolean = this is UiText.Verbatim && text.isEmpty()

/** This text in the language of the composition. */
@Composable
fun UiText.resolve(): String = when (this) {
  is UiText.Verbatim -> text
  is UiText.Resource -> if (args.isEmpty()) {
    stringResource(resource)
  } else {
    stringResource(resource, *args.map { it.resolveArgument() }.toTypedArray())
  }
  is UiText.Plural ->
    pluralStringResource(resource, quantity, *args.map { it.resolveArgument() }.toTypedArray())
  is UiText.Joined -> parts.map { it.resolve() }.joinToString(separator)
}

/**
 * This text in the language of the app's windows, for code outside composition such as
 * notifications, or in the system's language before a window shows.
 */
suspend fun UiText.load(): String = load(windowEnvironment ?: getSystemResourceEnvironment())

/**
 * Makes [load] read text in the language this composition shows, so text loaded outside
 * composition matches the screen. The root of every window calls it.
 */
@Composable
fun ProvideLoadEnvironment() {
  val environment = rememberResourceEnvironment()
  SideEffect { windowEnvironment = environment }
}

@Volatile
private var windowEnvironment: ResourceEnvironment? = null

/** This text in the language of [environment]. */
suspend fun UiText.load(environment: ResourceEnvironment): String = when (this) {
  is UiText.Verbatim -> text
  is UiText.Resource -> if (args.isEmpty()) {
    getString(environment, resource)
  } else {
    getString(environment, resource, *args.map { it.loadArgument(environment) }.toTypedArray())
  }
  is UiText.Plural -> getPluralString(
    environment,
    resource,
    quantity,
    *args.map { it.loadArgument(environment) }.toTypedArray(),
  )
  is UiText.Joined -> parts.map { it.load(environment) }.joinToString(separator)
}

@Composable
private fun Any.resolveArgument(): Any = if (this is UiText) resolve() else this

private suspend fun Any.loadArgument(environment: ResourceEnvironment): Any =
  if (this is UiText) load(environment) else this
