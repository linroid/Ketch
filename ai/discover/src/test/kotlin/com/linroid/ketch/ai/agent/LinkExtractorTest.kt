package com.linroid.ketch.ai.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkExtractorTest {

  private val extractor = LinkExtractor()

  @Test
  fun extract_anchorWithZipHref_found() {
    val html = """<a href="https://example.com/file.zip">Download</a>"""
    val links = extractor.extract(html, "https://example.com/")
    assertEquals(1, links.size)
    assertEquals("https://example.com/file.zip", links[0].url)
  }

  @Test
  fun extract_anchorWithPdfHref_found() {
    val html = """<a href="/docs/manual.pdf">Manual</a>"""
    val links = extractor.extract(html, "https://example.com/")
    assertEquals(1, links.size)
    assertEquals("https://example.com/docs/manual.pdf", links[0].url)
  }

  @Test
  fun extract_anchorWithHtmlHref_notIncluded() {
    val html = """<a href="/about.html">About</a>"""
    val links = extractor.extract(html, "https://example.com/")
    assertTrue(links.isEmpty())
  }

  @Test
  fun extract_relativeUrl_resolvedAgainstBase() {
    val html = """<a href="releases/v1.0.tar.gz">v1.0</a>"""
    val links = extractor.extract(
      html, "https://example.com/project/"
    )
    assertEquals(1, links.size)
    assertEquals(
      "https://example.com/project/releases/v1.0.tar.gz",
      links[0].url,
    )
  }

  @Test
  fun extract_downloadAttribute_found() {
    val html = """<a href="/data" download>Get data</a>"""
    val links = extractor.extract(html, "https://example.com/")
    assertEquals(1, links.size)
    assertEquals("https://example.com/data", links[0].url)
  }

  @Test
  fun extract_emptyHtml_emptyList() {
    val links = extractor.extract("", "https://example.com/")
    assertTrue(links.isEmpty())
  }

  @Test
  fun extract_surroundingTextCaptured() {
    val html = "Released today " +
      """<a href="/file.iso">Download ISO</a>""" +
      " for desktop users"
    val links = extractor.extract(html, "https://example.com/")
    assertEquals(1, links.size)
    assertTrue(links[0].surroundingText.contains("Released today"))
  }
}
