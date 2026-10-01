package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.RowAction
import okio.IOException

/**
 * A failure explained in plain words: what went wrong, why, and the fix most likely to work.
 *
 * @property title the problem, such as "Access denied (403)".
 * @property hint the likely cause and what to do about it; `null` when there is nothing to add.
 * @property primary the action most likely to fix the problem.
 * @property secondary further actions to offer next to [primary].
 * @property details technical text shown under "Technical details", such as the message of an
 *   unexpected error.
 */
data class ErrorCopy(
  val title: String,
  val hint: String?,
  val primary: RowAction,
  val secondary: List<RowAction> = emptyList(),
  val details: String? = null,
) {
  /**
   * First sentence of [hint] starting in lower case, to follow [title] after " · " on a row:
   * "Access denied (403) · the link may have expired". Acronyms, "I" and Title Case phrases
   * such as an HTTP reason ("Bad Request") keep their case.
   */
  val shortHint: String?
    get() {
      val sentence = hint?.substringBefore(". ")?.removeSuffix(".")?.trim()
      if (sentence.isNullOrEmpty()) return null
      val words = sentence.split(' ').filter { it.isNotEmpty() }
      val first = words.first()
      val isAcronym = first.length > 1 && first[1].isUpperCase()
      val isPronoun = first == "I" || first.startsWith("I'")
      val isTitleCase = words.size > 1 && words.all { it.first().isUpperCase() }
      if (isAcronym || isPronoun || isTitleCase) return sentence
      return sentence.replaceFirstChar { it.lowercaseChar() }
    }
}

/**
 * Explains this error of the task downloading [request] on [device].
 *
 * @param retryCount how many times the engine retried before giving up, which is the device's
 *   [com.linroid.ketch.api.DownloadConfig.retryCount] for retryable errors.
 */
fun KetchError.toCopy(request: DownloadRequest, retryCount: Int, device: DeviceInfo): ErrorCopy =
  copyOf(this, request, retryCount, device)

/**
 * Explains a failure that is not tied to a task's download, such as a command that could not
 * reach a device. A [KetchError] anywhere in the causes is explained as such; other I/O failures
 * read as a lost connection.
 */
fun Throwable.toCopy(): ErrorCopy {
  val causes = causeChain()
  val ketchError = causes.firstNotNullOfOrNull { it as? KetchError }
  return when {
    ketchError != null -> copyOf(ketchError, request = null, retryCount = 0, device = null)
    causes.any { it is IOException } -> ErrorCopy(
      title = "Connection lost",
      hint = "Check the connection and try again.",
      primary = RowAction.Retry,
      details = describeCauses(),
    )
    this is UnsupportedOperationException -> ErrorCopy(
      title = "Not supported on this device",
      hint = message,
      primary = RowAction.CopyDetails,
    )
    else -> ErrorCopy(
      title = "Something went wrong",
      hint = null,
      primary = RowAction.Retry,
      secondary = listOf(RowAction.CopyDetails),
      details = describeCauses(),
    )
  }
}

/**
 * Text that "Copy details" puts on the clipboard for a bug report: the error with its causes,
 * the HTTP status, the URL with credentials masked, the task id and the Ketch version.
 */
fun errorDetails(
  error: Throwable,
  request: DownloadRequest? = null,
  taskId: String? = null,
): String = buildString {
  appendLine("Error: ${error.describeCauses()}")
  if (error is KetchError.Http) appendLine("HTTP status: ${error.code}")
  if (request != null) appendLine("URL: ${redactUrl(request.url)}")
  if (taskId != null) appendLine("Task: $taskId")
  append("Ketch ${KetchApi.VERSION} (${KetchApi.REVISION})")
}

/** The `Referer` header of [this] request: the page the link was found on, if known. */
val DownloadRequest.referer: String?
  get() = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
    ?.value?.trim()?.ifEmpty { null }

private fun copyOf(
  error: KetchError,
  request: DownloadRequest?,
  retryCount: Int,
  device: DeviceInfo?,
): ErrorCopy {
  val host = request?.url?.let(::urlHost)
  return when (error) {
    is KetchError.Network -> ErrorCopy(
      title = "Connection lost",
      hint = listOfNotNull("Couldn't reach ${host ?: "the server"}.", retried(retryCount))
        .joinToString(" "),
      primary = RowAction.Retry,
      secondary = listOf(RowAction.RetryWithConnections(2)),
    )
    is KetchError.Http -> httpCopy(error, request, host, device)
    is KetchError.Disk -> {
      val canShowFolder = device?.capabilities?.canOpenFiles == true
      ErrorCopy(
        title = "Couldn't write the file",
        hint = diskHint(device),
        primary = if (canShowFolder) RowAction.ShowInFolder else RowAction.Retry,
        secondary = if (canShowFolder) listOf(RowAction.Retry) else emptyList(),
      )
    }
    is KetchError.Unsupported -> ErrorCopy(
      title = "This server can't do this download",
      hint = "It doesn't report a size or support ranges.",
      primary = RowAction.DownloadAgain,
      secondary = listOf(RowAction.CopyLink),
    )
    is KetchError.FileChanged -> ErrorCopy(
      title = "File changed on the server",
      hint = "Resuming would corrupt it (${error.reason}).",
      primary = RowAction.DownloadAgain,
    )
    is KetchError.CorruptResumeState -> ErrorCopy(
      title = "Saved progress is damaged",
      hint = "The downloaded part can't be reused.",
      primary = RowAction.DownloadAgain,
    )
    is KetchError.Canceled -> ErrorCopy(
      title = "Canceled",
      hint = null,
      primary = RowAction.DownloadAgain,
    )
    is KetchError.SourceError -> sourceCopy(error)
    is KetchError.AuthenticationFailed -> ErrorCopy(
      title = "Wrong user name or password",
      hint = "The ${sourceLabel(error.sourceType)} server rejected the sign-in.",
      primary = RowAction.EnterCredentials,
    )
    is KetchError.Unknown -> ErrorCopy(
      title = "Something went wrong",
      hint = null,
      primary = RowAction.Retry,
      secondary = listOf(RowAction.CopyDetails),
      details = error.errorMessage ?: error.cause?.describeCauses(),
    )
  }
}

