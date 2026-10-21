package com.hdlee.epubmaker.epub

import com.hdlee.epubmaker.AlignChoice
import com.hdlee.epubmaker.ConvertStats
import com.hdlee.epubmaker.EpubSettings
import com.hdlee.epubmaker.ExtractResult
import com.hdlee.epubmaker.Item
import com.hdlee.epubmaker.Kind
import com.hdlee.epubmaker.ParagraphChoice
import com.hdlee.epubmaker.TextUtil.xmlEscape
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 추출 결과를 EPUB3 파일로 쓴다. (글자 크기·줄 간격·여백은 style.css 로 반영) */
object EpubWriter {

    class Chapter(val title: String, val items: List<Item>)

    fun buildCss(s: EpubSettings): String {
        val fontRule = s.font.css?.let { "font-family: $it;\n  " } ?: ""
        val pRule = when (s.paragraph) {
            ParagraphChoice.INDENT -> "margin: 0; text-indent: 1em;"
            ParagraphChoice.SPACE -> "margin: 0 0 0.8em 0; text-indent: 0;"
        }
        val align = if (s.align == AlignChoice.JUSTIFY) "justify" else "left"
        return """@charset "UTF-8";
body {
  ${fontRule}font-size: ${s.fontSizePt}pt;
  line-height: ${trim(s.lineHeight)};
  margin: 0 ${s.marginPct}%;
  text-align: $align;
  word-break: keep-all;
  overflow-wrap: break-word;
}
p { $pRule }
h1, h2, h3 {
  line-height: 1.35;
  text-indent: 0;
  text-align: left;
  page-break-after: avoid;
  margin: 1.2em 0 0.6em 0;
}
h1 { font-size: 1.6em; }
h2 { font-size: 1.35em; }
h3 { font-size: 1.15em; }
div.fig { text-align: center; margin: 1em 0; text-indent: 0; }
div.fig img { max-width: 100%; height: auto; }
"""
    }

