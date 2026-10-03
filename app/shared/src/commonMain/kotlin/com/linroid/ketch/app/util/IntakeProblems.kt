package com.linroid.ketch.app.util

import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_retry
import ketch.app.shared.generated.resources.intake_action_add_anyway
import ketch.app.shared.generated.resources.intake_action_add_headers
import ketch.app.shared.generated.resources.intake_action_find_mirror
import ketch.app.shared.generated.resources.intake_action_keep_waiting
import ketch.app.shared.generated.resources.intake_action_paste_curl
import ketch.app.shared.generated.resources.intake_action_sign_in
import ketch.app.shared.generated.resources.intake_problem_busy
import ketch.app.shared.generated.resources.intake_problem_busy_detail
import ketch.app.shared.generated.resources.intake_problem_check_failed
import ketch.app.shared.generated.resources.intake_problem_forbidden
import ketch.app.shared.generated.resources.intake_problem_forbidden_detail
import ketch.app.shared.generated.resources.intake_problem_ftp
import ketch.app.shared.generated.resources.intake_problem_http
import ketch.app.shared.generated.resources.intake_problem_magnet_timeout
import ketch.app.shared.generated.resources.intake_problem_no_peers
import ketch.app.shared.generated.resources.intake_problem_not_found
import ketch.app.shared.generated.resources.intake_problem_not_found_detail
import ketch.app.shared.generated.resources.intake_problem_sign_in
import ketch.app.shared.generated.resources.intake_problem_torrent
import ketch.app.shared.generated.resources.intake_problem_unreachable
import ketch.app.shared.generated.resources.intake_problem_unreachable_server
import ketch.app.shared.generated.resources.intake_problem_unsupported
import ketch.app.shared.generated.resources.intake_problem_unsupported_detail
import org.jetbrains.compose.resources.StringResource
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How long a magnet may take to send its file list before [IntakeProblem.MagnetTimeout]. */
val MAGNET_METADATA_TIMEOUT: Duration = 120.seconds

/**
 * Why a link in the add sheet cannot be added as it is, and what the user can do about it.
 *
 * @property title what went wrong, shown on the link's row.
 * @property detail more about it, shown after the title; `null` when the title says it all.
 * @property actions what the row offers, most useful first.
 * @property blocksAdd whether the link stays out of the batch until the problem is solved. A
 *   busy server does not block it, because Ketch retries on its own.
 */
data class IntakeProblem(
  val title: UiText,
  val detail: UiText? = null,
  val actions: List<IntakeAction> = emptyList(),
  val blocksAdd: Boolean = true,
) {
  /** The title and detail on one line, such as "Not found · the link may have expired". */
  val text: UiText get() = listOfNotNull(title, detail).joinText()

  companion object {
    /** A magnet whose peers sent no file list within [MAGNET_METADATA_TIMEOUT]. */
    val MagnetTimeout: IntakeProblem = IntakeProblem(
      title = Res.plurals.intake_problem_magnet_timeout.text(
        MAGNET_METADATA_TIMEOUT.inWholeMinutes.toInt(),
      ),
      actions = listOf(IntakeAction.KeepWaiting, IntakeAction.AddAnyway),
    )
  }
}

/**
 * What a link with an [IntakeProblem] offers.
 *
 * @property label the button text.
 */
enum class IntakeAction(val label: StringResource) {
  /** Shows user name and password fields; apply them with [withCredentials]. */
  SignIn(Res.string.intake_action_sign_in),

  /** Replaces the link with a cURL command copied from the browser, which brings its cookies. */
  PasteCurl(Res.string.intake_action_paste_curl),

  /** Opens the request headers under Advanced. */
  AddHeaders(Res.string.intake_action_add_headers),

  /** Searches Discover for another source of the file. */
  FindMirror(Res.string.intake_action_find_mirror),

  /** Resolves the link again. */
  Retry(Res.string.action_retry),

  /** Keeps waiting for a magnet's file list. */
  KeepWaiting(Res.string.intake_action_keep_waiting),

  /** Adds the link without its file list or despite the problem. */
  AddAnyway(Res.string.intake_action_add_anyway),
}

/**
 * The add-sheet problem for this failure to resolve [url]. A missing scheme never gets here:
 * [LinkParser] adds `https://` on its own.
 *
 * @param discoverAvailable whether Discover can look for another source, which a missing file
 *   then offers.
 */