private fun httpCopy(
  error: KetchError.Http,
  request: DownloadRequest?,
  host: String?,
  device: DeviceInfo?,
): ErrorCopy {
  val code = error.code
  return when {
    code == 401 || code == 407 -> ErrorCopy(
      title = "Sign-in required ($code)",
      hint = "${host ?: "The server"} wants you signed in. Send it from the Ketch browser " +
        "extension so your cookies come along.",
      primary = RowAction.EditLink,
      secondary = listOf(RowAction.CopyLink),
    )
    code == 403 -> ErrorCopy(
      title = "Access denied (403)",
      hint = forbiddenHint(request),
      primary = RowAction.EditLink,
      secondary = listOfNotNull(RowAction.OpenSourcePage.takeIf { request?.referer != null }),
    )
    code == 404 || code == 410 -> {
      val canDiscover = device?.capabilities?.canDiscover == true
      ErrorCopy(
        title = "File not found ($code)",
        hint = "The server no longer has this file.",
        primary = if (canDiscover) RowAction.FindAnotherSource else RowAction.CopyLink,
        secondary = if (canDiscover) listOf(RowAction.CopyLink) else emptyList(),
      )
    }
    code == 416 -> ErrorCopy(
      title = "Server refused to resume",
      hint = "The saved position is no longer valid.",
      primary = RowAction.DownloadAgain,
    )
    code == 429 -> ErrorCopy(
      title = "Server is rate-limiting",
      hint = "Try again ${error.retryAfterSeconds?.let { "in $it s" } ?: "later"} with fewer " +
        "connections.",
      primary = RowAction.RetryWithConnections(1),
    )
    code in 500..599 -> ErrorCopy(
      title = "Server error ($code)",
      hint = "Usually temporary.",
      primary = RowAction.Retry,
    )
    else -> ErrorCopy(
      title = "Server answered $code",
      hint = error.statusMessage?.trim()?.ifEmpty { null },
      primary = RowAction.Retry,
      secondary = listOf(RowAction.CopyDetails),
    )
  }
}

private fun sourceCopy(error: KetchError.SourceError): ErrorCopy {
  // The first cause that has a message, without its class name: "FTP 550: No such file".
  val cause = error.cause?.describeCauses()?.split(" <- ")
    ?.firstNotNullOfOrNull { it.substringAfter(": ", "").ifEmpty { null } }
  return when (error.sourceType.lowercase()) {
    "torrent" -> ErrorCopy(
      title = "The torrent stopped",
      hint = "No reachable peers or trackers.",
      primary = RowAction.Retry,
    )
    else -> ErrorCopy(
      title = "The ${sourceLabel(error.sourceType)} server reported an error",
      hint = cause,
      primary = RowAction.Retry,
    )
  }
}

private fun forbiddenHint(request: DownloadRequest?): String {
  val browser = request?.let(::browserName) ?: return "The link may have expired."
  return "The link may have expired. Captured from $browser? Capture it again from the page."
}

/** The browser a request was captured from, named from its `User-Agent` when possible. */
private fun browserName(request: DownloadRequest): String? {
  val userAgent = request.headers.entries
    .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
  val named = userAgent?.let { agent ->
    when {
      "Firefox/" in agent -> "Firefox"
      "Edg/" in agent -> "Edge"
      "OPR/" in agent -> "Opera"
      "Chrome/" in agent -> "Chrome"
      "Safari/" in agent -> "Safari"
      else -> null
    }
  }
  val fromBrowser = request.properties[ORIGIN_PROPERTY] == "browser"
  return named ?: if (fromBrowser) "your browser" else null
}

private fun diskHint(device: DeviceInfo?): String {
  if (device == null) return "Check free space and folder permissions."
  val space = device.usableSpace?.takeIf { it >= 0 }
    ?: return "Check free space and folder permissions on ${device.name}."
  return "${formatBytes(space)} free on ${device.name}. Check space and folder permissions."
}

private fun retried(retryCount: Int): String? = when {
  retryCount <= 0 -> null
  retryCount == 1 -> "Ketch retried once."
  else -> "Ketch retried $retryCount times."
}

private fun sourceLabel(sourceType: String): String = when (sourceType.lowercase()) {
  "ftp", "ftps", "http", "https" -> sourceType.uppercase()
  else -> sourceType
}

private fun Throwable.causeChain(): List<Throwable> {
  val chain = mutableListOf<Throwable>()
  var current: Throwable? = this
  while (current != null && chain.none { it === current }) {
    chain += current
    current = current.cause
  }
  return chain
}

private const val ORIGIN_PROPERTY = "ketch.origin"