    private fun trim(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(java.util.Locale.US, "%.1f", f)

    /** 제목(h1)이 2개 이상이면 제목 기준으로, 아니면 N쪽 단위로 장을 나눈다. */
    fun splitChapters(items: List<Item>, pagesPerChapter: Int): List<Chapter> {
        val h1Count = items.count { it.kind == Kind.HEADING && it.level == 1 }
        val chapters = ArrayList<Chapter>()
        if (h1Count >= 2) {
            var title = ""
            var cur = ArrayList<Item>()
            for (it in items) {
                if (it.kind == Kind.HEADING && it.level == 1) {
                    if (cur.isNotEmpty()) {
                        chapters.add(Chapter(title.ifEmpty { "시작" }, cur))
                        cur = ArrayList()
                    }
                    title = it.text
                }
                cur.add(it)
            }
            if (cur.isNotEmpty()) chapters.add(Chapter(title.ifEmpty { "시작" }, cur))
            return chapters
        }
        val step = pagesPerChapter.coerceAtLeast(1)
        val buckets = java.util.TreeMap<Int, MutableList<Item>>()
        for (it in items) buckets.getOrPut(it.page / step) { ArrayList() }.add(it)
        for ((k, list) in buckets) {
            val lo = k * step + 1
            val last = list.maxOf { it.page } + 1
            chapters.add(Chapter("${lo}~${minOf((k + 1) * step, last)}쪽", list))
        }
        return chapters
    }

    fun write(
        out: OutputStream,
        result: ExtractResult,
        settings: EpubSettings,
        title: String,
        author: String,
    ): ConvertStats {
        val bookTitle = title.ifBlank { result.title.ifBlank { "제목 없음" } }
        val bookAuthor = author.ifBlank { result.author.ifBlank { "작자 미상" } }
        val lang = result.language.ifBlank { "en" }
        val chapters = splitChapters(result.items, settings.chapterPages)
        val modified = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())

        val manifest = StringBuilder()
        val spine = StringBuilder()
        val navLis = StringBuilder()
        val ncxPoints = StringBuilder()
        var imgNo = 0
        var tables = 0
        var images = 0
        var paragraphs = 0

        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            // 1) mimetype: 반드시 첫 항목, 무압축
            val mt = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().also { it.update(mt) }
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mt.size.toLong()
                    compressedSize = mt.size.toLong()
                    this.crc = crc.value
                },
            )
            zip.write(mt)
            zip.closeEntry()

            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""".toByteArray(),
            )
            put("OEBPS/style/style.css", buildCss(settings).toByteArray())

            // 2) 표지
            var coverMeta = ""
            if (settings.useCover && result.cover != null) {
                put("OEBPS/images/cover.jpg", result.cover)
                put("OEBPS/cover.xhtml", xhtml(lang, "표지", """<div class="fig"><img src="images/cover.jpg" alt="표지"/></div>""").toByteArray())
                manifest.append("""<item id="cover-img" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>""").append('\n')
                manifest.append("""<item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>""").append('\n')
                spine.append("""<itemref idref="cover-page"/>""").append('\n')
                coverMeta = """<meta name="cover" content="cover-img"/>"""
            }

            // 3) 본문 장
            for ((idx, ch) in chapters.withIndex()) {
                val no = idx + 1
                val file = "chap_%03d.xhtml".format(no)
                val body = StringBuilder()
                for (it in ch.items) {
                    when (it.kind) {
                        Kind.HEADING -> {
                            val lv = it.level.coerceIn(1, 3)
                            body.append("<h$lv>${xmlEscape(it.text)}</h$lv>\n")
                        }
                        Kind.PARAGRAPH -> {
                            paragraphs++
                            body.append("<p>${xmlEscape(it.text)}</p>\n")
                        }
                        Kind.IMAGE, Kind.TABLE -> {
                            val data = it.data ?: continue
                            imgNo++
                            val name = "images/img_%04d.%s".format(imgNo, it.ext)
                            val mime = if (it.ext == "jpg") "image/jpeg" else "image/png"
                            put("OEBPS/$name", data)
                            manifest.append("""<item id="img$imgNo" href="$name" media-type="$mime"/>""").append('\n')
                            val alt = if (it.kind == Kind.TABLE) "표" else "그림"
                            if (it.kind == Kind.TABLE) tables++ else images++
                            body.append("""<div class="fig"><img src="$name" alt="$alt"/></div>""").append('\n')
                        }
                    }
                }
                put("OEBPS/$file", xhtml(lang, ch.title, body.toString()).toByteArray())
                manifest.append("""<item id="c$no" href="$file" media-type="application/xhtml+xml"/>""").append('\n')
                spine.append("""<itemref idref="c$no"/>""").append('\n')
                val t = xmlEscape(ch.title)
                navLis.append("""<li><a href="$file">$t</a></li>""").append('\n')
                ncxPoints.append(
                    """<navPoint id="n$no" playOrder="$no"><navLabel><text>$t</text></navLabel><content src="$file"/></navPoint>""",
                ).append('\n')
            }

            // 4) 목차(nav, ncx)와 opf
            val uid = "urn:uuid:${UUID.randomUUID()}"
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$lang" xml:lang="$lang">
<head><meta charset="utf-8"/><title>목차</title></head>
<body><nav epub:type="toc" id="toc"><h1>목차</h1><ol>
$navLis</ol></nav></body>
</html>""".toByteArray(),
            )
            put(
                "OEBPS/toc.ncx",
                """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="$uid"/><meta name="dtb:depth" content="1"/><meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head>
<docTitle><text>${xmlEscape(bookTitle)}</text></docTitle>
<navMap>
$ncxPoints</navMap>
</ncx>""".toByteArray(),
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$lang">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">$uid</dc:identifier>
<dc:title>${xmlEscape(bookTitle)}</dc:title>
<dc:creator>${xmlEscape(bookAuthor)}</dc:creator>
<dc:language>$lang</dc:language>
<meta property="dcterms:modified">$modified</meta>
$coverMeta
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
<item id="css" href="style/style.css" media-type="text/css"/>
$manifest</manifest>
<spine toc="ncx">
$spine</spine>
</package>""".toByteArray(),
            )
        }
        return ConvertStats(chapters.size, paragraphs, images, tables)
    }

    private fun xhtml(lang: String, title: String, body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$lang" xml:lang="$lang">
<head><meta charset="utf-8"/><title>${xmlEscape(title)}</title><link rel="stylesheet" type="text/css" href="style/style.css"/></head>
<body>
$body</body>
</html>"""
}
