package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.remote.RemoteApiException
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.error_browser_yours
import ketch.app.shared.generated.resources.error_canceled
import ketch.app.shared.generated.resources.error_check_connection
import ketch.app.shared.generated.resources.error_check_connection_short
import ketch.app.shared.generated.resources.error_connection_lost
import ketch.app.shared.generated.resources.error_corrupt_resume
import ketch.app.shared.generated.resources.error_corrupt_resume_hint
import ketch.app.shared.generated.resources.error_corrupt_resume_hint_short
import ketch.app.shared.generated.resources.error_disk
import ketch.app.shared.generated.resources.error_disk_hint
import ketch.app.shared.generated.resources.error_disk_hint_free
import ketch.app.shared.generated.resources.error_disk_hint_free_short
import ketch.app.shared.generated.resources.error_disk_hint_on
import ketch.app.shared.generated.resources.error_disk_hint_on_short
import ketch.app.shared.generated.resources.error_disk_hint_short
import ketch.app.shared.generated.resources.error_file_changed
import ketch.app.shared.generated.resources.error_file_changed_hint
import ketch.app.shared.generated.resources.error_file_changed_hint_short
import ketch.app.shared.generated.resources.error_http_forbidden
import ketch.app.shared.generated.resources.error_http_forbidden_hint
import ketch.app.shared.generated.resources.error_http_forbidden_hint_browser
import ketch.app.shared.generated.resources.error_http_forbidden_hint_short
import ketch.app.shared.generated.resources.error_http_not_found
import ketch.app.shared.generated.resources.error_http_not_found_hint
import ketch.app.shared.generated.resources.error_http_not_found_hint_short
import ketch.app.shared.generated.resources.error_http_other
import ketch.app.shared.generated.resources.error_http_range
import ketch.app.shared.generated.resources.error_http_range_hint
import ketch.app.shared.generated.resources.error_http_range_hint_short
import ketch.app.shared.generated.resources.error_http_rate_limited
import ketch.app.shared.generated.resources.error_http_rate_limited_hint
import ketch.app.shared.generated.resources.error_http_rate_limited_hint_in
import ketch.app.shared.generated.resources.error_http_rate_limited_hint_in_short
import ketch.app.shared.generated.resources.error_http_rate_limited_hint_short
import ketch.app.shared.generated.resources.error_http_server
import ketch.app.shared.generated.resources.error_http_server_hint
import ketch.app.shared.generated.resources.error_http_server_hint_short
import ketch.app.shared.generated.resources.error_http_sign_in
import ketch.app.shared.generated.resources.error_http_sign_in_hint
import ketch.app.shared.generated.resources.error_http_sign_in_hint_server
import ketch.app.shared.generated.resources.error_http_sign_in_hint_server_short
import ketch.app.shared.generated.resources.error_http_sign_in_hint_short
import ketch.app.shared.generated.resources.error_network_hint
import ketch.app.shared.generated.resources.error_network_hint_server
import ketch.app.shared.generated.resources.error_network_hint_server_short
import ketch.app.shared.generated.resources.error_network_hint_short
import ketch.app.shared.generated.resources.error_not_supported
import ketch.app.shared.generated.resources.error_path_rejected
import ketch.app.shared.generated.resources.error_path_rejected_hint
import ketch.app.shared.generated.resources.error_retried
import ketch.app.shared.generated.resources.error_retried_once
import ketch.app.shared.generated.resources.error_something_went_wrong
import ketch.app.shared.generated.resources.error_source
import ketch.app.shared.generated.resources.error_torrent_stopped
import ketch.app.shared.generated.resources.error_torrent_stopped_hint
import ketch.app.shared.generated.resources.error_torrent_stopped_hint_short
import ketch.app.shared.generated.resources.error_unsupported
import ketch.app.shared.generated.resources.error_unsupported_hint
import ketch.app.shared.generated.resources.error_unsupported_hint_short
import ketch.app.shared.generated.resources.error_wrong_credentials
import ketch.app.shared.generated.resources.error_wrong_credentials_hint
import ketch.app.shared.generated.resources.error_wrong_credentials_hint_short
import okio.IOException

/**
 * A failure explained in plain words: what went wrong, why, and the fix most likely to work.
 *
 * @property title the problem, such as "Access denied (403)".
 * @property hint the likely cause and what to do about it; `null` when there is nothing to add.
 * @property shortHint the first sentence of [hint] as it follows [title] after " · " on a row,
 *   starting in lower case in English: "Access denied (403) · the link may have expired".
 * @property primary the action most likely to fix the problem.
 * @property secondary further actions to offer next to [primary].
 * @property details technical text shown under "Technical details", such as the message of an
 *   unexpected error; never translated.
 */
data class ErrorCopy(
  val title: UiText,
  val hint: UiText? = null,
  val shortHint: UiText? = null,
  val primary: RowAction,
  val secondary: List<RowAction> = emptyList(),
  val details: String? = null,
)

