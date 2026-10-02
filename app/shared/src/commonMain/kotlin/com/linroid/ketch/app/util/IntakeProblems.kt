package com.linroid.ketch.app.util

import com.linroid.ketch.api.KetchError
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
  val title: String,
  val detail: String? = null,
  val actions: List<IntakeAction> = emptyList(),
  val blocksAdd: Boolean = true,
) {
  /** The title and detail on one line, such as "Not found · the link may have expired". */
  val text: String get() = if (detail == null) title else "$title · $detail"

  companion object {
    /** A magnet whose peers sent no file list within [MAGNET_METADATA_TIMEOUT]. */
    val MagnetTimeout: IntakeProblem = IntakeProblem(
      title = "No peers sent the file list in ${MAGNET_METADATA_TIMEOUT.inWholeMinutes} min",
      actions = listOf(IntakeAction.KeepWaiting, IntakeAction.AddAnyway),
    )
  }
}

/**
 * What a link with an [IntakeProblem] offers.
 *
 * @property label the button text.
 */
enum class IntakeAction(val label: String) {
  /** Shows user name and password fields; apply them with [withCredentials]. */
  SignIn("Sign in"),

  /** Replaces the link with a cURL command copied from the browser, which brings its cookies. */
  PasteCurl("Paste as cURL"),

  /** Opens the request headers under Advanced. */
  AddHeaders("Add headers"),

  /** Searches Discover for another source of the file. */
  FindMirror("Find a working mirror"),

  /** Resolves the link again. */
  Retry("Retry"),

  /** Keeps waiting for a magnet's file list. */
  KeepWaiting("Keep waiting"),

  /** Adds the link without its file list or despite the problem. */
  AddAnyway("Add anyway"),
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
      title = "Ketch can't download this kind of link",
      detail = "it supports http(s), ftp(s), magnet and .torrent",
    )
    is KetchError.AuthenticationFailed -> SIGN_IN_REQUIRED
    is KetchError.Http -> when (code) {
      401 -> SIGN_IN_REQUIRED
      403 -> IntakeProblem(
        title = "The server refused access (403)",
        detail = "links copied from a signed-in page often need its cookies",
        actions = listOf(IntakeAction.PasteCurl, IntakeAction.AddHeaders),
      )
      404, 410 -> IntakeProblem(
        title = "Not found",
        detail = "the link may have expired",
        actions = if (discoverAvailable) listOf(IntakeAction.FindMirror) else emptyList(),
      )
      429, in 500..599 -> IntakeProblem(
        title = "Server busy ($code)",
        detail = "Ketch will retry automatically",
        blocksAdd = false,
      )
      else -> IntakeProblem(
        title = "The server answered $code",
        detail = statusMessage?.takeIf { it.isNotBlank() },
        actions = listOf(IntakeAction.Retry),
      )
    }
    is KetchError.Network -> IntakeProblem(
      title = if (LinkKind.of(url) == LinkKind.Magnet) {
        "Can't reach any peers"
      } else {
        "Can't reach ${hostOf(url) ?: "the server"}"
      },
      actions = listOf(IntakeAction.Retry),
    )
    is KetchError.SourceError -> IntakeProblem(
      title = when (sourceType) {
        "torrent" -> "Couldn't read this torrent"
        "ftp" -> "The FTP server reported an error"
        else -> "Couldn't check this link"
      },
      actions = listOf(IntakeAction.Retry),
    )
    else -> IntakeProblem(
      title = "Couldn't check this link",
      detail = when (this) {
        is KetchError.Unknown -> errorMessage
        is KetchError -> null
        else -> message
      }?.takeIf { it.isNotBlank() },
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
  title = "Sign-in required",
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
