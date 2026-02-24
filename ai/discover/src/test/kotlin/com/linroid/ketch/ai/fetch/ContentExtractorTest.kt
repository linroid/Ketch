package com.linroid.ketch.ai.fetch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContentExtractorTest {

  private val extractor = ContentExtractor()

  @Test
  fun extract_removesScriptTags() {
    val html = "<p>Hello</p><script>alert('xss')</script><p>World</p>"
    val text = extractor.extract(html)
    assertTrue("alert" !in text)
    assertTrue("Hello" in text)
    assertTrue("World" in text)
  }

  @Test
  fun extract_removesStyleTags() {
    val html = "<style>body { color: red; }</style><p>Content</p>"
    val text = extractor.extract(html)
    assertTrue("color" !in text)
    assertTrue("Content" in text)
  }

  @Test
  fun extract_removesHtmlComments() {
    val html = "<!-- hidden --><p>Visible</p>"
    val text = extractor.extract(html)
    assertTrue("hidden" !in text)
    assertTrue("Visible" in text)
  }

  @Test
  fun extract_decodesEntities() {
    val html = "<p>Tom &amp; Jerry &lt;3&gt;</p>"
    val text = extractor.extract(html)
    assertTrue("Tom & Jerry <3>" in text)
  }

  @Test
  fun extract_respectsMaxLength() {
    val html = "<p>${"x".repeat(100)}</p>"
    val text = extractor.extract(html, maxLength = 50)
    assertEquals(50, text.length)
  }

  @Test
  fun extract_emptyHtml_returnsEmpty() {
    val text = extractor.extract("")
    assertEquals("", text)
  }

  @Test
  fun extractTitle_findsTitle() {
    val html = "<html><head><title>My Page</title></head></html>"
    assertEquals("My Page", extractor.extractTitle(html))
  }

  @Test
  fun extractTitle_noTitle_returnsEmpty() {
    val html = "<html><head></head><body>No title</body></html>"
    assertEquals("", extractor.extractTitle(html))
  }

  @Test
  fun extractTitle_decodesEntities() {
    val html = "<title>Tom &amp; Jerry</title>"
    assertEquals("Tom & Jerry", extractor.extractTitle(html))
  }
}
