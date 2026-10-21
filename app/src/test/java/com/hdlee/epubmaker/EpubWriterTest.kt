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

    private fun h(level: Int, text: String, page: Int = 0) = Item(Kind.HEADING, text, level = level, page = page)
    private fun p(text: String, page: Int = 0) = Item(Kind.PARAGRAPH, text, page = page)

    @Test
    fun noHeadings_isNotSplit() {
        val items = (0 until 25).map { p("문단 $it", page = it) }
        val chapters = EpubWriter.splitChapters(items, "내 책")
        assertEquals(1, chapters.size)
        assertEquals("내 책", chapters[0].title)
        assertEquals(25, chapters[0].items.size)
    }

    @Test
    fun singleHeading_isNotSplit() {
        val chapters = EpubWriter.splitChapters(listOf(h(1, "제목"), p("본문"), p("본문2")), "내 책")
        assertEquals(1, chapters.size)
        assertEquals(3, chapters[0].items.size)
    }

    @Test
    fun splitsAtLevel2_whenLevel1AppearsOnlyOnce() {
        val items = listOf(h(1, "책 제목"), h(2, "1장"), p("a"), h(2, "2장"), p("b"))
        val chapters = EpubWriter.splitChapters(items, "내 책")
        assertEquals(listOf("1장", "2장"), chapters.map { it.title })
        // 제목뿐인 도입부(책 제목)는 첫 장에 합쳐진다
        assertEquals(3, chapters[0].items.size)
        assertEquals(2, chapters[1].items.size)
    }

    @Test
    fun frontMatterBeforeFirstHeading_getsOwnChapter() {
        val items = listOf(p("서문입니다"), h(1, "1장"), p("a"), h(1, "2장"), p("b"))
        val chapters = EpubWriter.splitChapters(items, "내 책")
        assertEquals(listOf("시작", "1장", "2장"), chapters.map { it.title })
    }

    @Test
    fun documentWithoutHeadings_writesSingleChapterFile() {
        val result = ExtractResult(
            (0 until 30).map { p("문단 $it", page = it) }.toMutableList(), "책", "", "ko", 30, null, emptyList(),
        )
        val bos = ByteArrayOutputStream()
        val stats = EpubWriter.write(bos, result, EpubSettings(), "", "")
        val files = entries(bos.toByteArray())
        assertEquals(1, stats.chapters)
        assertTrue(files.containsKey("OEBPS/chap_001.xhtml"))
        assertTrue(!files.containsKey("OEBPS/chap_002.xhtml"))
    }
}
