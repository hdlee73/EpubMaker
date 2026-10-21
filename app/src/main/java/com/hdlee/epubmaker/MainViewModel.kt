package com.hdlee.epubmaker

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hdlee.epubmaker.update.Release
import com.hdlee.epubmaker.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { HOME, FORMAT, ABOUT }

class ConvertResult(val outUri: Uri, val stats: ConvertStats, val warnings: List<String>)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val converter = Converter(app)

    // ---- 화면 이동: 뒤로가기는 앱 종료가 아니라 이전 화면으로 ----
    val stack = mutableStateListOf(Screen.HOME)
    val current: Screen get() = stack.last()

    fun push(s: Screen) {
        if (stack.last() != s) stack.add(s)
    }

    /** 이전 화면으로 돌아간다. 더 돌아갈 곳이 없으면 false (시스템이 앱을 닫음). */
    fun pop(): Boolean {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        return false
    }

    // ---- 입력 상태 ----
    var pdfUri by mutableStateOf<Uri?>(null)
        private set
    var pdfName by mutableStateOf("")
        private set
    var title by mutableStateOf("")
    var author by mutableStateOf("")
    var settings by mutableStateOf(prefs.loadSettings())
        private set
    var options by mutableStateOf(prefs.loadExtractOptions())
        private set

    // ---- 변환 상태 ----
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var status by mutableStateOf("PDF 파일을 선택하세요.")
        private set
    val log = mutableStateListOf<String>()
    var result by mutableStateOf<ConvertResult?>(null)
        private set
    private var job: Job? = null

    // ---- 업데이트 상태 ----
    var autoCheck by mutableStateOf(prefs.autoCheckUpdate)
        private set
    var updateRelease by mutableStateOf<Release?>(null)
        private set
    var updateBusy by mutableStateOf(false)
        private set
    var updateMessage by mutableStateOf<String?>(null)
        private set

    fun onPdfPicked(uri: Uri) {
        pdfUri = uri
        pdfName = converter.displayName(uri)
        title = pdfName.removeSuffix(".pdf").removeSuffix(".PDF")
        result = null
        progress = 0f
        status = "변환할 준비가 되었습니다."
    }

    fun suggestedOutputName(): String =
        (pdfName.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "book" }) + ".epub"

    fun updateSettings(s: EpubSettings) {
        settings = s
        prefs.saveSettings(s)
    }

    fun updateOptions(o: ExtractOptions) {
        options = o
        prefs.saveExtractOptions(o)
    }

    fun resetSettings() = updateSettings(EpubSettings())

    fun startConversion(outUri: Uri) {
        val uri = pdfUri ?: return
        if (running) return
        running = true
        progress = 0f
        result = null
        log.clear()
        log.add("변환 시작: $pdfName")
        status = "변환을 시작합니다..."
        val opts = options
        val st = settings
        val bookTitle = title.ifBlank { pdfName.removeSuffix(".pdf") }
        val bookAuthor = author
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val (stats, warns) = converter.convert(
                    uri, outUri, opts, st, bookTitle, bookAuthor,
                    progress = { i, n, m ->
                        progress = if (n > 0) (i.toFloat() / n).coerceIn(0f, 1f) else 0f
                        status = m
                    },
                    checkCancel = { ensureActive() },
                )
                result = ConvertResult(outUri, stats, warns)
                progress = 1f
                status = "변환 완료"
                log.add("완료: 장 ${stats.chapters}개, 문단 ${stats.paragraphs}개, 그림 ${stats.images}개, 표(이미지) ${stats.tables}개")
                warns.forEach { log.add("⚠ $it") }
            } catch (e: CancellationException) {
                status = "취소되었습니다."
                log.add("사용자가 취소했습니다.")
                progress = 0f
            } catch (e: ConvertException) {
                status = "변환하지 못했습니다."
                log.add("오류: ${e.message}")
            } catch (e: OutOfMemoryError) {
                status = "메모리가 부족합니다."
                log.add("오류: 메모리가 부족합니다. PDF가 너무 크면 나누어 변환해 주세요.")
            } catch (e: Exception) {
                status = "오류가 발생했습니다."
                log.add("오류: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                running = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
        status = "취소하는 중..."
    }

    // ------------------------------------------------------------ 업데이트
    fun setAutoCheck(v: Boolean) {
        autoCheck = v
        prefs.autoCheckUpdate = v
    }

    /** 앱을 열 때 한 번(6시간 이내 재확인 안 함) 조용히 확인 */
    fun autoCheckOnStart() {
        if (autoCheck && System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 60 * 60 * 1000L) {
            checkUpdate(silent = true)
        }
    }

    fun checkUpdate(silent: Boolean) {
        if (updateBusy) return
        updateBusy = true
        if (!silent) updateMessage = "업데이트를 확인하는 중..."
        viewModelScope.launch(Dispatchers.IO) {
            val rel = Updater.fetchLatest()
            withContext(Dispatchers.Main) {
                updateBusy = false
                prefs.lastUpdateCheck = System.currentTimeMillis()
                when {
                    rel == null ->
                        updateMessage = if (silent) null else "업데이트 정보를 가져오지 못했습니다. (네트워크 연결 또는 릴리스가 아직 없을 수 있습니다.)"
                    !Updater.isNewer(rel, BuildConfig.VERSION_NAME) ->
                        updateMessage = if (silent) null else "현재 최신 버전입니다. (v${BuildConfig.VERSION_NAME})"
                    else -> {
                        updateRelease = rel
                        updateMessage = null
                    }
                }
            }
        }
    }

    fun dismissUpdate() {
        updateRelease = null
    }

    fun startUpdate() {
        val rel = updateRelease ?: return
        updateRelease = null
        val ctx = getApplication<Application>()
        val apk = Updater.pickApk(rel)
        if (apk == null || Updater.PUBLIC_KEY_B64.isEmpty()) {
            // 서명 검증을 할 수 없으면 자동 설치 대신 릴리스 페이지를 연다.
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(rel.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            updateMessage = "릴리스 페이지에서 직접 내려받아 설치해 주세요."
            return
        }
        updateBusy = true
        updateMessage = "업데이트를 내려받는 중..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = Updater.downloadAndVerify(ctx, rel, apk) { done, total ->
                    updateMessage = if (total > 0) "내려받는 중... ${done * 100 / total}%" else "내려받는 중..."
                }
                withContext(Dispatchers.Main) {
                    updateBusy = false
                    launchInstall(file)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateBusy = false
                    updateMessage = "업데이트 실패: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    private fun launchInstall(file: File) {
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            updateMessage = "서명 검증을 마쳤습니다. '이 앱에서 설치 허용'을 켠 뒤 '업데이트 확인'을 다시 눌러 주세요."
            ctx.startActivity(Updater.unknownSourcesSettingsIntent(ctx))
            return
        }
        updateMessage = "서명 검증 완료. 설치 화면을 엽니다."
        ctx.startActivity(Updater.installIntent(ctx, file))
    }
}
