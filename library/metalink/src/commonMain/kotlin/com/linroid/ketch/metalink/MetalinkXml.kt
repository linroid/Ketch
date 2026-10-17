package com.linroid.ketch.metalink

/**
 * An element of a parsed XML document. Names are local: namespace prefixes are dropped, which
 * lets one reader take Metalink 3 and 4 documents alike.
 *
 * @property text the character data directly inside the element, entities decoded
 */
internal class XmlElement(
  val name: String,
  val attributes: Map<String, String>,
  val children: List<XmlElement>,
  val text: String,
) {
  /** The first child called [name], or `null`. */
  fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }

  /** Every child called [name], in document order. */
  fun children(name: String): List<XmlElement> = children.filter { it.name == name }
}

/** A document [parseXml] does not read. */
internal class XmlException(message: String) : Exception(message)

/**
 * Parses [text], a small XML document, into its root element.
 *
 * Only what Metalink needs is read: elements, attributes, character data, CDATA sections and the
 * predefined and numeric character references. Comments and processing instructions are
 * skipped. Document type declarations are refused, so no entity can expand or reach outside the
 * document, and nesting and element counts are bounded.
 *
 * @throws XmlException when [text] is not such a document
 */
internal fun parseXml(text: String): XmlElement = XmlParser(text).document()

private class XmlParser(private val s: String) {
  private var pos = 0
  private var elements = 0

  fun document(): XmlElement {
    if (s.startsWith('﻿')) pos = 1
    skipProlog()
    if (!s.startsWith("<", pos)) fail("No root element")
    val root = element(0)
    skipProlog()
    if (pos != s.length) fail("Content after the root element")
    return root
  }

  private fun skipProlog() {
    while (true) {
      skipWhitespace()
      when {
        s.startsWith("<?", pos) -> skipPast("?>")
        s.startsWith("<!--", pos) -> skipPast("-->")
        s.startsWith("<!", pos) -> fail("Document type declarations are not supported")
        else -> return
      }
    }
  }

  private fun element(depth: Int): XmlElement {
    if (depth >= MAX_DEPTH) fail("Elements are nested too deeply")
    if (++elements > MAX_ELEMENTS) fail("Too many elements")
    pos++ // '<'
    val name = name()
    val attributes = LinkedHashMap<String, String>()
    while (true) {
      skipWhitespace()
      if (s.startsWith("/>", pos)) {
        pos += 2
        return XmlElement(local(name), attributes, emptyList(), "")
      }
      if (peek() == '>') {
        pos++
        break
      }
      val attribute = name()
      skipWhitespace()
      expect('=')
      skipWhitespace()
      val quote = peek()
      if (quote != '"' && quote != '\'') fail("Unquoted attribute")
      val end = s.indexOf(quote, pos + 1)
      if (end < 0) fail("Unclosed attribute")
      val raw = s.substring(pos + 1, end)
      if ('<' in raw) fail("'<' in an attribute")
      attributes[local(attribute)] = decode(raw)
      pos = end + 1
    }
    val children = mutableListOf<XmlElement>()
    val text = StringBuilder()
    while (true) {
      if (pos >= s.length) fail("Unclosed element")
      when {
        s.startsWith("</", pos) -> {
          pos += 2
          if (name() != name) fail("Mismatched closing tag")
          skipWhitespace()
          expect('>')
          return XmlElement(local(name), attributes, children, text.toString())
        }
        s.startsWith("<!--", pos) -> skipPast("-->")
        s.startsWith("<![CDATA[", pos) -> {
          val end = s.indexOf("]]>", pos + 9)
          if (end < 0) fail("Unclosed CDATA section")
          text.append(s, pos + 9, end)
          pos = end + 3
        }
        s.startsWith("<?", pos) -> skipPast("?>")
        s.startsWith("<!", pos) -> fail("Unsupported markup")
        peek() == '<' -> children += element(depth + 1)
        else -> {
          val end = s.indexOf('<', pos).let { if (it < 0) s.length else it }
          text.append(decode(s.substring(pos, end)))
          pos = end
        }
      }
      if (text.length > MAX_TEXT) fail("Text is too long")
    }
  }

  private fun name(): String {
    val start = pos
    while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] in "_-.:")) pos++
    if (pos == start) fail("Expected a name")
    return s.substring(start, pos)
  }

  private fun local(name: String): String = name.substringAfter(':')

  private fun decode(raw: String): String {
    if ('&' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
      val c = raw[i]
      if (c != '&') {
        out.append(c)
        i++
        continue
      }
      val end = raw.indexOf(';', i)
      if (end < 0 || end - i > 10) fail("Malformed character reference")
      val entity = raw.substring(i + 1, end)
      when (entity) {
        "amp" -> out.append('&')
        "lt" -> out.append('<')
        "gt" -> out.append('>')
        "quot" -> out.append('"')
        "apos" -> out.append('\'')
        else -> {
          val code = when {
            entity.startsWith("#x") -> entity.substring(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.substring(1).toIntOrNull()
            else -> null
          }
          if (code == null || code !in 1..0x10FFFF || code in 0xD800..0xDFFF) {
            fail("Unknown entity")
          }
          if (code < 0x10000) {
            out.append(code.toChar())
          } else {
            val offset = code - 0x10000
            out.append((0xD800 + (offset shr 10)).toChar())
            out.append((0xDC00 + (offset and 0x3FF)).toChar())
          }
        }
      }
      i = end + 1
    }
    return out.toString()
  }

  private fun skipWhitespace() {
    while (pos < s.length && s[pos].isWhitespace()) pos++
  }

  private fun skipPast(marker: String) {
    val end = s.indexOf(marker, pos)
    if (end < 0) fail("Unclosed '$marker'")
    pos = end + marker.length
  }

  private fun expect(c: Char) {
    if (peek() != c) fail("Expected '$c'")
    pos++
  }

  private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'

  private fun fail(message: String): Nothing = throw XmlException("$message at offset $pos")

  companion object {
    const val MAX_DEPTH = 32
    const val MAX_ELEMENTS = 500_000
    const val MAX_TEXT = 64 * 1024
  }
}
