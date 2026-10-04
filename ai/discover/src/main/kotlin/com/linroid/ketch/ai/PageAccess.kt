package com.linroid.ketch.ai

/** What the agent wants to do on a website. */
enum class PageAccessKind {
  /** Fetch a page to read its text and links (`fetchPage`). */
  Page,

  /** Check a file's type, size and date with a HEAD request (`headUrl`). */
  FileInfo,
}

/**
 * A request the agent wants to make to a website, which a [PageAccessApprover] allows or
 * declines before anything is sent to the site.
 *
 * @param url the URL to request
 * @param host the host of [url], lowercase
 * @param kind what the request is for
 * @param reason why the agent says it needs it: model text that fetched pages may have shaped,
 *   one line of at most 160 characters with control and bidirectional characters removed;
 *   blank when it gave none
 * @param redirectFrom the host that redirected here, for a redirect to another host; blank for
 *   the request the agent asked for
 */
data class PageAccessRequest(
  val url: String,
  val host: String,
  val kind: PageAccessKind,
  val reason: String = "",
  val redirectFrom: String = "",
)

/**
 * Decides whether discovery may contact a website.
 *
 * Asked before a page fetch or a HEAD request reaches a site, and before a redirect leads to a
 * host the run has not been allowed to contact. A declined host is refused for the rest of the
 * run without asking again. Runs limited to websites ([DiscoverQuery.sites]) never ask: every
 * host they request is one the user named.
 *
 * Called from the agent's threads, one request at a time per run.
 */
fun interface PageAccessApprover {
  /**
   * Whether the agent may make [request]; may suspend until the user answers. Returning `false`
   * declines it.
   */
  suspend fun approve(request: PageAccessRequest): Boolean

  companion object {
    /** Allows every request, ignoring the `[ai.access]` settings. */
    val AllowAll: PageAccessApprover = PageAccessApprover { true }
  }
}
