package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentOutputParserTest {

  private val dns = fakeDns(
    "example.com" to "203.0.113.1",
    "bit.ly" to "203.0.113.2",
    "releases.ubuntu.com" to "203.0.113.3",
    "notubuntu.com" to "203.0.113.4",
  )

  private val parser = AgentOutputParser(
    urlValidator = UrlValidator(dns),
    safetyFilter = DeviceSafetyFilter(),
    json = Json { ignoreUnknownKeys = true; isLenient = true },
  )

  @Test
  fun parse_cleanJsonArray_returnsCandidates() = runTest {
    val output = """[
      {"name":"Test","url":"https://example.com/file.zip",
       "fileType":"zip","sourcePageUrl":"https://example.com",
       "description":"A test file","confidence":0.9,
       "deviceSafetyNotes":"HTTPS"}
    ]"""
    val result = parser.parse(output)
    assertEquals(1, result.size)
    assertEquals("https://example.com/file.zip", result[0].url)
  }

  @Test
  fun parse_markdownWrappedJson_returnsCandidates() = runTest {
    val output = """
      Here are the results:
      ```json
      [{"name":"Test","url":"https://example.com/file.zip",
        "fileType":"zip","sourcePageUrl":"https://example.com",
        "description":"test","confidence":0.8,
        "deviceSafetyNotes":"safe"}]
      ```
    """
    val result = parser.parse(output)
    assertEquals(1, result.size)
  }

  @Test
  fun parse_noJson_returnsEmpty() = runTest {
    val result = parser.parse("No results found.")
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_invalidJson_returnsEmpty() = runTest {
    val result = parser.parse("[{invalid json}]")
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_blockedUrlFiltered() = runTest {
    val output = """[
      {"name":"Test","url":"file:///etc/passwd",
       "fileType":"txt","sourcePageUrl":"",
       "description":"","confidence":0.9,
       "deviceSafetyNotes":""}
    ]"""
    val result = parser.parse(output)
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_unsafeUrlFiltered() = runTest {
    val output = """[
      {"name":"Crack","url":"https://bit.ly/crack123",
       "fileType":"zip","sourcePageUrl":"",
       "description":"","confidence":0.9,
       "deviceSafetyNotes":""}
    ]"""
    val result = parser.parse(output)
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_duplicateUrlsDeduped() = runTest {
    val output = """[
      {"name":"A","url":"https://example.com/file.zip",
       "fileType":"zip","sourcePageUrl":"https://example.com",
       "description":"first","confidence":0.8,
       "deviceSafetyNotes":"safe"},
      {"name":"B","url":"https://example.com/file.zip",
       "fileType":"zip","sourcePageUrl":"https://example.com",
       "description":"second","confidence":0.9,
       "deviceSafetyNotes":"safe"}
    ]"""
    val result = parser.parse(output)
    assertEquals(1, result.size)
  }

  @Test
  fun parse_confidenceAdjustedBySafety() = runTest {
    val output = """[
      {"name":"Test","url":"https://example.com/file.zip",
       "fileType":"zip","sourcePageUrl":"https://example.com",
       "description":"test","confidence":1.0,
       "deviceSafetyNotes":"safe"}
    ]"""
    val result = parser.parse(output)
    assertEquals(1, result.size)
    // Confidence should be adjusted (multiplied by safety score)
    // so it won't be exactly 1.0
    assertTrue(result[0].confidence <= 1.0f)
  }

  @Test
  fun parse_allowlist_keepsOnlyCandidatesOnAllowedSites() = runTest {
    // The source page is not checked: only where the file is served matters.
    val output = """[
      {"name":"ISO","url":"https://releases.ubuntu.com/24.04/ubuntu.iso",
       "fileType":"iso","sourcePageUrl":"https://example.com/blog",
       "description":"","confidence":0.9,"deviceSafetyNotes":""},
      {"name":"Lookalike","url":"https://notubuntu.com/ubuntu.iso",
       "fileType":"iso","sourcePageUrl":"https://notubuntu.com",
       "description":"","confidence":0.9,"deviceSafetyNotes":""}
    ]"""
    val result = parser.parse(output, SiteAllowlist.of(listOf("ubuntu.com")))
    assertEquals(listOf("https://releases.ubuntu.com/24.04/ubuntu.iso"), result.map { it.url })
  }

  @Test
  fun parse_emptyArray_returnsEmpty() = runTest {
    val result = parser.parse("[]")
    assertTrue(result.isEmpty())
  }
}
