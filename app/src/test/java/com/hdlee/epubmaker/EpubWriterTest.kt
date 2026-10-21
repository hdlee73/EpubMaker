package com.hdlee.epubmaker

import com.hdlee.epubmaker.epub.EpubWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EpubWriterTest {
    private fun sample(): ExtractResult {
        val items = mutableListOf(
            Item(Kind.HEADING, "Chapter 1", level = 1, page = 0),
            Item(Kind.PARAGRAPH, "첫 문단입니다 & 특수문자 <테스트>.", page = 0),
            Item(Kind.TABLE, data = byteArrayOf(1, 2, 3), ext = "png", page = 0),
            Item(Kind.IMAGE, data = byteArrayOf(4, 5, 6), ext = "jpg", page = 1),
            Item(Kind.HEADING, "Chapter 2", level = 1, page = 2),
            Item(Kind.PARAGRAPH, "둘째 장 문단.", page = 2),
        )
        return ExtractResult(items, "샘플", "작가", "ko", 3, null, emptyList())
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e: ZipEntry? = z.nextEntry
            while (e != null) {
                map[e.name] = z.readBytes()
                e = z.nextEntry
            }
        }
        return map
    }

    @Test
    fun writesValidStructure() {
        val bos = ByteArrayOutputStream()
        val stats = EpubWriter.write(bos, sample(), EpubSettings(), "", "")
        val files = entries(bos.toByteArray())

        assertEquals("mimetype", files.keys.first())
        assertEquals("application/epub+zip", String(files.getValue("mimetype")))
        assertTrue(files.containsKey("META-INF/container.xml"))
        assertTrue(files.containsKey("OEBPS/content.opf"))
        assertTrue(files.containsKey("OEBPS/nav.xhtml"))
        assertTrue(files.containsKey("OEBPS/chap_001.xhtml"))
        assertTrue(files.containsKey("OEBPS/chap_002.xhtml"))
        assertTrue(files.containsKey("OEBPS/images/img_0001.png"))
        assertTrue(files.containsKey("OEBPS/images/img_0002.jpg"))

        assertEquals(2, stats.chapters)
        assertEquals(2, stats.paragraphs)
        assertEquals(1, stats.tables)
        assertEquals(1, stats.images)

        val chap1 = String(files.getValue("OEBPS/chap_001.xhtml"))
        assertTrue(chap1.contains("&amp;"))
        assertTrue(chap1.contains("&lt;테스트&gt;"))
        val opf = String(files.getValue("OEBPS/content.opf"))
        assertTrue(opf.contains("<dc:title>샘플</dc:title>"))
        assertTrue(opf.contains("<dc:creator>작가</dc:creator>"))
    }

    @Test
    fun cssReflectsSettings() {
        val css = EpubWriter.buildCss(EpubSettings(fontSizePt = 18, lineHeight = 2.1f, marginPct = 9, align = AlignChoice.JUSTIFY))
        assertTrue(css.contains("font-size: 18pt"))
        assertTrue(css.contains("line-height: 2.1"))
        assertTrue(css.contains("margin: 0 9%"))
        assertTrue(css.contains("text-align: justify"))
    }

    @Test
    fun fallbackChaptersByPageWhenNoHeadings() {
        val items = (0 until 25).map { Item(Kind.PARAGRAPH, "문단 $it", page = it) }
        val chapters = EpubWriter.splitChapters(items, 10)
        assertEquals(3, chapters.size)
        assertEquals("1~10쪽", chapters[0].title)
    }
}
