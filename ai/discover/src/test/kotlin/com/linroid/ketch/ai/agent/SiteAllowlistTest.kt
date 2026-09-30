package com.linroid.ketch.ai.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SiteAllowlistTest {

  @Test
  fun of_urlLikeEntries_normalizesToDomains() {
    val sites = listOf("https://www.Ubuntu.com/download", "*.blender.org", "example.org:8080")
    val allowlist = SiteAllowlist.of(sites + listOf(" ", "EXAMPLE.org."))
    assertEquals(listOf("ubuntu.com", "blender.org", "example.org"), allowlist.domains)
  }

  @Test
  fun of_onlyUnusableEntries_throws() {
    assertFailsWith<IllegalArgumentException> {
      SiteAllowlist.of(listOf("https://", "/"))
    }
  }

  @Test
  fun allows_domainAndSubdomains_areAllowed() {
    val allowlist = SiteAllowlist.of(listOf("ubuntu.com"))
    assertTrue(allowlist.allows("https://ubuntu.com/download"))
    assertTrue(allowlist.allows("https://releases.ubuntu.com/24.04/ubuntu.iso"))
    assertTrue(allowlist.allows("https://UBUNTU.COM./"))
  }

  @Test
  fun allows_lookalikeHosts_areRejected() {
    val allowlist = SiteAllowlist.of(listOf("ubuntu.com"))
    assertFalse(allowlist.allows("https://notubuntu.com/"))
    assertFalse(allowlist.allows("https://ubuntu.com.evil.example/"))
    assertFalse(allowlist.allows("https://ubuntu.com@evil.example/"))
    assertFalse(allowlist.allows("https://evil.example/?next=https://ubuntu.com"))
  }

  @Test
  fun allows_unparsableUrlWhileRestricted_isRejected() {
    assertFalse(SiteAllowlist.of(listOf("ubuntu.com")).allows("not a url"))
  }

  @Test
  fun allows_noSites_allowsAnyHost() {
    assertTrue(SiteAllowlist.of(emptyList()).allows("https://anything.example/file.zip"))
  }

  @Test
  fun forRun_singleList_appliesAsIs() {
    val ubuntu = listOf("ubuntu.com")
    val configuredOnly = SiteAllowlist.forRun(configured = ubuntu, requested = emptyList())
    val requestedOnly = SiteAllowlist.forRun(configured = emptyList(), requested = ubuntu)
    assertEquals(listOf("ubuntu.com"), configuredOnly.domains)
    assertEquals(listOf("ubuntu.com"), requestedOnly.domains)
  }

  @Test
  fun forRun_requestedSitesWithinConfigured_narrowsToThem() {
    val allowlist = SiteAllowlist.forRun(
      configured = listOf("ubuntu.com"),
      requested = listOf("releases.ubuntu.com", "evil.example"),
    )
    assertEquals(listOf("releases.ubuntu.com"), allowlist.domains)
  }

  @Test
  fun forRun_configuredNarrowerThanRequested_keepsConfigured() {
    val allowlist = SiteAllowlist.forRun(
      configured = listOf("releases.ubuntu.com"),
      requested = listOf("ubuntu.com"),
    )
    assertEquals(listOf("releases.ubuntu.com"), allowlist.domains)
  }

  @Test
  fun forRun_disjointLists_throws() {
    assertFailsWith<IllegalArgumentException> {
      SiteAllowlist.forRun(configured = listOf("ubuntu.com"), requested = listOf("evil.example"))
    }
  }
}
