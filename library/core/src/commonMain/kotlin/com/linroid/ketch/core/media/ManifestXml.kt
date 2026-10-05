package com.linroid.ketch.core.media

internal class XmlElement(val qualifiedName: String, val attributes: Map<String, String>) {
  val name: String = qualifiedName.substringAfter(':')
  val children: MutableList<XmlElement> = mutableListOf()
  val text: StringBuilder = StringBuilder()
  fun child(name: String): XmlElement? = children.singleOrNull { it.name == name }
  fun contains(name: String): Boolean = this.name == name || children.any { it.contains(name) }
}

/** A bounded XML tree for manifests. DTDs, entities and declarations are never interpreted. */
internal fun manifestXml(source: String): XmlElement {
  val stack = mutableListOf<XmlElement>()
  var root: XmlElement? = null
  var offset = 0
  var nodes = 0
  while (offset < source.length) {
    if (source[offset] != '<') {
      val end = source.indexOf('<', offset).let { if (it < 0) source.length else it }
      val text = source.substring(offset, end)
      if (stack.isEmpty()) mediaRequire(text.isBlank() || text == "\uFEFF", "Invalid XML text")
      else stack.last().text.append(xmlText(text))
      offset = end
      continue
    }
    if (source.startsWith("<!--", offset) || source.startsWith("<?", offset)) {
      val marker = if (source.startsWith("<!--", offset)) "-->" else "?>"
      val end = source.indexOf(marker, offset + 2)
      mediaRequire(end >= 0, "Unclosed XML declaration")
      offset = end + marker.length
      continue
    }
    mediaRequire(!source.startsWith("<!", offset),
      "XML declarations and entities are not supported")
    var end = offset + 1
    var quote: Char? = null
    while (end < source.length) {
      val char = source[end]
      if (quote != null) { if (char == quote) quote = null }
      else if (char == '\'' || char == '"') quote = char
      else if (char == '>') break
      end++
    }
    mediaRequire(end < source.length, "Unclosed XML tag")
    val tag = source.substring(offset + 1, end).trim()
    offset = end + 1
    if (tag.startsWith('/')) {
      mediaRequire(stack.isNotEmpty() && stack.last().qualifiedName == tag.drop(1).trim(),
        "Mismatched XML tag")
      stack.removeAt(stack.lastIndex)
      continue
    }
    val body = tag.removeSuffix("/")
    val name = XML_NAME.find(body)?.takeIf { it.range.first == 0 }?.value
    mediaRequire(name != null, "Invalid XML tag")
    val attrs = mutableMapOf<String, String>()
    var at = name!!.length
    while (at < body.length && body.substring(at).isNotBlank()) {
      val attr = XML_ATTRIBUTE.matchAt(body, at)
      mediaRequire(attr != null, "Invalid XML attribute")
      val key = attr!!.groupValues[1]
      mediaRequire(key !in attrs, "Duplicate XML attribute")
      attrs[key] = xmlText(attr.groupValues[2].drop(1).dropLast(1))
      at = attr.range.last + 1
    }
    val element = XmlElement(name, attrs)
    mediaRequire(++nodes <= 20_000 && stack.size < 32, "Media manifest is too complex")
    if (stack.isEmpty()) {
      mediaRequire(root == null, "Multiple XML roots")
      root = element
    } else stack.last().children += element
    if (!tag.endsWith('/')) stack += element
  }
  mediaRequire(root != null && stack.isEmpty(), "Incomplete XML manifest")
  return root!!
}

private fun xmlText(text: String): String = buildString {
  var at = 0
  while (at < text.length) {
    val char = text[at++]
    if (char != '&') { append(char); continue }
    val end = text.indexOf(';', at)
    mediaRequire(end >= at && end - at <= 12, "Invalid XML escape")
    val escape = text.substring(at, end)
    val decoded = when (escape) {
      "amp" -> '&'.code
      "lt" -> '<'.code
      "gt" -> '>'.code
      "quot" -> '"'.code
      "apos" -> '\''.code
      else -> when {
        escape.startsWith("#x") -> escape.drop(2).toIntOrNull(16)
        escape.startsWith('#') -> escape.drop(1).toIntOrNull()
        else -> null
      }
    }
    mediaRequire(decoded != null && decoded in 1..0x10ffff && decoded !in 0xd800..0xdfff,
      "Unsupported XML entity")
    if (decoded!! <= 0xffff) append(decoded.toChar())
    else {
      append((0xd800 + ((decoded - 0x10000) shr 10)).toChar())
      append((0xdc00 + ((decoded - 0x10000) and 1023)).toChar())
    }
    at = end + 1
  }
}

private val XML_NAME = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
private val XML_ATTRIBUTE = Regex("\\s+([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=\\s*(\"[^\"]*\"|'[^']*')")