fun Throwable.toIntakeProblem(url: String, discoverAvailable: Boolean = false): IntakeProblem =
  when (this) {
    is KetchError.Unsupported -> IntakeProblem(
      title = Res.string.intake_problem_unsupported.text(),
      detail = Res.string.intake_problem_unsupported_detail.text(),
    )
    is KetchError.AuthenticationFailed -> SIGN_IN_REQUIRED
    is KetchError.Http -> when (code) {
      401 -> SIGN_IN_REQUIRED
      403 -> IntakeProblem(
        title = Res.string.intake_problem_forbidden.text(),
        detail = Res.string.intake_problem_forbidden_detail.text(),
        actions = listOf(IntakeAction.PasteCurl, IntakeAction.AddHeaders),
      )
      404, 410 -> IntakeProblem(
        title = Res.string.intake_problem_not_found.text(),
        detail = Res.string.intake_problem_not_found_detail.text(),
        actions = if (discoverAvailable) listOf(IntakeAction.FindMirror) else emptyList(),
      )
      429, in 500..599 -> IntakeProblem(
        title = Res.string.intake_problem_busy.text(code),
        detail = Res.string.intake_problem_busy_detail.text(),
        blocksAdd = false,
      )
      else -> IntakeProblem(
        title = Res.string.intake_problem_http.text(code),
        detail = statusMessage?.takeIf { it.isNotBlank() }?.let(::verbatim),
        actions = listOf(IntakeAction.Retry),
      )
    }
    is KetchError.Network -> IntakeProblem(
      title = when {
        LinkKind.of(url) == LinkKind.Magnet -> Res.string.intake_problem_no_peers.text()
        else -> hostOf(url)?.let { Res.string.intake_problem_unreachable.text(it) }
          ?: Res.string.intake_problem_unreachable_server.text()
      },
      actions = listOf(IntakeAction.Retry),
    )
    is KetchError.SourceError -> IntakeProblem(
      title = when (sourceType) {
        "torrent" -> Res.string.intake_problem_torrent.text()
        "ftp" -> Res.string.intake_problem_ftp.text()
        else -> Res.string.intake_problem_check_failed.text()
      },
      actions = listOf(IntakeAction.Retry),
    )
    else -> IntakeProblem(
      title = Res.string.intake_problem_check_failed.text(),
      detail = when (this) {
        is KetchError.Unknown -> errorMessage
        is KetchError -> null
        else -> message
      }?.takeIf { it.isNotBlank() }?.let(::verbatim),
      actions = listOf(IntakeAction.Retry),
    )
  }

/**
 * This link signed in as [user] with [password], to retry one that needs a sign-in. FTP and FTPS
 * links carry the credentials in the URL, where the FTP source reads them, replacing any there;
 * other links send them in an `Authorization: Basic` header, replacing any such header.
 */
fun IntakeItem.Link.withCredentials(user: String, password: String): IntakeItem.Link {
  if (kind == LinkKind.Ftp) return copy(url = embedCredentials(url, user, password))
  val token = Base64.encode("$user:$password".encodeToByteArray())
  val others = headers.filterKeys { !it.equals(AUTHORIZATION, ignoreCase = true) }
  return copy(headers = others + (AUTHORIZATION to "Basic $token"))
}

private const val AUTHORIZATION = "Authorization"

private val SIGN_IN_REQUIRED = IntakeProblem(
  title = Res.string.intake_problem_sign_in.text(),
  actions = listOf(IntakeAction.SignIn),
)

/** The host of [url] without user info or port, or `null` when it has none. */
private fun hostOf(url: String): String? {
  val authority = authorityOf(url) ?: return null
  val hostAndPort = url.substring(authority.hostStart, authority.end)
  val host = if (hostAndPort.startsWith('[')) {
    hostAndPort.substringBefore(']') + "]"
  } else {
    hostAndPort.substringBefore(':')
  }
  return host.takeIf { it.isNotEmpty() }
}

/** [url] with [user] and [password] as its user info, replacing any it has. */
private fun embedCredentials(url: String, user: String, password: String): String {
  val authority = authorityOf(url) ?: return url
  val userInfo = percentEncode(user) + ":" + percentEncode(password) + "@"
  return url.substring(0, authority.start) + userInfo + url.substring(authority.hostStart)
}

private fun percentEncode(value: String): String = buildString {
  for (byte in value.encodeToByteArray()) {
    val code = byte.toInt() and 0xFF
    val char = code.toChar()
    val unreserved = char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~"
    if (code < 0x80 && unreserved) {
      append(char)
    } else {
      append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0x0F])
    }
  }
}

private const val HEX_DIGITS = "0123456789ABCDEF"
