package com.linroid.ketch.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class AiDiscoverArgsTest {

  @Test
  fun `no arguments and help flags print usage`() {
    assertEquals(AiDiscoverArgs.Help, parseAiDiscoverArgs(emptyList()))
    assertEquals(AiDiscoverArgs.Help, parseAiDiscoverArgs(listOf("--help")))
    assertEquals(AiDiscoverArgs.Help, parseAiDiscoverArgs(listOf("ubuntu", "iso", "-h")))
  }

  @Test
  fun `query words and options are parsed in any order`() {
    val args = listOf("-y", "ffmpeg", "--sites", " ffmpeg.org, ,github.com ", "release")
    val parsed = parseAiDiscoverArgs(args + listOf("--max-results", "3"))
    assertEquals(
      AiDiscoverArgs.Discover(
        query = "ffmpeg release",
        sites = listOf("ffmpeg.org", "github.com"),
        maxResults = 3,
        allowAll = true,
      ),
      parsed,
    )
  }

  @Test
  fun `defaults ask and return five results from any site`() {
    assertEquals(
      AiDiscoverArgs.Discover(query = "latest Ubuntu ISO"),
      parseAiDiscoverArgs(listOf("latest Ubuntu ISO")),
    )
    assertEquals(
      AiDiscoverArgs.Discover(query = "ubuntu", allowAll = true),
      parseAiDiscoverArgs(listOf("ubuntu", "--yes")),
    )
  }

  @Test
  fun `unknown options are rejected rather than searched for`() {
    assertEquals(
      AiDiscoverArgs.Invalid("unknown option '--yess'"),
      parseAiDiscoverArgs(listOf("ubuntu", "--yess")),
    )
  }

  @Test
  fun `option without a value is rejected`() {
    assertEquals(
      AiDiscoverArgs.Invalid("--sites requires a value"),
      parseAiDiscoverArgs(listOf("ubuntu", "--sites")),
    )
  }

  @Test
  fun `invalid option values are rejected`() {
    assertEquals(
      AiDiscoverArgs.Invalid("--sites requires a domain"),
      parseAiDiscoverArgs(listOf("ubuntu", "--sites", " , ")),
    )
    assertEquals(
      AiDiscoverArgs.Invalid("invalid number 'many'"),
      parseAiDiscoverArgs(listOf("ubuntu", "--max-results", "many")),
    )
    assertEquals(
      AiDiscoverArgs.Invalid("--max-results must be > 0"),
      parseAiDiscoverArgs(listOf("ubuntu", "--max-results", "0")),
    )
  }

  @Test
  fun `options without a query are rejected`() {
    assertEquals(
      AiDiscoverArgs.Invalid("missing <query>"),
      parseAiDiscoverArgs(listOf("--sites", "ubuntu.com", "-y")),
    )
  }
}
