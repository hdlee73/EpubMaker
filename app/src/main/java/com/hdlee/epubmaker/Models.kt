package com.hdlee.epubmaker

/** 앱 정보 (이름/만든이/저장소). 버전은 BuildConfig.VERSION_NAME 을 사용합니다. */
object AppInfo {
    const val NAME = "Epubmaker"
    const val AUTHOR = "이현덕"
    const val EMAIL = "hdlee73@gmail.com"
    const val GITHUB_OWNER = "hdlee73"
    const val GITHUB_REPO = "EpubMaker"
    const val GITHUB_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
}

/** 앱 안에 표시되는 업데이트(변경) 내역. 새 버전을 낼 때 맨 위에 추가합니다. */
object Changelog {
    val entries: List<Pair<String, List<String>>> = listOf(
        "1.0.0" to listOf(
            "최초 릴리스 (안드로이드)",
            "PDF 텍스트 추출: 머리글/바닥글 제거, 끊긴 문단 이어 붙이기, 제목 감지",
            "표는 이미지로 캡처, 그림은 이미지로 추출하여 EPUB에 삽입",
            "글자 크기·줄 간격·여백·글꼴·정렬 지정 (미리보기 제공)",
            "뒤로가기/제스처는 앱 종료가 아니라 이전 화면으로 이동",
            "GitHub 릴리스 기반 자동 업데이트 확인 (서명 검증)",
        ),
    )
}

enum class FontChoice(val label: String, val css: String?) {
    SERIF("명조 (세리프)", "'Noto Serif KR', 'Nanum Myeongjo', 'Batang', serif"),
    SANS("고딕 (산세리프)", "'Noto Sans KR', 'Nanum Gothic', 'Malgun Gothic', sans-serif"),
    READER("리더 기본 글꼴", null),
}

enum class AlignChoice(val label: String, val css: String) {
    LEFT("왼쪽 정렬", "left"),
    JUSTIFY("양쪽 정렬", "justify"),
}

enum class ParagraphChoice(val label: String) {
    SPACE("문단 간격"),
    INDENT("첫 줄 들여쓰기"),
}

/** EPUB에 반영되는 읽기 서식 */
data class EpubSettings(
    val fontSizePt: Int = 14,
    val lineHeight: Float = 1.7f,
    val marginPct: Int = 5,
    val font: FontChoice = FontChoice.SERIF,
    val align: AlignChoice = AlignChoice.LEFT,
    val paragraph: ParagraphChoice = ParagraphChoice.SPACE,
    val chapterPages: Int = 10,
    val useCover: Boolean = false,
)

/** PDF 추출 옵션 */
data class ExtractOptions(
    val removeHeadersFooters: Boolean = true,
    val mergeParagraphs: Boolean = true,
    val includeImages: Boolean = true,
    val tablesAsImages: Boolean = true,
    val minImagePt: Float = 40f,
    val makeCover: Boolean = false,
)

/** 사용자에게 그대로 보여줄 수 있는 변환 오류 */
class ConvertException(message: String) : Exception(message)

enum class Kind { PARAGRAPH, HEADING, IMAGE, TABLE }

/** 추출 결과의 한 항목 (문단·제목·그림·표 이미지) */
class Item(
    val kind: Kind,
    var text: String = "",
    var level: Int = 0,
    val size: Float = 0f,
    val data: ByteArray? = null,
    val ext: String = "png",
    val page: Int = 0,
)

class ExtractResult(
    val items: MutableList<Item>,
    val title: String,
    val author: String,
    val language: String,
    val pageCount: Int,
    val cover: ByteArray?,
    val warnings: List<String>,
)

data class ConvertStats(val chapters: Int, val paragraphs: Int, val images: Int, val tables: Int)
