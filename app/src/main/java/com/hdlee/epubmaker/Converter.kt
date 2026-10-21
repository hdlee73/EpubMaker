package com.hdlee.epubmaker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hdlee.epubmaker.epub.EpubWriter
import com.hdlee.epubmaker.pdf.PdfExtractor
import java.io.BufferedOutputStream
import java.io.File

/** PDF(Uri) → EPUB(Uri) 변환. 백그라운드 스레드에서 호출해야 한다. */
class Converter(private val context: Context) {

    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    fun convert(
        pdfUri: Uri,
        outUri: Uri,
        extractOpts: ExtractOptions,
        settings: EpubSettings,
        title: String,
        author: String,
        progress: (Int, Int, String) -> Unit,
        checkCancel: () -> Unit,
    ): Pair<ConvertStats, List<String>> {
        val tmp = File(context.cacheDir, "input.pdf")
        try {
            progress(0, 1, "PDF 불러오는 중")
            context.contentResolver.openInputStream(pdfUri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw ConvertException("PDF 파일을 열 수 없습니다.")

            val result = PdfExtractor().extract(
                tmp,
                extractOpts.copy(makeCover = settings.useCover),
                progress,
                checkCancel,
            )
            checkCancel()
            if (result.items.isEmpty()) {
                throw ConvertException("변환할 내용이 없습니다. (텍스트·그림·표를 찾지 못했습니다)")
            }
            progress(result.pageCount, result.pageCount, "EPUB 파일 만드는 중")
            val stats = context.contentResolver.openOutputStream(outUri, "wt")?.let { os ->
                BufferedOutputStream(os).use { EpubWriter.write(it, result, settings, title, author) }
            } ?: throw ConvertException("저장 위치에 파일을 쓸 수 없습니다.")
            return stats to result.warnings
        } finally {
            tmp.delete()
        }
    }
}
