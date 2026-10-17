package com.linroid.ketch.torrent.peer

/**
 * Names a peer's client for display, from what the peer says about itself: its 20-byte peer ID
 * (BEP 20) or its BEP 10 `v`. Both are self-reported and never trusted for anything else.
 */
internal object PeerClients {
  /** The longest client name kept, in characters. */
  const val MAX_NAME = 64

  /**
   * The client [peerId] names, such as `qBittorrent 4.6.3`: Azureus style (`-qB4630-`), Shadow
   * style (`T03I---`) or Mainline style (`M7-4-3--`); `null` for any other ID or an unknown code.
   */
  fun fromPeerId(peerId: ByteArray): String? {
    if (peerId.size != 20) return null
    val id = CharArray(peerId.size) { (peerId[it].toInt() and 0xFF).toChar() }
    return azureus(id) ?: shadow(id) ?: mainline(id)
  }

  /**
   * [raw], a BEP 10 `v`, as a name to show: control and line breaking characters become spaces,
   * formatting ones (bidi overrides among them) are dropped, runs of spaces collapse and the
   * rest is cut to [MAX_NAME] characters; `null` when nothing is left.
   */
  fun fromVersion(raw: ByteArray): String? {
    val decoded = raw.copyOf(minOf(raw.size, MAX_NAME * 4)).decodeToString()
    val text = buildString {
      var i = 0
      while (i < decoded.length) {
        val char = decoded[i]
        // A surrogate pair is one code point outside the BMP, whose category the pair hides.
        if (char.isHighSurrogate() && i + 1 < decoded.length && decoded[i + 1].isLowSurrogate()) {
          val point = 0x10000 + ((char.code - 0xD800) shl 10) + (decoded[i + 1].code - 0xDC00)
          if (SUPPLEMENTARY_FORMAT.none { point in it }) append(char).append(decoded[i + 1])
          i += 2
          continue
        }
        when {
          char.isISOControl() || char == '\u2028' || char == '\u2029' -> append(' ')
          char.category == CharCategory.FORMAT || char.isSurrogate() || char == '\uFFFD' -> Unit
          else -> append(char)
        }
        i++
      }
    }.replace(WHITESPACE, " ").trim()
    if (text.isEmpty()) return null
    if (text.length <= MAX_NAME) return text
    var end = MAX_NAME
    if (text[end - 1].isHighSurrogate()) end--
    return text.substring(0, end).trimEnd()
  }

  /** `-XXabcd-`: a two-character code and four version characters. */
  private fun azureus(id: CharArray): String? {
    if (id[0] != '-' || id[7] != '-') return null
    val name = AZUREUS[id.concatToString(1, 3)] ?: return null
    val version = (3..5).map { digit(id[it]) ?: return name }
    return "$name ${version.joinToString(".")}"
  }

  /** A one-letter code, three version characters and `---`, as BitTornado sends. */
  private fun shadow(id: CharArray): String? {
    val name = SHADOW[id[0]] ?: return null
    if (id.concatToString(6, 9) != "---") return null
    val version = (1..3).map { digit(id[it]) ?: return null }
    return "$name ${version.joinToString(".")}"
  }

  /** `M` and `major-minor-patch-`, as BitTorrent's original client sends. */
  private fun mainline(id: CharArray): String? {
    if (id[0] != 'M') return null
    val match = MAINLINE.matchEntire(id.concatToString(0, 8)) ?: return null
    return "Mainline ${match.groupValues.drop(1).joinToString(".")}"
  }

  /** A version character: `0`-`9`, then `A`-`Z` for 10 to 35 and `a`-`z` for 36 to 61. */
  private fun digit(char: Char): Int? = when (char) {
    in '0'..'9' -> char - '0'
    in 'A'..'Z' -> char - 'A' + 10
    in 'a'..'z' -> char - 'a' + 36
    else -> null
  }

  private val WHITESPACE = Regex("\\s+")