/** A hint written by someone else, such as a server's reason phrase, and its short form. */
private fun foreignHint(
  title: UiText,
  hint: String?,
  primary: RowAction,
  secondary: List<RowAction> = emptyList(),
  details: String? = null,
): ErrorCopy = ErrorCopy(
  title = title,
  hint = hint?.let(::verbatim),
  shortHint = hint?.let(::shortForm)?.let(::verbatim),
  primary = primary,
  secondary = secondary,
  details = details,
)

/**
 * The first sentence of [hint] starting in lower case, to follow a title on a row. Acronyms, "I"
 * and Title Case phrases such as an HTTP reason ("Bad Request") keep their case.
 */
internal fun shortForm(hint: String): String? {
  val sentence = hint.substringBefore(". ").removeSuffix(".").trim()
  if (sentence.isEmpty()) return null
  val words = sentence.split(' ').filter { it.isNotEmpty() }
  val first = words.first()
  val isAcronym = first.length > 1 && first[1].isUpperCase()
  val isPronoun = first == "I" || first.startsWith("I'")
  val isTitleCase = words.size > 1 && words.all { it.first().isUpperCase() }
  if (isAcronym || isPronoun || isTitleCase) return sentence
  return sentence.replaceFirstChar { it.lowercaseChar() }
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
 * reach a device. A [KetchError] anywhere in the causes is explained as such, as is a device
 * refusing a folder (`path_rejected`); other I/O failures read as a lost connection.
 */
fun Throwable.toCopy(): ErrorCopy {
  val causes = causeChain()
  val ketchError = causes.firstNotNullOfOrNull { it as? KetchError }
  val refusal = causes.firstNotNullOfOrNull { it as? RemoteApiException }
  return when {
    ketchError != null -> copyOf(ketchError, request = null, retryCount = 0, device = null)
    // The server's message says "this device" of itself, which here reads as the user's own.
    refusal?.errorCode == "path_rejected" -> ErrorCopy(
      title = Res.string.error_path_rejected.text(),
      hint = Res.string.error_path_rejected_hint.text(),
      primary = RowAction.CopyDetails,
      details = refusal.message,
    )
    causes.any { it is IOException } -> ErrorCopy(
      title = Res.string.error_connection_lost.text(),
      hint = Res.string.error_check_connection.text(),
      shortHint = Res.string.error_check_connection_short.text(),
      primary = RowAction.Retry,
      details = describeCauses(),
    )
    this is UnsupportedOperationException -> foreignHint(
      title = Res.string.error_not_supported.text(),
      hint = message,
      primary = RowAction.CopyDetails,
    )
    else -> ErrorCopy(
      title = Res.string.error_something_went_wrong.text(),
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
    is KetchError.Network -> {
      val reach = if (host != null) {
        Res.string.error_network_hint.text(host)
      } else {
        Res.string.error_network_hint_server.text()
      }
      ErrorCopy(
        title = Res.string.error_connection_lost.text(),
        hint = listOfNotNull(reach, retried(retryCount)).joinText(" "),
        shortHint = if (host != null) {
          Res.string.error_network_hint_short.text(host)
        } else {
          Res.string.error_network_hint_server_short.text()
        },
        primary = RowAction.Retry,
        secondary = listOf(RowAction.RetryWithConnections(2)),
      )
    }
    is KetchError.Http -> httpCopy(error, request, host, device)
    is KetchError.Disk -> {
      val canShowFolder = device?.capabilities?.canOpenFiles == true
      val (hint, shortHint) = diskHint(device)
      ErrorCopy(
        title = Res.string.error_disk.text(),
        hint = hint,
        shortHint = shortHint,
        primary = if (canShowFolder) RowAction.ShowInFolder else RowAction.Retry,
        secondary = if (canShowFolder) listOf(RowAction.Retry) else emptyList(),
      )
    }
    is KetchError.Unsupported -> ErrorCopy(
      title = Res.string.error_unsupported.text(),
      hint = Res.string.error_unsupported_hint.text(),
      shortHint = Res.string.error_unsupported_hint_short.text(),
      primary = RowAction.DownloadAgain,
      secondary = listOf(RowAction.CopyLink),
    )
    is KetchError.FileChanged -> ErrorCopy(
      title = Res.string.error_file_changed.text(),
      hint = Res.string.error_file_changed_hint.text(error.reason),
      shortHint = Res.string.error_file_changed_hint_short.text(error.reason),
      primary = RowAction.DownloadAgain,
    )
    is KetchError.CorruptResumeState -> ErrorCopy(
      title = Res.string.error_corrupt_resume.text(),
      hint = Res.string.error_corrupt_resume_hint.text(),
      shortHint = Res.string.error_corrupt_resume_hint_short.text(),
      primary = RowAction.DownloadAgain,
    )
    is KetchError.Canceled -> ErrorCopy(
      title = Res.string.error_canceled.text(),
      primary = RowAction.DownloadAgain,
    )
    is KetchError.SourceError -> sourceCopy(error)
    is KetchError.AuthenticationFailed -> ErrorCopy(
      title = Res.string.error_wrong_credentials.text(),
      hint = Res.string.error_wrong_credentials_hint.text(sourceLabel(error.sourceType)),
      shortHint = Res.string.error_wrong_credentials_hint_short.text(sourceLabel(error.sourceType)),
      primary = RowAction.EnterCredentials,
    )
    is KetchError.Unknown -> ErrorCopy(
      title = Res.string.error_something_went_wrong.text(),
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
      title = Res.string.error_http_sign_in.text(code),
      hint = if (host != null) {
        Res.string.error_http_sign_in_hint.text(host)
      } else {
        Res.string.error_http_sign_in_hint_server.text()
      },
      shortHint = if (host != null) {
        Res.string.error_http_sign_in_hint_short.text(host)
      } else {
        Res.string.error_http_sign_in_hint_server_short.text()
      },
      primary = RowAction.EditLink,
      secondary = listOf(RowAction.CopyLink),
    )
    code == 403 -> ErrorCopy(
      title = Res.string.error_http_forbidden.text(),
      hint = forbiddenHint(request),
      shortHint = Res.string.error_http_forbidden_hint_short.text(),
      primary = RowAction.EditLink,
      secondary = listOfNotNull(RowAction.OpenSourcePage.takeIf { request?.referer != null }),
    )
    code == 404 || code == 410 -> {
      val canDiscover = device?.capabilities?.canDiscover == true
      ErrorCopy(
        title = Res.string.error_http_not_found.text(code),
        hint = Res.string.error_http_not_found_hint.text(),
        shortHint = Res.string.error_http_not_found_hint_short.text(),
        primary = if (canDiscover) RowAction.FindAnotherSource else RowAction.CopyLink,
        secondary = if (canDiscover) listOf(RowAction.CopyLink) else emptyList(),
      )
    }
    code == 416 -> ErrorCopy(
      title = Res.string.error_http_range.text(),
      hint = Res.string.error_http_range_hint.text(),
      shortHint = Res.string.error_http_range_hint_short.text(),
      primary = RowAction.DownloadAgain,
    )
    code == 429 -> {
      val after = error.retryAfterSeconds
      ErrorCopy(
        title = Res.string.error_http_rate_limited.text(),
        hint = if (after != null) {
          Res.string.error_http_rate_limited_hint_in.text(after)
        } else {
          Res.string.error_http_rate_limited_hint.text()
        },
        shortHint = if (after != null) {
          Res.string.error_http_rate_limited_hint_in_short.text(after)
        } else {
          Res.string.error_http_rate_limited_hint_short.text()
        },
        primary = RowAction.RetryWithConnections(1),
      )
    }
    code in 500..599 -> ErrorCopy(
      title = Res.string.error_http_server.text(code),
      hint = Res.string.error_http_server_hint.text(),
      shortHint = Res.string.error_http_server_hint_short.text(),
      primary = RowAction.Retry,
    )
    else -> foreignHint(
      title = Res.string.error_http_other.text(code),
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
      title = Res.string.error_torrent_stopped.text(),
      hint = Res.string.error_torrent_stopped_hint.text(),
      shortHint = Res.string.error_torrent_stopped_hint_short.text(),
      primary = RowAction.Retry,
    )
    else -> foreignHint(
      title = Res.string.error_source.text(sourceLabel(error.sourceType)),
      hint = cause,
      primary = RowAction.Retry,
    )
  }
}

private fun forbiddenHint(request: DownloadRequest?): UiText {
  val browser = request?.let(::browserName) ?: return Res.string.error_http_forbidden_hint.text()
  return Res.string.error_http_forbidden_hint_browser.text(browser)
}

/** The browser a request was captured from, named from its `User-Agent` when possible. */
private fun browserName(request: DownloadRequest): UiText? {
  val userAgent = request.headers.entries
    .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
  val named = userAgent?.let { agent ->
    when {
      "Firefox/" in agent -> verbatim("Firefox")
      "Edg/" in agent -> verbatim("Edge")
      "OPR/" in agent -> verbatim("Opera")
      "Chrome/" in agent -> verbatim("Chrome")
      "Safari/" in agent -> verbatim("Safari")
      else -> null
    }
  }
  val fromBrowser = TaskOrigin.of(request) == TaskOrigin.Browser
  return named ?: if (fromBrowser) Res.string.error_browser_yours.text() else null
}

/** The hint of a disk failure on [device] and its short form. */
private fun diskHint(device: DeviceInfo?): Pair<UiText, UiText> {
  if (device == null) {
    return Res.string.error_disk_hint.text() to Res.string.error_disk_hint_short.text()
  }
  val space = device.usableSpace?.takeIf { it >= 0 }
    ?: return Res.string.error_disk_hint_on.text(device.name) to
      Res.string.error_disk_hint_on_short.text(device.name)
  val free = sizeText(space)
  return Res.string.error_disk_hint_free.text(free, device.name) to
    Res.string.error_disk_hint_free_short.text(free, device.name)
}

private fun retried(retryCount: Int): UiText? = when {
  retryCount <= 0 -> null
  retryCount == 1 -> Res.string.error_retried_once.text()
  else -> Res.plurals.error_retried.text(retryCount)
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
