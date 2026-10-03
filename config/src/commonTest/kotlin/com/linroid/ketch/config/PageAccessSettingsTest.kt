package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PageAccessSettingsTest {

  @Test
  fun normalize_typedSiteOrUrl_leavesTheBareDomain() {
    assertEquals("ubuntu.com", SiteNames.normalize("https://www.Ubuntu.com:443/download?x=1"))
    assertEquals("blender.org", SiteNames.normalize(" *.blender.org. "))
    assertEquals("example.com", SiteNames.normalize("ftp://user:pw@example.com/file"))
    assertEquals("", SiteNames.normalize("https:///"))
  }

  @Test
  fun normalize_wwwBeforeASharedSuffix_staysWhole() {
    // Dropping www. would leave a suffix every site under it shares: com, github.io, co.uk.
    assertEquals("www.com", SiteNames.normalize("www.com"))
    assertEquals("www.github.io", SiteNames.normalize("https://www.GitHub.io/x"))
    assertEquals("www.blogspot.com", SiteNames.normalize("www.blogspot.com"))
    assertEquals("www.co.uk", SiteNames.normalize("www.co.uk"))
    assertEquals("bbc.co.uk", SiteNames.normalize("www.bbc.co.uk"))
    assertEquals("owner.github.io", SiteNames.normalize("www.owner.github.io"))
    assertFalse(SiteNames.covers(SiteNames.normalize("www.github.io"), "attacker.github.io"))
    assertFalse(SiteNames.covers(SiteNames.normalize("www.com"), "github.com"))
  }

  @Test
  fun isPublicSuffix_tldsCountryLevelsAndHostingDomains_butNotSitesOrAddresses() {
    assertTrue(SiteNames.isPublicSuffix("com"))
    assertTrue(SiteNames.isPublicSuffix("com.au"))
    assertTrue(SiteNames.isPublicSuffix("vercel.app"))
    assertFalse(SiteNames.isPublicSuffix("ubuntu.com"))
    assertFalse(SiteNames.isPublicSuffix("co.com"))
    assertFalse(SiteNames.isPublicSuffix("1.2.3.4"))
    assertFalse(SiteNames.isPublicSuffix("[2001:db8::1]"))
  }

  @Test
  fun covers_subdomains_butNotLookalikes() {
    assertTrue(SiteNames.covers("ubuntu.com", "releases.ubuntu.com"))
    assertTrue(SiteNames.covers("ubuntu.com", "UBUNTU.com."))
    assertFalse(SiteNames.covers("ubuntu.com", "notubuntu.com"))
    assertFalse(SiteNames.covers("releases.ubuntu.com", "ubuntu.com"))
    assertFalse(SiteNames.covers("", "ubuntu.com"))
  }

  @Test
  fun trusts_matchesTrustedSitesAndTheirSubdomains() {
    val access = PageAccessSettings(trustedSites = listOf("blender.org"))
    assertTrue(access.trusts("download.blender.org"))
    assertFalse(access.trusts("blender.org.evil.example"))
  }

  @Test
  fun trusts_handWrittenEntries_matchAsNormalized() {
    // config.toml written by hand, as CLI users do, may hold any spelling of a site.
    val access = PageAccessSettings(
      trustedSites = listOf("Blender.org", "www.ubuntu.com", "https://ffmpeg.org/", "  "),
    )
    assertTrue(access.trusts("download.blender.org"))
    assertTrue(access.trusts("releases.ubuntu.com"))
    assertTrue(access.trusts("ffmpeg.org"))
    assertFalse(access.trusts("notblender.org"))
    assertTrue(access.allowsWithoutAsking("download.blender.org"))
  }

  @Test
  fun trusting_siteListedInAnotherSpelling_leavesTheListAsItIs() {
    val access = PageAccessSettings(trustedSites = listOf("Blender.org"))
    assertSame(access, access.trusting("blender.org"))
  }

  @Test
  fun distrusting_entryInAnotherSpelling_removesIt() {
    val access = PageAccessSettings(trustedSites = listOf("Blender.org", "ubuntu.com"))
    assertEquals(listOf("ubuntu.com"), access.distrusting("blender.org").trustedSites)
  }

  @Test
  fun trusting_normalizesAndListsEachSiteOnce() {
    val access = PageAccessSettings()
      .trusting("https://www.Blender.org/download")
      .trusting("blender.org")
    assertEquals(listOf("blender.org"), access.trustedSites)
    assertSame(access, access.trusting("  "))
  }

  @Test
  fun distrusting_removesTheSiteAsTypedOrNormalized() {
    val access = PageAccessSettings(trustedSites = listOf("blender.org", "ubuntu.com"))
    assertEquals(listOf("ubuntu.com"), access.distrusting("www.blender.org").trustedSites)
    assertEquals(listOf("blender.org"), access.distrusting("ubuntu.com").trustedSites)
  }

  @Test
  fun normalize_ipv6Literal_keepsItsBrackets() {
    assertEquals("[2001:db8::1]", SiteNames.normalize("https://[2001:DB8::1]:8443/x"))
    assertTrue(SiteNames.covers("[2001:db8::1]", "[2001:db8::1]"))
    assertTrue(SiteNames.covers("[2001:db8::1]", "2001:db8::1"))
  }

  @Test
  fun allowsWithoutAsking_followsTheModeTrustedSitesAndAllowedSites() {
    val asking = PageAccessSettings(trustedSites = listOf("ubuntu.com"))
    assertTrue(asking.allowsWithoutAsking("releases.ubuntu.com"))
    assertFalse(asking.allowsWithoutAsking("blender.org"))
    assertTrue(asking.allowsWithoutAsking("download.blender.org", setOf("blender.org")))
    assertTrue(asking.copy(mode = PageAccessMode.Allow).allowsWithoutAsking("blender.org"))
  }

  @Test
  fun canonicalUrl_dropsDefaultPortAndFragmentAndLowersSchemeAndHost() {
    assertEquals(
      "https://download.blender.org/release/Blender4.2/b.dmg?x=1",
      SiteNames.canonicalUrl("HTTPS://Download.Blender.org:443/release/Blender4.2/b.dmg?x=1#top"),
    )
    assertEquals("http://a.org:8080/", SiteNames.canonicalUrl("http://A.org:8080/"))
    assertEquals("http://a.org", SiteNames.canonicalUrl("http://a.org:80"))
    assertEquals("magnet:?xt=1", SiteNames.canonicalUrl(" magnet:?xt=1 "))
  }
}
