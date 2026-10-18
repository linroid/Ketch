package com.linroid.ketch.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DavPropertiesTest {
  @Test
  fun parse_nextcloudStyleAnswer() {
    val properties = DavProperties.parse(
      """
      <?xml version="1.0"?>
      <d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns"
          xmlns:oc="http://owncloud.org/ns">
        <d:response>
          <d:href>/remote.php/dav/files/alice/Movie%20Night.mkv</d:href>
          <d:propstat>
            <d:prop>
              <d:resourcetype/>
              <d:getcontentlength>734003200</d:getcontentlength>
              <d:getetag>&quot;5f1c0d9e8a7b6&quot;</d:getetag>
              <d:getlastmodified>Tue, 13 Oct 2026 09:12:44 GMT</d:getlastmodified>
              <d:getcontenttype>video/x-matroska</d:getcontenttype>
            </d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
          <d:propstat>
            <d:prop><d:displayname/></d:prop>
            <d:status>HTTP/1.1 404 Not Found</d:status>
          </d:propstat>
        </d:response>
      </d:multistatus>
      """.trimIndent(),
    )
    assertFalse(properties.collection)
    assertEquals(734003200L, properties.contentLength)
    assertEquals("\"5f1c0d9e8a7b6\"", properties.etag)
    assertEquals("Tue, 13 Oct 2026 09:12:44 GMT", properties.lastModified)
    assertEquals("video/x-matroska", properties.contentType)
    assertNull(properties.displayName)
  }

  @Test
  fun parse_defaultNamespaceAndOtherPrefixes() {
    val properties = DavProperties.parse(
      """
      <multistatus xmlns="DAV:"><response><href>/f</href>
        <propstat><prop xmlns:x="DAV:">
          <x:getcontentlength> 42 </x:getcontentlength>
          <displayname><![CDATA[Report <final>.pdf]]></displayname>
          <getetag xmlns="urn:other">not-dav</getetag>
        </prop><status>HTTP/1.1 200 OK</status></propstat>
      </response></multistatus>
      """.trimIndent(),
    )
    assertEquals(42L, properties.contentLength)
    assertEquals("Report <final>.pdf", properties.displayName)
    assertNull(properties.etag)
  }

  @Test
  fun parse_collection() {
    val properties = DavProperties.parse(
      "<D:multistatus xmlns:D='DAV:'><D:response><D:href>/dir/</D:href><D:propstat>" +
        "<D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>" +
        "<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>",
    )
    assertTrue(properties.collection)
  }

  @Test
  fun parse_missingResource() {
    val error = assertFailsWith<MissingResourceException> {
      DavProperties.parse(
        "<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>/gone</D:href>" +
          "<D:status>HTTP/1.1 404 Not Found</D:status></D:response></D:multistatus>",
      )
    }
    assertEquals(404, error.status)
  }

  @Test
  fun parse_refusesOtherDocuments() {
    assertFailsWith<IllegalArgumentException> { DavProperties.parse("<html><body/></html>") }
    assertFailsWith<IllegalArgumentException> {
      DavProperties.parse("<D:multistatus xmlns:D=\"DAV:\"></D:multistatus>")
    }
    assertFailsWith<IllegalArgumentException> { DavProperties.parse("not xml") }
  }

  @Test
  fun xml_decodesReferences_andSkipsMarkup() {
    val root = Xml.parse(
      "﻿<?xml version=\"1.0\"?><!-- c --><!DOCTYPE a [<!ENTITY e \"x\">]>" +
        "<a>&lt;&#65;&#x1F600;&amp;<?pi x?><!-- c --><b/></a>",
    )
    assertEquals("<A😀&", root.text)
    assertEquals(listOf("b"), root.children.map { it.name })
  }

  @Test
  fun xml_refusesMalformedDocuments() {
    val bad = listOf(
      "<a><b></a>",
      "<a>",
      "<p:a/>",
      "<a>&e;</a>",
      "<a>&#0;</a>",
      "<a x=1/>",
      "<a/><b/>",
      "<a>" + "<b>".repeat(70) + "</b>".repeat(70) + "</a>",
    )
    for (text in bad) {
      assertFailsWith<IllegalArgumentException>(text) { Xml.parse(text) }
    }
  }
}