  /** The format (`Cf`) code points outside the BMP, such as the tag characters. */
  private val SUPPLEMENTARY_FORMAT = listOf(
    0x110BD..0x110BD,
    0x110CD..0x110CD,
    0x13430..0x1343F,
    0x1BCA0..0x1BCA3,
    0x1D173..0x1D17A,
    0xE0001..0xE0001,
    0xE0020..0xE007F,
  )
  private val MAINLINE = Regex("""M(\d)-(\d{1,2})-(\d)--?""")

  private val SHADOW = mapOf(
    'A' to "ABC",
    'O' to "Osprey Permaseed",
    'Q' to "BTQueue",
    'R' to "Tribler",
    'S' to "Shadow",
    'T' to "BitTornado",
    'U' to "UPnP NAT",
  )

  private val AZUREUS = mapOf(
    "7T" to "aTorrent",
    "AG" to "Ares",
    "A~" to "Ares",
    "AR" to "Arctic",
    "AT" to "Artemis",
    "AV" to "Avicora",
    "AX" to "BitPump",
    "AZ" to "Vuze",
    "BB" to "BitBuddy",
    "BC" to "BitComet",
    "BE" to "BitTorrent SDK",
    "BF" to "Bitflu",
    "BG" to "BTG",
    "BI" to "BiglyBT",
    "BL" to "BitBlinder",
    "BN" to "Baidu Netdisk",
    "BP" to "BitTorrent Pro",
    "BR" to "BitRocket",
    "BS" to "BTSlave",
    "BT" to "BitTorrent",
    "BW" to "BitWombat",
    "BX" to "BittorrentX",
    "CD" to "Enhanced CTorrent",
    "CT" to "CTorrent",
    "DE" to "Deluge",
    "DP" to "Propagate Data Client",
    "EB" to "EBit",
    "ES" to "Electric Sheep",
    "FD" to "Free Download Manager",
    "FG" to "FlashGet",
    "FT" to "FoxTorrent",
    "FW" to "FrostWire",
    "FX" to "Freebox BitTorrent",
    "GS" to "GSTorrent",
    "HL" to "Halite",
    "HN" to "Hydranode",
    "KE" to "Ketch",
    "KG" to "KGet",
    "KT" to "KTorrent",
    "LC" to "LeechCraft",
    "LH" to "LH-ABC",
    "LP" to "Lphant",
    "LT" to "libtorrent (rakshasa)",
    "lt" to "libtorrent",
    "LW" to "LimeWire",
    "MO" to "MonoTorrent",
    "MP" to "MooPolice",
    "MR" to "Miro",
    "MT" to "MoonlightTorrent",
    "NX" to "Net Transport",
    "OS" to "OneSwarm",
    "OT" to "OmegaTorrent",
    "PD" to "Pando",
    "PI" to "PicoTorrent",
    "qB" to "qBittorrent",
    "QD" to "QQDownload",
    "QT" to "Qt 4 Torrent",
    "RT" to "Retriever",
    "RZ" to "RezTorrent",
    "SB" to "Swiftbit",
    "SD" to "Thunder",
    "SM" to "SoMud",
    "SP" to "BitSpirit",
    "SS" to "SwarmScope",
    "ST" to "SymTorrent",
    "st" to "SharkTorrent",
    "SZ" to "Shareaza",
    "TB" to "Torch",
    "TL" to "Tribler",
    "TN" to "TorrentDotNET",
    "TR" to "Transmission",
    "TS" to "Torrentstorm",
    "TT" to "TuoTu",
    "UL" to "uLeecher!",
    "UM" to "µTorrent for Mac",
    "UT" to "µTorrent",
    "UW" to "µTorrent Web",
    "VG" to "Vagaa",
    "WD" to "WebTorrent Desktop",
    "WT" to "BitLet",
    "WW" to "WebTorrent",
    "WY" to "FireTorrent",
    "XF" to "Xfplay",
    "XL" to "Xunlei",
    "XS" to "XSwifter",
    "XT" to "XanTorrent",
    "XX" to "Xtorrent",
    "ZT" to "ZipTorrent",
  )
}
