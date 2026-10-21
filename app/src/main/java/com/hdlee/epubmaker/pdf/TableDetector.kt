package com.hdlee.epubmaker.pdf

import com.hdlee.epubmaker.TableStrictness
import com.hdlee.epubmaker.TextUtil
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 한 줄 안에서 가로로 크게 떨어진 글자 묶음(칸) */
internal class PdfCell(val text: String, val x0: Float, val x1: Float)

/** 한 줄의 글과 위치. y 는 기준선의 위에서부터의 거리(pt). */
internal class PdfLine(
    val text: String,
    val x0: Float,
    val x1: Float,
    val y: Float,
    val size: Float,
    val bold: Boolean,
    val cells: List<PdfCell>,
)

/**
 * 글자 위치만으로 "표처럼 보이는 줄 묶음"을 찾는다. (안드로이드 의존성 없음 → 단위 테스트 가능)
 *
 * 엄격(STRICT, 기본): 표가 아닌 것을 표로 잘못 인식하는 일을 최소화한다.
 *  - 모든 줄의 칸 수가 같아야 한다 (2칸 표는 4줄 이상, 그 외 3줄 이상)
 *  - 각 열이 "전체 줄에 걸쳐" 왼쪽/가운데/오른쪽 중 한 기준으로 가지런해야 한다
 *  - 칸이 짧아야 한다 (24자 이하, 칸 너비가 본문 너비의 45% 이하) → 2단 편집 본문, "항목  긴 설명" 목록 제외
 *  - 칸 안 글이 문장(세 단어 이상 + 마침표 등으로 끝남)처럼 보이면 제외
 * 보통(NORMAL): 예전 방식. 인접한 줄끼리 정렬만 확인해 더 많이 표로 본다.
 */
internal object TableDetector {
    private const val ALIGN_TOL = 8f
    private const val STRICT_MAX_CELL_CHARS = 24
    private const val NORMAL_MAX_CELL_CHARS = 40
    private const val STRICT_MAX_CELLS = 8
    private const val STRICT_MAX_CELL_WIDTH_RATIO = 0.45f
    private const val STRICT_MAX_SENTENCE_RATIO = 0.3f

    /** 표로 판단한 줄 범위(lines 의 인덱스 범위) 목록 */
    fun detect(lines: List<PdfLine>, contentWidth: Float, strictness: TableStrictness): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (isRow(lines[i], strictness)) {
                var j = i
                while (j + 1 < lines.size && continues(lines[j], lines[j + 1], strictness)) j++
                if (j - i + 1 >= 3 && accept(lines.subList(i, j + 1), contentWidth, strictness)) {
                    ranges.add(i..j)
                }
                i = j + 1
                continue
            }
            i++
        }
        return ranges
    }

    private fun isRow(l: PdfLine, s: TableStrictness): Boolean {
        if (l.cells.size < 2) return false
        val strict = s == TableStrictness.STRICT
        if (strict && l.cells.size > STRICT_MAX_CELLS) return false
        val maxChars = if (strict) STRICT_MAX_CELL_CHARS else NORMAL_MAX_CELL_CHARS
        return l.cells.all {
            it.text.length <= maxChars && !it.text.contains("....") && !it.text.contains("··") && !it.text.contains("…")
        }
    }

    private fun continues(a: PdfLine, b: PdfLine, s: TableStrictness): Boolean {
        if (!isRow(b, s)) return false
        val gap = b.y - a.y
        val sz = max(a.size, b.size)
        val shapeOk = if (s == TableStrictness.STRICT) {
            a.cells.size == b.cells.size && gap > sz * 0.6f && gap < sz * 2.6f
        } else {
            abs(a.cells.size - b.cells.size) <= 1 && gap < sz * 3f
        }
        return shapeOk && columnsAligned(a, b)
    }

    private fun columnsAligned(a: PdfLine, b: PdfLine): Boolean {
        var matches = 0
        for (cb in b.cells) {
            if (a.cells.any { ca ->
                    abs(ca.x0 - cb.x0) < ALIGN_TOL || abs(ca.x1 - cb.x1) < ALIGN_TOL ||
                        abs((ca.x0 + ca.x1) / 2f - (cb.x0 + cb.x1) / 2f) < ALIGN_TOL
                }
            ) matches++
        }
        return matches >= max(1, min(a.cells.size, b.cells.size) / 2)
    }

    private fun spread(v: List<Float>): Float = (v.maxOrNull() ?: 0f) - (v.minOrNull() ?: 0f)

    private fun accept(rows: List<PdfLine>, contentWidth: Float, s: TableStrictness): Boolean {
        if (s == TableStrictness.NORMAL) return true
        val n = rows[0].cells.size
        if (rows.any { it.cells.size != n }) return false
        if (rows.size < (if (n == 2) 4 else 3)) return false

        // 1) 모든 열이 전체 줄에 걸쳐 가지런한가 (왼쪽·가운데·오른쪽 정렬 중 하나라도 맞으면 통과)
        for (j in 0 until n) {
            val col = rows.map { it.cells[j] }
            val best = min(
                spread(col.map { it.x0 }),
                min(spread(col.map { it.x1 }), spread(col.map { (it.x0 + it.x1) / 2f })),
            )
            if (best > ALIGN_TOL) return false
        }

        // 2) 칸이 본문 너비에 비해 좁은가 (2단 편집 본문 제외)
        val widest = rows.maxOf { r -> r.cells.maxOf { it.x1 - it.x0 } }
        if (widest > contentWidth * STRICT_MAX_CELL_WIDTH_RATIO) return false

        // 3) 칸 안 글이 문장처럼 보이지 않는가
        val cells = rows.flatMap { it.cells }
        val sentences = cells.count {
            val t = it.text.trim()
            TextUtil.endsSentence(t) && t.split(Regex("\\s+")).size >= 3
        }
        if (sentences > cells.size * STRICT_MAX_SENTENCE_RATIO) return false
        return true
    }
}
