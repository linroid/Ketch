package com.linroid.ketch.app.util

/** Broad category of a download, used to pick its icon and color. */
enum class FileKind {
  Torrent, Video, Audio, Subtitle, Image, Design, Document, Pdf, Ebook, Spreadsheet,
  Presentation, Text, Code, Data, Database, Web, Archive, DiskImage, App, Model3d, Font,
  Key, Email, Unknown;

  companion object {
    /**
     * Classifies [fileName] by its extension, falling back to the source.
     *
     * [sourceUrl] is the download's URL: a torrent (magnet, `torrent:` or `.torrent` URL)
     * whose name has no known extension, such as a multi-file torrent's folder, is
     * [Torrent]. [mimeType] classifies names without a usable extension.
     */
    fun of(fileName: String, sourceUrl: String? = null, mimeType: String? = null): FileKind {
      ofName(fileName).takeIf { it != Unknown }?.let { return it }
      if (sourceUrl != null && isTorrentUrl(sourceUrl)) return Torrent
      return mimeType?.let { ofMimeType(it) } ?: Unknown
    }

    private fun ofName(fileName: String): FileKind {
      val parts = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        .lowercase().split('.')
        .dropLastWhile { it in incompleteSuffixes }
      if (parts.size < 2) return Unknown
      val ext = parts.last()
      kindsByExtension[ext]?.let { return it }
      // Split volumes: "movie.mkv.001", "archive.r00", "archive.z01".
      val isNumberedVolume = ext.length == 3 && ext.all { it.isDigit() } &&
        parts.size > 2 && parts[parts.size - 2] in kindsByExtension
      val isLegacyVolume = ext.length == 3 && (ext[0] == 'r' || ext[0] == 'z') &&
        ext[1].isDigit() && ext[2].isDigit()
      return if (isNumberedVolume || isLegacyVolume) Archive else Unknown
    }

    private fun ofMimeType(mimeType: String): FileKind? {
      val mime = mimeType.substringBefore(';').trim().lowercase()
      val subtype = mime.substringAfter('/')
      return when {
        mime == "application/x-bittorrent" -> Torrent
        mime == "text/vtt" -> Subtitle
        mime == "text/html" || mime == "application/xhtml+xml" -> Web
        mime == "text/csv" || mime == "text/tab-separated-values" -> Spreadsheet
        mime == "application/pdf" -> Pdf
        mime == "application/epub+zip" -> Ebook
        mime == "message/rfc822" -> Email
        mime.startsWith("video/") || "mpegurl" in subtype -> Video
        mime.startsWith("audio/") || subtype == "ogg" -> Audio
        mime.startsWith("image/") -> Image
        mime.startsWith("font/") -> Font
        mime.startsWith("model/") -> Model3d
        "spreadsheet" in subtype || "excel" in subtype -> Spreadsheet
        "presentation" in subtype || "powerpoint" in subtype -> Presentation
        "wordprocessing" in subtype || "msword" in subtype || subtype.endsWith(".text") ->
          Document
        "diskimage" in subtype || "iso9660" in subtype -> DiskImage
        "package-archive" in subtype || "msdownload" in subtype || "msi" in subtype ||
          "binary-package" in subtype || "rpm" in subtype || subtype == "java-archive" -> App
        subtype in archiveMimeSubtypes -> Archive
        "sql" in subtype -> Database
        subtype == "json" || subtype == "xml" || subtype == "yaml" || subtype == "toml" ||
          subtype.endsWith("+json") || subtype.endsWith("+xml") -> Data
        "javascript" in subtype || "typescript" in subtype -> Code
        mime.startsWith("text/") -> Text
        else -> null
      }
    }

    private val archiveMimeSubtypes = setOf(
      "zip", "x-zip-compressed", "vnd.rar", "x-rar-compressed", "x-7z-compressed", "gzip",
      "x-gzip", "x-tar", "x-bzip2", "x-xz", "zstd", "x-lzip", "x-lzma", "vnd.ms-cab-compressed",
    )

    private fun isTorrentUrl(url: String): Boolean {
      val lower = url.trim().lowercase()
      return lower.startsWith("magnet:") || lower.startsWith("torrent:") ||
        lower.substringBefore('?').substringBefore('#').endsWith(".torrent")
    }

    /** Suffixes browsers and download managers add to unfinished files. */
    private val incompleteSuffixes =
      setOf("part", "partial", "crdownload", "download", "!ut", "!qb")

    private val kindsByExtension: Map<String, FileKind> = buildMap {
      fun add(kind: FileKind, extensions: String) {
        for (ext in extensions.trim().split(Regex("\\s+"))) {
          check(put(ext, kind) == null) { "Duplicate file extension: $ext" }
        }
      }
      add(Torrent, "torrent")
      // "ts" and "mts" are far more often MPEG transport streams than TypeScript here.
      add(
        Video,
        """
        mp4 m4v mkv mk3d webm mov qt avi wmv asf flv f4v mpg mpeg mpe m1v m2v m2ts mts ts m2t
        vob 3gp 3g2 ogv ogm rm rmvb divx mxf dv hevc h264 h265 264 265 y4m ivf nut wtv amv m3u8
        """,
      )
      add(
        Audio,
        """
        mp3 mp2 mpa aac adts m4a m4b m4r flac wav wave aif aiff aifc caf ogg oga opus spx wma
        ape wv mka mpc tta tak ac3 eac3 dts amr awb au snd ra mid midi kar rmi xm mod s3m it
        dsf dff weba 3ga aa aax gsm voc w64 rf64 bwf m3u pls xspf
        """,
      )
      add(Subtitle, "srt ass ssa vtt sub sbv smi sami idx sup lrc ttml dfxp scc")
      add(
        Image,
        """
        jpg jpeg jpe jfif pjpeg pjp png apng gif webp bmp dib tif tiff heic heif hif avif jxl
        jp2 j2k jpf jpx ico icns cur svg svgz tga exr hdr dds pcx ppm pgm pbm pnm pam qoi wbmp
        xbm xpm emf wmf raw cr2 cr3 crw nef nrw arw srf sr2 dng orf rw2 raf pef srw x3f 3fr erf
        kdc mrw rwl iiq
        """,
      )
      add(
        Design,
        """
        psd psb ai eps sketch fig xd afdesign afphoto afpub indd indt idml cdr xcf kra clip
        procreate ora pdn vsd vsdx drawio
        """,
      )
      add(
        Document,
        "doc docx docm dot dotx dotm odt ott fodt rtf pages wpd wps wri hwp hwpx abw sxw",
      )
      add(Pdf, "pdf xps oxps")
      add(
        Ebook,
        "epub mobi azw azw3 azw4 kfx fb2 fbz djvu djv ibooks lit lrf prc cbz cbr cb7 cbt cba chm",
      )
      add(Spreadsheet, "xls xlsx xlsm xlsb xlt xltx xltm ods ots fods numbers csv tsv et")
      // "key" is Keynote; private keys usually travel as .pem.
      add(Presentation, "ppt pptx pptm pps ppsx ppsm pot potx potm key odp otp fodp dps")
      add(Text, "txt text md markdown mdown mkd rst adoc asciidoc org textile log nfo diz")
      add(
        Code,
        """
        c h cpp cc cxx hpp hh hxx ino cs csx fs fsx fsi vb java kt kts scala sc groovy gradle
        clj cljs cljc go rs swift m mm py pyw pyi ipynb rb erb php phtml pl pm lua r rmd dart js
        mjs cjs jsx tsx vue svelte astro css scss sass less styl sh bash zsh fish ksh csh bat
        cmd ps1 psm1 vbs hs lhs ex exs erl hrl elm ml mli nim zig sv vhdl asm s jl f90 f95 pas
        sol tf hcl nix cmake mk gd glsl hlsl frag vert wgsl metal cu cuh tex sty patch diff
        """,
      )
      add(
        Data,
        """
        json jsonc json5 jsonl ndjson geojson topojson xml xsd xsl xslt dtd rss atom yaml yml
        toml ini cfg conf config cnf properties env plist proto gpx kml kmz ics ical vcf vcard
        har reg
        """,
      )
      add(
        Database,
        """
        db db3 sqlite sqlite3 sdb mdb accdb frm ibd myd dbf ndf ldf sql dump parquet orc avro
        feather arrow h5 hdf5 realm rdb
        """,
      )
      add(Web, "html htm xhtml shtml mht mhtml maff webarchive url webloc")
      add(
        Archive,
        """
        zip zipx rar 7z tar gz gzip tgz taz bz2 bzip2 tbz tbz2 xz txz lz tlz lzma lzo lz4 zst
        zstd tzst z cab arj ace sit sitx cpio sz br lha lzh alz egg pea arc xar zoo s7z pak war
        ear
        """,
      )
      add(
        DiskImage,
        """
        iso img dmg vhd vhdx vmdk vdi hdd qcow qcow2 cue bin nrg mdf mds cdi ccd toast udf isz
        wim esd swm ova ovf dsk sparseimage cso chd wbfs rvz nsp xci
        """,
      )
      add(
        App,
        """
        exe msi msix msixbundle appx appxbundle apk apks apkm xapk aab ipa deb udeb rpm pkg
        mpkg appimage snap flatpak flatpakref jar run app crx xpi vsix whl nupkg gem ipk hap dll
        so dylib
        """,
      )
      add(
        Model3d,
        """
        obj fbx gltf glb stl 3mf amf blend dae 3ds max ma mb c4d usd usda usdc usdz ply step stp
        iges igs dwg dxf skp x3d wrl vrml lwo 3dm f3d ipt iam sldprt sldasm fcstd gcode
        """,
      )
      add(Font, "ttf otf ttc otc woff woff2 eot fon fnt pfa pfb pfm afm dfont bdf pcf")
      add(
        Key,
        """
        pem crt cer der p12 pfx p7b p7c p7s crl csr pub gpg pgp sig asc minisig jks keystore bks
        kdbx kdb ovpn mobileprovision md5 sha1 sha256 sha512 sfv
        """,
      )
      add(Email, "eml emlx msg mbox oft pst ost")
    }
  }
}
