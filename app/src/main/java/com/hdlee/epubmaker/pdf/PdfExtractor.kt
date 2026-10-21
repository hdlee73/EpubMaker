package com.hdlee.epubmaker.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hdlee.epubmaker.ConvertException
import com.hdlee.epubmaker.ExtractOptions
import com.hdlee.epubmaker.ExtractResult
import com.hdlee.epubmaker.Item
import com.hdlee.epubmaker.Kind
import com.hdlee.epubmaker.TextUtil
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** PDF → 순서가 있는 항목(문단·제목·그림·표 이미지) 목록 */
class PdfExtractor {

    // ------------------------------------------------------------------ 내부 자료구조
    private class Cell(val text: String, val x0: Float, val x1: Float)

    private class RawLine(
        val text: String,
        val x0: Float,
        val x1: Float,
        val y: Float, // 기준선(위에서부터의 거리, pt)
        val size: Float,
        val bold: Boolean,
        val cells: List<Cell>,
    )

    private class ImgBox(val x: Float, val top: Float, val w: Float, val h: Float)

    private class PageData(val width: Float, val height: Float, val lines: List<RawLine>, val images: List<ImgBox>)

    private class Pos(val y: Float, val item: Item)

    private class Ctx(
        val bodySize: Float,
        val repeatedMargin: Set<String>,
        val repeatedImages: Set<String>,
    )

    // ------------------------------------------------------------------ PDFBox 도우미
    private class LineCollector : PDFTextStripper() {
        val lines = ArrayList<RawLine>()

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val tps = textPositions.filter { !it.unicode.isNullOrEmpty() }
            val trimmed = text.trim()
            if (tps.isEmpty() || trimmed.isEmpty()) return

