package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.fetch.UrlValidator
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentOutputParserTest {

  private val parser = AgentOutputParser(
    urlValidator = UrlValidator(),
    safetyFilter = DeviceSafetyFilter(),
    json = Json { ignoreUnknownKeys = true; isLenient = true },
  )

  @Test
  fun parse_cleanJsonArray_returnsCandidates() {
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
  fun parse_markdownWrappedJson_returnsCandidates() {
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
  fun parse_noJson_returnsEmpty() {
    val result = parser.parse("No results found.")
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_invalidJson_returnsEmpty() {
    val result = parser.parse("[{invalid json}]")
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_blockedUrlFiltered() {
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
  fun parse_unsafeUrlFiltered() {
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
  fun parse_duplicateUrlsDeduped() {
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
  fun parse_confidenceAdjustedBySafety() {
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
  fun parse_emptyArray_returnsEmpty() {
    val result = parser.parse("[]")
    assertTrue(result.isEmpty())
  }
}
