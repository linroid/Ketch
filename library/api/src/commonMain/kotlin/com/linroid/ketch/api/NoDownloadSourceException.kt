package com.linroid.ketch.api

/**
 * The cause of the [KetchError.Unsupported] that [KetchApi.resolve] throws for an input no
 * download source takes, on an instance in this process or a remote one. It tells that apart from
 * the one a source throws when it takes the input but lacks something it needs, such as byte
 * ranges or a usable proxy, whose cause says what, so callers say that no source handles an input
 * only when none does.
 */
class NoDownloadSourceException @InternalKetchApi constructor() :
  Exception("No download source takes this input")

/**
 * Why this error was thrown, in words to show: [noSource] when no download source takes the input
 * (a [NoDownloadSourceException] cause), else "Not supported" and what the source that takes it
 * lacks, as its cause says. A cause that names a link is left out, as links may hold credentials.
 * Its own message is the same whatever the reason. Ketch servers answer refused resolves with
 * it, which `RemoteKetch` reads back.
 */
@InternalKetchApi
fun KetchError.Unsupported.reason(
  noSource: String = "No download source handles this link",
): String {
  if (cause is NoDownloadSourceException) return noSource
  val reason = cause?.message?.trim()
    ?.takeIf { it.isNotEmpty() && "://" !in it && !it.contains("magnet:", ignoreCase = true) }
  return if (reason == null) "Not supported" else "Not supported: $reason"
}