            var sizeSum = 0f
            var chars = 0
            var bold = true
            val cells = ArrayList<Cell>()
            var cur = StringBuilder()
            var cx0 = tps[0].xDirAdj
            var prevEnd = cx0
            var first = true
            for (tp in tps) {
                val u: String = tp.unicode
                val x = tp.xDirAdj
                val fs = tp.fontSizeInPt
                if (!first && x - prevEnd > fs * 1.5f && cur.isNotBlank()) {
                    cells.add(Cell(cur.toString().trim(), cx0, prevEnd))
                    cur = StringBuilder()
                    cx0 = x
                }
                cur.append(u)
                prevEnd = x + tp.widthDirAdj
                first = false
                if (u.isNotBlank()) {
                    sizeSum += fs
                    chars++
                    val fn = tp.font?.name?.lowercase() ?: ""
                    if (!(fn.contains("bold") || fn.contains("black") || fn.contains("heavy"))) bold = false
                }
            }
            if (cur.isNotBlank()) cells.add(Cell(cur.toString().trim(), cx0, prevEnd))
            if (chars == 0) return
            val last = tps.last()
            lines.add(
                RawLine(
                    text = trimmed,
                    x0 = tps.first().xDirAdj,
                    x1 = max(last.xDirAdj + last.widthDirAdj, prevEnd),
                    y = tps.first().yDirAdj,
                    size = sizeSum / chars,
                    bold = bold,
                    cells = cells,
                ),
            )
        }
    }

    /** 페이지 안의 그림(래스터) 위치를 찾는다. */
    private class ImageLocator : PDFStreamEngine() {
        val found = ArrayList<FloatArray>() // [x, yBottom, w, h] (PDF 사용자 좌표)

        init {
            addOperator(Concatenate())
            addOperator(DrawObject())
            addOperator(SetGraphicsStateParameters())
            addOperator(Save())
            addOperator(Restore())
            addOperator(SetMatrix())
        }

        override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
            if (operator.name == "Do" && operands.isNotEmpty()) {
                val name = operands[0] as? COSName
                val xobj = if (name != null) resources.getXObject(name) else null
                if (xobj is PDImageXObject) {
                    val m = graphicsState.currentTransformationMatrix
                    found.add(floatArrayOf(m.translateX, m.translateY, m.scalingFactorX, m.scalingFactorY))
                } else if (xobj is PDFormXObject) {
                    showForm(xobj)
                }
            } else {
                super.processOperator(operator, operands)
            }
        }
    }

    /** PdfRenderer 페이지를 필요할 때만 열어서 영역을 이미지로 렌더링한다. */
    private class RegionRenderer(private val renderer: PdfRenderer, private val index: Int) {
        private var page: PdfRenderer.Page? = null

        fun render(left: Float, top: Float, right: Float, bottom: Float, zoom: Float, png: Boolean): ByteArray? {
            val p = page ?: renderer.openPage(index).also { page = it }
            val l = max(0f, left)
            val t = max(0f, top)
            val r = min(p.width.toFloat(), right)
            val b = min(p.height.toFloat(), bottom)
            val rw = r - l
            val rh = b - t
            if (rw < 4f || rh < 4f) return null
            var z = zoom
            if (rw * z > 2400f) z = 2400f / rw
            if (rh * z > 3200f) z = min(z, 3200f / rh)
            val bw = ceil(rw * z).toInt().coerceAtLeast(1)
            val bh = ceil(rh * z).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bmp).drawColor(Color.WHITE)
                val m = Matrix()
                m.postTranslate(-l, -t)
                m.postScale(z, z)
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bos = ByteArrayOutputStream()
                if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                else bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
                return bos.toByteArray()
            } finally {
                bmp.recycle()
            }
        }

        fun pageSize(): Pair<Float, Float> {
            val p = page ?: renderer.openPage(index).also { page = it }
            return p.width.toFloat() to p.height.toFloat()
        }

        fun close() {
            page?.close()
            page = null
        }
    }

    // ------------------------------------------------------------------ 진입점
    fun extract(
        file: File,
        opts: ExtractOptions,
        progress: (Int, Int, String) -> Unit,
        checkCancel: () -> Unit,
    ): ExtractResult {
        val doc = try {
            PDDocument.load(file)
        } catch (e: InvalidPasswordException) {
            throw ConvertException("암호가 걸린 PDF입니다. 암호를 해제한 뒤 다시 시도해 주세요.")
        } catch (e: Exception) {
            throw ConvertException("PDF를 열 수 없습니다. 손상되었거나 지원하지 않는 형식일 수 있습니다.")
        }
        return doc.use { d ->
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                pfd.close()
                throw ConvertException("PDF를 열 수 없습니다. (${e.javaClass.simpleName})")
            }
            try {
                run(d, renderer, opts, progress, checkCancel)
            } finally {
                renderer.close()
                pfd.close()
            }
        }
    }

    private fun run(
        d: PDDocument,
        renderer: PdfRenderer,
        opts: ExtractOptions,
        progress: (Int, Int, String) -> Unit,
        checkCancel: () -> Unit,
    ): ExtractResult {
        val total = d.numberOfPages

        // ---- 1차: 모든 페이지의 줄/그림 위치 수집 ----
        val pages = ArrayList<PageData>(total)
        for (p in 0 until total) {
            checkCancel()
            progress(p, total, "문서 분석 중 (${p + 1}/$total)")
            val page = d.getPage(p)
            val box = page.cropBox
            val rot = page.rotation
            val w = if (rot == 90 || rot == 270) box.height else box.width
            val h = if (rot == 90 || rot == 270) box.width else box.height
            val collector = LineCollector()
            collector.startPage = p + 1
            collector.endPage = p + 1
            try {
                collector.getText(d)
            } catch (e: Exception) {
                // 손상된 페이지는 건너뜀
            }
            val imgs = if (opts.includeImages && rot == 0) locateImages(page, box) else emptyList()
            pages.add(PageData(w, h, collector.lines, imgs))
        }

        // ---- 본문 글자 크기, 반복되는 머리글/바닥글·그림 파악 ----
        val sizeChars = HashMap<Float, Int>()
        val marginPages = HashMap<String, HashSet<Int>>()
        val imagePages = HashMap<String, HashSet<Int>>()
        for ((pno, pd) in pages.withIndex()) {
            for (l in pd.lines) {
                val key = (l.size * 10f).roundToInt() / 10f
                sizeChars[key] = (sizeChars[key] ?: 0) + l.text.length
                if (l.y < pd.height * 0.09f || l.y > pd.height * 0.91f) {
                    marginPages.getOrPut(TextUtil.normKey(l.text)) { HashSet() }.add(pno)
                }
            }
            for (im in pd.images) imagePages.getOrPut(imageKey(im)) { HashSet() }.add(pno)
        }
        val bodySize = sizeChars.maxByOrNull { it.value }?.key ?: 10f
        val repeatMin = if (total >= 3) max(3, (total * 0.4f).toInt()) else Int.MAX_VALUE
        val ctx = Ctx(
            bodySize = bodySize,
            repeatedMargin = marginPages.filter { it.value.size >= repeatMin && it.key.isNotEmpty() }.keys,
            repeatedImages = imagePages.filter { it.value.size >= repeatMin }.keys,
        )

        // ---- 2차: 페이지별 항목 생성 ----
        val items = ArrayList<Item>()
        for ((pno, pd) in pages.withIndex()) {
            checkCancel()
            progress(pno, total, "변환 중 (${pno + 1}/$total)")
            items.addAll(processPage(pno, pd, ctx, opts, renderer))
        }

        // ---- 제목 단계 부여 ----
        val sizes = items.filter { it.kind == Kind.HEADING && it.level == 0 }
            .map { (it.size * 10f).roundToInt() / 10f }.distinct().sortedDescending()
        for (it in items) {
            if (it.kind != Kind.HEADING) continue
            it.level = if (it.level == 9) 3 else min(sizes.indexOf((it.size * 10f).roundToInt() / 10f) + 1, 3).coerceAtLeast(1)
        }

        val merged = if (opts.mergeParagraphs) mergeParagraphs(items) else items

        // ---- 메타데이터 ----
        val info = d.documentInformation
        val sample = merged.asSequence().filter { it.kind != Kind.IMAGE && it.kind != Kind.TABLE }
            .map { it.text }.joinToString("").take(2000)
        val language = if (TextUtil.hangulRatio(sample) > 0.1f) "ko" else "en"
        val textChars = merged.sumOf { it.text.length }
        val warnings = ArrayList<String>()
        if (textChars < 20 * max(total, 1)) {
            warnings.add("추출된 텍스트가 거의 없습니다. 스캔(이미지) PDF로 보이며, 글자를 읽으려면 OCR이 필요합니다.")
        }
        var cover: ByteArray? = null
        if (opts.makeCover && total > 0) {
            val rr = RegionRenderer(renderer, 0)
            try {
                val (pw, ph) = rr.pageSize()
                cover = rr.render(0f, 0f, pw, ph, 1200f / pw, false)
            } finally {
                rr.close()
            }
        }
        return ExtractResult(
            items = merged,
            title = info?.title?.trim().orEmpty(),
            author = info?.author?.trim().orEmpty(),
            language = language,
            pageCount = total,
            cover = cover,
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------ 페이지 처리
    private fun processPage(pno: Int, pd: PageData, ctx: Ctx, opts: ExtractOptions, renderer: PdfRenderer): List<Item> {
        var lines = pd.lines
        if (opts.removeHeadersFooters) {
            lines = lines.filter { l ->
                val inMargin = l.y < pd.height * 0.09f || l.y > pd.height * 0.91f
                !(inMargin && (TextUtil.isPageNumber(l.text) || TextUtil.normKey(l.text) in ctx.repeatedMargin))
            }
        }

        val rr = RegionRenderer(renderer, pno)
        try {
            val others = ArrayList<Pos>() // 표/그림
            val tableRects = ArrayList<FloatArray>()
            val consumed = BooleanArray(lines.size)

            if (opts.tablesAsImages) {
                for (range in detectTables(lines)) {
                    val rows = lines.subList(range.first, range.last + 1)
                    val left = rows.minOf { it.x0 } - 6f
                    val right = rows.maxOf { it.x1 } + 6f
                    val top = rows.first().y - rows.first().size - 6f
                    val bottom = rows.last().y + rows.last().size * 0.4f + 6f
                    val png = try {
                        rr.render(left, top, right, bottom, 2.5f, true)
                    } catch (e: Exception) {
                        null
                    }
                    if (png != null) {
                        for (i in range) consumed[i] = true
                        tableRects.add(floatArrayOf(left, top, right, bottom))
                        others.add(Pos(top, Item(Kind.TABLE, data = png, ext = "png", page = pno)))
                    }
                }
            }

            if (opts.includeImages) {
                val pageArea = pd.width * pd.height
                val hasText = lines.isNotEmpty()
                val seen = HashSet<String>()
                for (im in pd.images) {
                    val key = imageKey(im)
                    if (im.w < opts.minImagePt || im.h < opts.minImagePt) continue
                    if (key in ctx.repeatedImages || !seen.add(key)) continue
                    if (hasText && im.w * im.h > pageArea * 0.85f) continue // 배경 이미지
                    if (tableRects.any { overlapRatio(im, it) > 0.5f }) continue
                    val jpg = try {
                        rr.render(im.x, im.top, im.x + im.w, im.top + im.h, 2f, false)
                    } catch (e: Exception) {
                        null
                    }
                    if (jpg != null) others.add(Pos(im.top, Item(Kind.IMAGE, data = jpg, ext = "jpg", page = pno)))
                }
            }

            val rest = lines.filterIndexed { i, _ -> !consumed[i] }
            val text = groupParagraphs(rest, ctx.bodySize, pno)

            // 그림/표를 위→아래 읽는 순서에 맞게 끼워 넣기
            val out = ArrayList<Pos>(text)
            for (o in others.sortedByDescending { it.y }) {
                val idx = out.indexOfFirst { it.y > o.y }.let { if (it < 0) out.size else it }
                out.add(idx, o)
            }
            return out.map { it.item }
        } finally {
            rr.close()
        }
    }

    private fun overlapRatio(im: ImgBox, r: FloatArray): Float {
        val ix = max(0f, min(im.x + im.w, r[2]) - max(im.x, r[0]))
        val iy = max(0f, min(im.top + im.h, r[3]) - max(im.top, r[1]))
        val area = im.w * im.h
        return if (area <= 0f) 0f else (ix * iy) / area
    }

    private fun imageKey(im: ImgBox) =
        "${(im.x / 4).roundToInt()}_${(im.top / 4).roundToInt()}_${(im.w / 4).roundToInt()}_${(im.h / 4).roundToInt()}"

    private fun locateImages(page: PDPage, box: PDRectangle): List<ImgBox> {
        val loc = ImageLocator()
        try {
            loc.processPage(page)
        } catch (e: Exception) {
            return emptyList()
        }
        return loc.found.map { f ->
            val h = abs(f[3])
            val w = abs(f[2])
            ImgBox(f[0] - box.lowerLeftX, box.height - (f[1] - box.lowerLeftY) - h, w, h)
        }
    }

    // ------------------------------------------------------------------ 표 감지
    private fun isRow(l: RawLine) =
        l.cells.size >= 2 && l.cells.all { it.text.length <= 40 && !it.text.contains("....") && !it.text.contains("··") }

    private fun columnsAligned(a: RawLine, b: RawLine): Boolean {
        var matches = 0
        for (cb in b.cells) {
            if (a.cells.any { ca ->
                    abs(ca.x0 - cb.x0) < 8f || abs(ca.x1 - cb.x1) < 8f ||
                        abs((ca.x0 + ca.x1) / 2f - (cb.x0 + cb.x1) / 2f) < 8f
                }
            ) matches++
        }
        return matches >= max(1, min(a.cells.size, b.cells.size) / 2)
    }

    /** 가로로 간격을 두고 정렬된 짧은 칸이 3줄 이상 이어지면 표로 본다. */
    private fun detectTables(lines: List<RawLine>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (isRow(lines[i])) {
                var j = i
                while (j + 1 < lines.size && isRow(lines[j + 1]) &&
                    abs(lines[j].cells.size - lines[j + 1].cells.size) <= 1 &&
                    (lines[j + 1].y - lines[j].y) < 3f * max(lines[j].size, lines[j + 1].size) &&
                    columnsAligned(lines[j], lines[j + 1])
                ) j++
                if (j - i + 1 >= 3) {
                    ranges.add(i..j)
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return ranges
    }

    // ------------------------------------------------------------------ 문단 만들기
    private fun breaksParagraph(p: RawLine, l: RawLine, left: Float, width: Float): Boolean {
        val gap = l.y - p.y
        if (gap < -p.size * 0.5f) return true // 단/위치가 거꾸로 이동
        if (gap > max(p.size, l.size) * 1.75f) return true // 큰 세로 간격
        if (abs(l.size - p.size) > p.size * 0.12f) return true // 글자 크기 변화
        if (l.bold != p.bold) return true
        if (TextUtil.isListStart(l.text)) return true
        if (TextUtil.endsSentence(p.text)) {
            if ((left + width) - p.x1 > width * 0.15f) return true // 짧게 끝난 줄
            if (l.x0 > left + l.size * 1.2f) return true // 들여쓰기
        }
        return false
    }

    private fun groupParagraphs(lines: List<RawLine>, bodySize: Float, pno: Int): List<Pos> {
        if (lines.isEmpty()) return emptyList()
        val left = lines.minOf { it.x0 }
        val right = lines.maxOf { it.x1 }
        val width = max(right - left, 1f)
        val groups = ArrayList<MutableList<RawLine>>()
        var prev: RawLine? = null
        for (l in lines) {
            if (prev == null || breaksParagraph(prev, l, left, width)) groups.add(mutableListOf(l))
            else groups.last().add(l)
            prev = l
        }
        val out = ArrayList<Pos>()
        for (g in groups) {
            val text = TextUtil.joinLines(g.map { it.text })
            if (text.isBlank()) continue
            val avg = (g.sumOf { it.size.toDouble() } / g.size).toFloat()
            val allBold = g.all { it.bold }
            val y = g.first().y - g.first().size
            val item = when {
                g.size <= 3 && text.length <= 150 && avg >= bodySize * 1.18f ->
                    Item(Kind.HEADING, text, level = 0, size = avg, page = pno)
                g.size <= 3 && allBold && text.length <= 60 && !TextUtil.endsSentence(text) && avg >= bodySize * 0.95f ->
                    Item(Kind.HEADING, text, level = 9, size = avg, page = pno)
                else -> Item(Kind.PARAGRAPH, text, size = avg, page = pno)
            }
            out.add(Pos(y, item))
        }
        return out
    }

    /** 종결부호 없이 끝난 문단을 다음 문단과 이어 붙인다 (쪽·블록 경계 대응). */
    private fun mergeParagraphs(items: List<Item>): MutableList<Item> {
        val out = ArrayList<Item>()
        for (it in items) {
            val prev = out.lastOrNull()
            if (prev != null && prev.kind == Kind.PARAGRAPH && it.kind == Kind.PARAGRAPH &&
                !TextUtil.endsSentence(prev.text) && abs(prev.size - it.size) < 0.6f &&
                !TextUtil.isListStart(it.text) && prev.text.length > 20
            ) {
                prev.text = TextUtil.joinLines(listOf(prev.text, it.text))
                continue
            }
            out.add(it)
        }
        return out
    }
}
