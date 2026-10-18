package com.linroid.ketch.webdav

/**
 * An XML element, its name resolved against the namespaces in scope.
 *
 * @property namespace the namespace URI, empty for none
 * @property name the local name
 * @property children the child elements, in order
 * @property text the character data directly inside the element, CDATA included
 */
internal class XmlElement(
  val namespace: String,
  val name: String,
  val children: List<XmlElement>,
  val text: String,
) {
  /** The first child named [name] in [namespace], or `null`. */
  fun child(namespace: String, name: String): XmlElement? =
    children.firstOrNull { it.namespace == namespace && it.name == name }

  /** The children named [name] in [namespace]. */
  fun children(namespace: String, name: String): List<XmlElement> =
    children.filter { it.namespace == namespace && it.name == name }

  override fun toString(): String = "XmlElement({$namespace}$name)"
}

/**
 * Reads the XML documents WebDAV servers answer with into [XmlElement]s: elements, attributes
 * (only namespace declarations are kept), character data, CDATA sections and the predefined and
 * numeric character references. Comments, processing instructions and the document type are
 * skipped; entities a document type declares are never expanded, so a document cannot grow past
 * its own size.
 */
internal object Xml {
  private const val MAX_DEPTH = 64
  private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"

  /**
   * The root element of [text].
   *
   * @throws IllegalArgumentException when [text] is not well-formed, nests deeper than 64
   *   elements, or uses a namespace prefix it never declares
   */
  fun parse(text: String): XmlElement = Reader(text.removePrefix("﻿")).document()

  private class Reader(private val text: String) {
    private var pos = 0

    fun document(): XmlElement {
      skipMisc()
      require(peek() == '<') { "No root element" }
      val root = element(mapOf("xml" to XML_NAMESPACE, "" to ""), depth = 0)
      skipMisc()
      require(pos >= text.length) { "Content after the root element" }
      return root
    }

    /** Skips whitespace, comments, processing instructions and the document type. */
    private fun skipMisc() {
      while (true) {
        skipWhitespace()
        when {
          text.startsWith("<?", pos) -> skipPast("?>")
          text.startsWith("<!--", pos) -> skipPast("-->")
          text.startsWith("<!DOCTYPE", pos) -> skipDoctype()
          else -> return
        }
      }
    }

    private fun skipDoctype() {
      var bracket = 0
      while (pos < text.length) {
        when (text[pos]) {
          '[' -> bracket++
          ']' -> bracket--
          '>' -> if (bracket <= 0) {
            pos++
            return
          }
        }
        pos++
      }
      throw IllegalArgumentException("Unterminated document type")
    }

    private fun element(parentScope: Map<String, String>, depth: Int): XmlElement {
      require(depth < MAX_DEPTH) { "Elements nest too deep" }
      expect('<')
      val qualified = name()
      val declarations = mutableMapOf<String, String>()
      var selfClosing = false
      while (true) {
        skipWhitespace()
        when (peek()) {
          '/' -> {
            expect('/')
            expect('>')
            selfClosing = true
            break
          }
          '>' -> {
            pos++
            break
          }
          null -> throw IllegalArgumentException("Unterminated start tag")
        }
        val attribute = name()
        skipWhitespace()
        expect('=')
        skipWhitespace()
        val quote = peek()
        require(quote == '"' || quote == '\'') { "Unquoted attribute value" }
        pos++
        val end = text.indexOf(quote, pos)
        require(end >= 0) { "Unterminated attribute value" }
        val value = decode(text.substring(pos, end))
        pos = end + 1
        when {
          attribute == "xmlns" -> declarations[""] = value
          attribute.startsWith("xmlns:") -> declarations[attribute.substring(6)] = value
        }
      }
      val scope = if (declarations.isEmpty()) parentScope else parentScope + declarations
      val prefix = qualified.substringBefore(':', "")
      val local = qualified.substringAfter(':')
      val namespace = scope[prefix]
        ?: throw IllegalArgumentException("Undeclared namespace prefix")
      if (selfClosing) return XmlElement(namespace, local, emptyList(), "")

      val children = mutableListOf<XmlElement>()
      val content = StringBuilder()
      while (true) {
        require(pos < text.length) { "Unterminated element" }
        when {
          text.startsWith("</", pos) -> {
            pos += 2
            require(name() == qualified) { "Mismatched end tag" }
            skipWhitespace()
            expect('>')
            return XmlElement(namespace, local, children, content.toString())
          }
          text.startsWith("<!--", pos) -> skipPast("-->")
          text.startsWith("<![CDATA[", pos) -> {
            val end = text.indexOf("]]>", pos + 9)
            require(end >= 0) { "Unterminated CDATA section" }
            content.append(text, pos + 9, end)
            pos = end + 3
          }
          text.startsWith("<?", pos) -> skipPast("?>")
          text[pos] == '<' -> children += element(scope, depth + 1)
          else -> {
            val end = text.indexOf('<', pos).let { if (it < 0) text.length else it }
            content.append(decode(text.substring(pos, end)))
            pos = end
          }
        }
      }
    }

    private fun name(): String {
      val start = pos
      while (pos < text.length && !text[pos].isWhitespace() && text[pos] !in "/>=<") pos++
      require(pos > start) { "Expected a name" }
      return text.substring(start, pos)
    }

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
        require(end > i) { "Unterminated reference" }
        val entity = raw.substring(i + 1, end)
        when {
          entity == "lt" -> out.append('<')
          entity == "gt" -> out.append('>')
          entity == "amp" -> out.append('&')
          entity == "quot" -> out.append('"')
          entity == "apos" -> out.append('\'')
          entity.startsWith("#x") || entity.startsWith("#X") ->
            appendCodePoint(out, entity.substring(2).toIntOrNull(16))
          entity.startsWith("#") -> appendCodePoint(out, entity.substring(1).toIntOrNull())
          else -> throw IllegalArgumentException("Unknown entity")
        }
        i = end + 1
      }
      return out.toString()
    }

    private fun appendCodePoint(out: StringBuilder, codePoint: Int?) {
      require(codePoint != null && codePoint in 1..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
        "Invalid character reference"
      }
      if (codePoint < 0x10000) {
        out.append(codePoint.toChar())
      } else {
        val offset = codePoint - 0x10000
        out.append((0xD800 + (offset shr 10)).toChar())
        out.append((0xDC00 + (offset and 0x3FF)).toChar())
      }
    }

    private fun skipWhitespace() {
      while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun skipPast(terminator: String) {
      val end = text.indexOf(terminator, pos)
      require(end >= 0) { "Unterminated markup" }
      pos = end + terminator.length
    }

    private fun peek(): Char? = text.getOrNull(pos)

    private fun expect(c: Char) {
      require(peek() == c) { "Expected '$c'" }
      pos++
    }
  }
}
