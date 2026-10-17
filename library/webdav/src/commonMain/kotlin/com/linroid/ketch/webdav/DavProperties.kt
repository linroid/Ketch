package com.linroid.ketch.webdav

/**
 * The properties a `PROPFIND` (RFC 4918) reports for one resource.
 *
 * @property collection whether the resource is a collection (a folder)
 * @property contentLength `getcontentlength`, or `null`
 * @property etag `getetag`, or `null`
 * @property lastModified `getlastmodified`, or `null`
 * @property contentType `getcontenttype`, or `null`
 * @property displayName `displayname`, or `null`
 */
internal data class DavProperties(
  val collection: Boolean,
  val contentLength: Long?,
  val etag: String?,
  val lastModified: String?,
  val contentType: String?,
  val displayName: String?,
) {
  companion object {
    const val DAV = "DAV:"

    /** The body of the `PROPFIND` the source sends: the properties above, at depth 0. */
    val REQUEST: String = """
      |<?xml version="1.0" encoding="utf-8"?>
      |<D:propfind xmlns:D="DAV:"><D:prop>
      |<D:resourcetype/><D:getcontentlength/><D:getetag/><D:getlastmodified/>
      |<D:getcontenttype/><D:displayname/>
      |</D:prop></D:propfind>
    """.trimMargin()

    /**
     * The properties of the first resource of a `207 Multi-Status` [body]: those its `200`
     * property sets report.
     *
     * @throws IllegalArgumentException when [body] is not a multistatus document
     * @throws MissingResourceException when the document reports the resource with a status
     *   other than success, such as `404`, rather than properties
     */
    fun parse(body: String): DavProperties {
      val root = Xml.parse(body)
      require(root.namespace == DAV && root.name == "multistatus") { "Not a multistatus answer" }
      val response = root.child(DAV, "response")
        ?: throw IllegalArgumentException("The multistatus answer names no resource")
      val found = response.children(DAV, "propstat")
        .filter { statusCode(it.child(DAV, "status")?.text) in 200..299 }
        .mapNotNull { it.child(DAV, "prop") }
      if (found.isEmpty()) {
        val status = statusCode(response.child(DAV, "status")?.text)
          ?: response.children(DAV, "propstat")
            .firstNotNullOfOrNull { statusCode(it.child(DAV, "status")?.text) }
        if (status != null && status !in 200..299) throw MissingResourceException(status)
      }
      fun text(name: String): String? = found.firstNotNullOfOrNull { prop ->
        prop.child(DAV, name)?.text?.trim()?.takeIf { it.isNotEmpty() }
      }
      return DavProperties(
        collection = found.any { it.child(DAV, "resourcetype")?.child(DAV, "collection") != null },
        contentLength = text("getcontentlength")?.toLongOrNull()?.takeIf { it >= 0 },
        etag = text("getetag"),
        lastModified = text("getlastmodified"),
        contentType = text("getcontenttype"),
        displayName = text("displayname"),
      )
    }

    /** The code of a status line such as `HTTP/1.1 200 OK`, or `null`. */
    private fun statusCode(line: String?): Int? =
      line?.trim()?.split(' ', '\t')?.getOrNull(1)?.toIntOrNull()
  }
}

/** A multistatus answer that reports its resource with [status] rather than properties. */
internal class MissingResourceException(val status: Int) :
  Exception("The server reported status $status")
