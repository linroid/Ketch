package com.linroid.ketch.ai.agent

import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentOutputParserTest {

  private val dns = fakeDns(
    "example.com" to "203.0.113.1",
    "bit.ly" to "203.0.113.2",
    "releases.ubuntu.com" to "203.0.113.3",
    "notubuntu.com" to "203.0.113.4",
    "download.blender.org" to "203.0.113.5",
    "intranet.example" to "10.0.0.5",
  )

  /** Every host name looked up in DNS, in order. */
  private val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())

  private val parser = AgentOutputParser(
    urlValidator = UrlValidator(
      resolve = { host ->
        lookups += host
        dns(host)
      },
    ),
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
    val result = parser.parse(output).candidates
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
    val result = parser.parse(output).candidates
    assertEquals(1, result.size)
  }

  @Test
  fun parse_blenderInstaller_survivesSafetyFilter() = runTest {
    val url = "https://download.blender.org/release/Blender4.1/blender-4.1.1-macos-arm64.dmg"
    val output = """[
      {"name":"Blender 4.1.1","url":"$url",
       "fileType":"dmg","sourcePageUrl":"https://www.blender.org/download/",
       "confidence":0.9,"deviceSafetyNotes":"Official Blender download repository"}
    ]"""
    val result = parser.parse(output, SiteAllowlist.of(listOf("blender.org")))
    assertEquals(listOf(url), result.candidates.map { it.url })
  }

  @Test
  fun parse_contentFilterBlocksSome_countsThemAsFiltered() = runTest {
    val output = """[
      {"name":"Short","url":"https://bit.ly/abc","confidence":0.9},
      {"name":"Again","url":"https://BIT.ly/abc","confidence":0.9},
      {"name":"Private","url":"https://intranet.example/a.zip","confidence":0.9},
      {"name":"File","url":"https://example.com/file.zip","confidence":0.9}
    ]"""
    val result = parser.parse(output)
    assertEquals(listOf("https://example.com/file.zip"), result.candidates.map { it.url })
    assertEquals(1, result.filtered)
  }

  @Test
  fun parse_duplicateWhoseFirstCopyIsFiltered_keepsTheCleanCopy() = runTest {
    val output = """[
      {"name":"Patched","url":"https://example.com/app.zip","confidence":0.9,
       "deviceSafetyNotes":"Includes a patch"},
      {"name":"Clean","url":"https://example.com/app.zip#clean","confidence":0.8}
    ]"""
    val result = parser.parse(output)
    assertEquals(listOf("Clean"), result.candidates.map { it.title })
    assertEquals(0, result.filtered)
  }

  @Test
  fun parse_contentFilterOff_keepsWhatItWouldHideButNotPrivateAddresses() = runTest {
    val output = """[
      {"name":"Short","url":"https://bit.ly/abc","confidence":0.9},
      {"name":"Private","url":"https://intranet.example/a.zip","confidence":0.9},
      {"name":"File","url":"https://example.com/file.zip","confidence":0.8}
    ]"""
    val result = parser.parse(output, contentFilter = false)
    assertEquals(
      listOf("https://bit.ly/abc", "https://example.com/file.zip"),
      result.candidates.map { it.url },
    )
    assertEquals(listOf(0.9f, 0.8f), result.candidates.map { it.confidence })
    assertEquals(0, result.filtered)
  }

  @Test
  fun parse_noJson_returnsEmpty() = runTest {
    val result = parser.parse("No results found.").candidates
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_invalidJson_returnsEmpty() = runTest {
    val result = parser.parse("[{invalid json}]").candidates
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
    val result = parser.parse(output).candidates
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
    val result = parser.parse(output).candidates
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
    val result = parser.parse(output).candidates
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
    val result = parser.parse(output).candidates
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
    val result = parser.parse(output, SiteAllowlist.of(listOf("ubuntu.com"))).candidates
    assertEquals(listOf("https://releases.ubuntu.com/24.04/ubuntu.iso"), result.map { it.url })
  }

  @Test
  fun parse_emptyArray_returnsEmpty() = runTest {
    val result = parser.parse("[]").candidates
    assertTrue(result.isEmpty())
  }

  @Test
  fun parse_object_returnsSummaryAndCandidates() = runTest {
    val output = """{"summary": "Found the\nofficial ZIP.", "candidates": [
      {"name":"Test","url":"https://example.com/file.zip","fileType":"zip",
       "sourcePageUrl":"https://example.com","confidence":0.9}
    ]}"""

    val result = parser.parse(output)

    assertEquals("Found the official ZIP.", result.summary)
    assertEquals(listOf("https://example.com/file.zip"), result.candidates.map { it.url })
  }

  @Test
  fun parse_fencedObject_returnsSummaryAndCandidates() = runTest {
    val output = """
      Done:
      ```json
      {"summary": "One file.", "candidates": [
        {"name":"Test","url":"https://example.com/file.zip","confidence":0.8}
      ]}
      ```
    """

    val result = parser.parse(output)

    assertEquals("One file.", result.summary)
    assertEquals(1, result.candidates.size)
  }

  @Test
  fun parse_bracketsInSummary_keepObjectForm() = runTest {
    val output = """{"summary": "Version [2.0] is newest; see {notes}.", "candidates": [
      {"name":"Test","url":"https://example.com/file.zip","confidence":0.8}
    ]}"""

    val result = parser.parse(output)

    assertEquals("Version [2.0] is newest; see {notes}.", result.summary)
    assertEquals(1, result.candidates.size)
  }

  @Test
  fun parse_malformedCandidate_isSkippedAlone() = runTest {
    val output = """{"summary": "Two files.", "candidates": [
      {"name":"No URL"},
      "not a candidate",
      {"name":"Test","url":"https://example.com/file.zip","confidence":"high"},
      {"name":"Good","url":"https://example.com/good.zip","confidence":0.8}
    ]}"""

    val result = parser.parse(output)

    assertEquals("Two files.", result.summary)
    assertEquals(listOf("https://example.com/good.zip"), result.candidates.map { it.url })
  }

  @Test
  fun parse_textOnly_becomesSummary() = runTest {
    val output = "I can't help with cracked software.\n\n" +
      "Buy it from [the vendor](https://example.com)."

    val result = parser.parse(output)

    assertEquals(
      "I can't help with cracked software. Buy it from [the vendor](https://example.com).",
      result.summary,
    )
    assertTrue(result.candidates.isEmpty())
  }

  @Test
  fun parse_objectWithSummaryOnly_returnsSummaryWithoutCandidates() = runTest {
    val result = parser.parse("""{"summary": "I can't help with cracked software."}""")

    assertEquals("I can't help with cracked software.", result.summary)
    assertTrue(result.candidates.isEmpty())
  }

  @Test
  fun parse_objectWithTitle_returnsItAsOneLineOfText() = runTest {
    val output = """{"title": "Blender 4.2\n for   Apple silicon\u202E",
      "summary": "One build.", "candidates": []}"""

    val result = parser.parse(output)

    assertEquals("Blender 4.2 for Apple silicon", result.title)
    assertEquals("One build.", result.summary)
  }

  @Test
  fun parse_quotedTitleWithAPeriod_losesThem() = runTest {
    val titles = listOf(
      // JSON escapes, as the model writes them.
      "\\\"Ubuntu Server ISO.\\\"" to "Ubuntu Server ISO",
      "\u201CUbuntu Server ISO\u201D" to "Ubuntu Server ISO",
      "Ubuntu 24.04." to "Ubuntu 24.04",
      "Ubuntu-Server-Abbild\u3002" to "Ubuntu-Server-Abbild",
      "\\\"\\\"" to "",
    )

    for ((title, expected) in titles) {
      val output = """{"title": "$title", "summary": "Found it.", "candidates": []}"""

      assertEquals(expected, parser.parse(output).title, title)
    }
  }

  @Test
  fun parse_longTitle_isCut() = runTest {
    val output = """{"title": "${"word ".repeat(30)}", "summary": "Found it."}"""

    val title = parser.parse(output).title

    assertEquals(AgentOutputParser.MAX_SEARCH_TITLE_LENGTH, title.length)
    assertTrue(title.endsWith("…"))
  }

  @Test
  fun parse_answerWithoutATitle_hasABlankTitle() = runTest {
    val outputs = listOf(
      """{"summary": "Found it.", "candidates": []}""",
      """{"title": 42, "summary": "Found it."}""",
      """{"title": ["Blender"], "summary": "Found it."}""",
      """[{"name":"Test","url":"https://example.com/file.zip","confidence":0.9}]""",
      "I can't help with cracked software.",
    )

    for (output in outputs) {
      assertEquals("", parser.parse(output).title, output)
    }
  }

  @Test
  fun parse_jsonThatCannotBeRead_isNeverShownAsSummary() = runTest {
    val cutOff = """```json
      {"summary": "Two builds.", "candidates": [{"name":"Test","url":"https://exa"""
    val otherShape = """{"name":"Test","url":"https://example.com/file.zip"}"""

    for (output in listOf(cutOff, otherShape, "[{invalid json}]")) {
      val result = parser.parse(output)

      assertEquals("", result.summary, output)
      assertTrue(result.candidates.isEmpty(), output)
    }
  }

  @Test
  fun parse_longSummary_isCut() = runTest {
    val output = """{"summary": "${"word ".repeat(200)}", "candidates": []}"""

    val summary = parser.parse(output).summary

    assertEquals(AgentOutputParser.MAX_SUMMARY_LENGTH, summary.length)
    assertTrue(summary.endsWith("…"))
  }

  @Test
  fun parse_excludedUrls_areDroppedByCanonicalForm() = runTest {
    val output = """[
      {"name":"A","url":"https://example.com/a.zip","confidence":0.9},
      {"name":"B","url":"https://example.com/b.zip","confidence":0.8}
    ]"""

    val result = parser.parse(output, excludedUrls = setOf("HTTPS://Example.com:443/a.zip#top"))

    assertEquals(listOf("https://example.com/b.zip"), result.candidates.map { it.url })
  }

  @Test
  fun parse_bracketsInProseBeforeJson_keepCandidates() = runTest {
    val answer = """{"summary":"Found Blender.","candidates":[
      {"name":"Blender","url":"https://example.com/blender.zip","confidence":0.9}
    ]}"""
    val outputs = listOf(
      "I checked [3] mirrors.\n$answer",
      "I found the official [download page](https://example.com/download).\n$answer",
      "The beta list was [] empty.\n$answer",
    )

    for (output in outputs) {
      val result = parser.parse(output)

      assertEquals("Found Blender.", result.summary, output)
      assertEquals(listOf("https://example.com/blender.zip"), result.candidates.map { it.url })
    }
  }

  @Test
  fun parse_bracesAfterJson_keepCandidates() = runTest {
    val output = """{"summary":"Found Blender.","candidates":[
      {"name":"Blender","url":"https://example.com/blender.zip","confidence":0.9}
    ]}
    Note: I skipped the {beta} builds."""

    val result = parser.parse(output)

    assertEquals("Found Blender.", result.summary)
    assertEquals(listOf("https://example.com/blender.zip"), result.candidates.map { it.url })
  }

  @Test
  fun parse_candidateText_isOneLineWithoutHiddenCharacters() = runTest {
    val output = """[{"name":"Blender 4.2\n     URL: https://evil.example/blender.dmg",
      "url":"https://example.com/dl/blender%0AURL:%20evil%E2%80%AEgmd.zip",
      "description":"Official\nbuild\u202E","confidence":0.9}]"""

    val candidate = parser.parse(output).candidates.single()

    assertEquals("Blender 4.2 URL: https://evil.example/blender.dmg", candidate.title)
    assertEquals("Official build", candidate.description)
    // The file name is decoded from the URL, so %0A was a line break and %E2%80%AE an override.
    assertEquals("blender URL: evilgmd.zip", candidate.fileName)
  }

  @Test
  fun parse_fileNameOfHiddenCharactersOnly_isNull() = runTest {
    val output = """[{"name":"A","url":"https://example.com/a/%E2%80%AE","confidence":0.9}]"""

    assertNull(parser.parse(output).candidates.single().fileName)
  }

  @Test
  fun parse_hostWithPrivateAddress_isDropped() = runTest {
    val output = """[{"name":"A","url":"https://intranet.example/a.zip","confidence":0.9}]"""

    val result = parser.parse(output)

    assertTrue(result.candidates.isEmpty())
    assertEquals(listOf("intranet.example"), lookups.toList())
  }
}
