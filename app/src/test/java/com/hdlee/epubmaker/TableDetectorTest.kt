package com.hdlee.epubmaker

import com.hdlee.epubmaker.pdf.PdfCell
import com.hdlee.epubmaker.pdf.PdfLine
import com.hdlee.epubmaker.pdf.TableDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableDetectorTest {
    private val width = 450f

    private fun line(y: Float, vararg cells: PdfCell) = PdfLine(
        text = cells.joinToString(" ") { it.text },
        x0 = cells.first().x0,
        x1 = cells.last().x1,
        y = y,
        size = 10f,
        bold = false,
        cells = cells.toList(),
    )

    private fun cell(text: String, x0: Float, w: Float = 30f) = PdfCell(text, x0, x0 + w)

    private fun detect(lines: List<PdfLine>, s: TableStrictness) = TableDetector.detect(lines, width, s)

    /** 3열 x 4줄, 가지런한 진짜 표 */
    private fun realTable() = (0 until 4).map { r ->
        line(100f + r * 14f, cell("R${r}C0", 72f), cell("R${r}C1", 192f), cell("R${r}C2", 312f))
    }

    @Test
    fun realTable_isDetected_inBothModes() {
        assertEquals(listOf(0..3), detect(realTable(), TableStrictness.STRICT))
        assertEquals(listOf(0..3), detect(realTable(), TableStrictness.NORMAL))
    }

    @Test
    fun twoColumnProse_isNotATable() {
        // 2단 편집 본문: 한 줄에 왼쪽 단/오른쪽 단 글이 같이 잡힌 경우 (칸이 길고 너비가 넓다)
        val lines = (0 until 6).map { r ->
            line(
                100f + r * 12f,
                PdfCell("이 문서는 변환기 테스트를 위한 예시입니다 글자 크기와", 72f, 230f),
                PdfCell("줄 간격과 여백을 조절하여 전자책 형태로 읽기 편하게", 310f, 230f),
            )
        }
        assertTrue(detect(lines, TableStrictness.STRICT).isEmpty())
    }

    @Test
    fun termWithLongDescription_isNotATable_inStrict() {
        val lines = (0 until 4).map { r ->
            line(100f + r * 14f, cell("항목$r", 72f), PdfCell("이 항목에 대한 설명이 길게 이어지는 문장입니다 ${r}번", 150f, 250f))
        }
        assertTrue(detect(lines, TableStrictness.STRICT).isEmpty())
    }

    @Test
    fun twoColumnsWithFewRows_needFourRows_inStrict() {
        fun rows(n: Int) = (0 until n).map { r -> line(100f + r * 14f, cell("키$r", 72f), cell("값$r", 192f)) }
        assertTrue(detect(rows(3), TableStrictness.STRICT).isEmpty())
        assertEquals(listOf(0..3), detect(rows(4), TableStrictness.STRICT))
        // 보통 모드는 3줄이면 표로 본다
        assertEquals(listOf(0..2), detect(rows(3), TableStrictness.NORMAL))
    }

    @Test
    fun changingColumnCount_isNotATable_inStrict() {
        val lines = listOf(
            line(100f, cell("a", 72f), cell("b", 192f), cell("c", 312f)),
            line(114f, cell("a", 72f), cell("b", 192f)),
            line(128f, cell("a", 72f), cell("b", 192f), cell("c", 312f)),
        )
        assertTrue(detect(lines, TableStrictness.STRICT).isEmpty())
    }

    @Test
    fun staircaseLayout_isNotATable_inStrict() {
        // 첫 열만 맞고 둘째 열이 계단식으로 밀리는 배치
        val lines = (0 until 4).map { r ->
            line(100f + r * 14f, cell("가", 72f, 10f), cell("나", 150f + r * 40f, 10f))
        }
        assertTrue(detect(lines, TableStrictness.STRICT).isEmpty())
    }

    @Test
    fun cellsThatAreSentences_areNotATable_inStrict() {
        val lines = (0 until 4).map { r ->
            line(100f + r * 14f, PdfCell("그는 집에 갔다.", 72f, 90f), PdfCell("나는 학교에 갔다.", 192f, 90f), PdfCell("우리는 만났다.", 312f, 90f))
        }
        assertTrue(detect(lines, TableStrictness.STRICT).isEmpty())
    }

    @Test
    fun dotLeaderToc_isNotATable() {
        val lines = (0 until 4).map { r ->
            line(100f + r * 14f, cell("제${r}장 서론", 72f, 60f), cell("....... ${r * 10}", 300f, 60f))
        }
        assertTrue(detect(lines, TableStrictness.STRICT).isEmpty())
        assertTrue(detect(lines, TableStrictness.NORMAL).isEmpty())
    }

    @Test
    fun twoSeparateTables_areFoundSeparately() {
        val first = realTable()
        val gapLine = line(200f, PdfCell("일반 문장 한 줄입니다 다음 표가 이어집니다.", 72f, 380f))
        val second = (0 until 4).map { r ->
            line(240f + r * 14f, cell("X${r}", 72f), cell("Y${r}", 192f), cell("Z${r}", 312f))
        }
        assertEquals(listOf(0..3, 5..8), detect(first + gapLine + second, TableStrictness.STRICT))
    }
}
