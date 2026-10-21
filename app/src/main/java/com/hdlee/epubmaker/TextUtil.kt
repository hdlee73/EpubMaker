package com.hdlee.epubmaker

/** 텍스트 후처리 유틸 (안드로이드 의존성 없음 → 단위 테스트 가능) */
object TextUtil {
    val TERMINALS = setOf('.', '!', '?', '。', '？', '！', '…', '"', '”', '’', '\'', ')', '）', '」', '』')
    private val LIST_START = Regex("""^\s*(?:\d+[.)]|\(\d+\)|[•·▪■◦○●\-–—]|[가-힣]\.|[a-zA-Z][.)])\s""")
    private val PAGE_NO = Regex(
        """^\s*[-–—\s]*(?:page|p\.|쪽|페이지)?\s*\d+\s*(?:/\s*\d+)?\s*(?:쪽|페이지)?[-–—\s]*$""",
        RegexOption.IGNORE_CASE,
    )
    private val DIGITS = Regex("""\d+""")

    fun isHangul(c: Char) = c in '가'..'힯' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'

    /** 한자·가나 (띄어쓰기 없이 이어 붙임) */
    fun isIdeographic(c: Char) = c in '぀'..'ヿ' || c in '㐀'..'鿿'

    fun endsSentence(text: String): Boolean = text.isNotEmpty() && text.last() in TERMINALS

    fun isListStart(text: String) = LIST_START.containsMatchIn(text)

    fun isPageNumber(text: String) = PAGE_NO.matches(text)

    /** 머리글/바닥글 반복 판정용 키: 숫자를 # 로 바꾸고 80자까지 */
    fun normKey(text: String): String = text.trim().lowercase().replace(DIGITS, "#").take(80)

    /** 줄바꿈으로 나뉜 조각을 자연스럽게 잇는다 (영문 하이픈 복원, 한글은 공백, 한자/가나는 붙임). */
    fun joinLines(parts: List<String>): String {
        val out = StringBuilder()
        for (raw in parts) {
            val ln = raw.trim()
            if (ln.isEmpty()) continue
            if (out.isEmpty()) {
                out.append(ln)
                continue
            }
            val a = out.last()
            val b = ln.first()
            when {
                a == '-' && out.length > 1 && out[out.length - 2].isLetter() && b.isLowerCase() -> {
                    out.setLength(out.length - 1)
                    out.append(ln)
                }
                isIdeographic(a) && isIdeographic(b) -> out.append(ln)
                else -> out.append(' ').append(ln)
            }
        }
        return out.toString().replace(Regex("[ \\t]{2,}"), " ")
    }

    fun hangulRatio(sample: String): Float {
        if (sample.isEmpty()) return 0f
        return sample.count { isHangul(it) }.toFloat() / sample.length
    }

    /** XML에서 허용되지 않는 문자를 제거하고 특수문자를 이스케이프 */
    fun xmlEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val valid = c == '\t' || c == '\n' || c == '\r' ||
                (c in ' '..'퟿') || (c in ''..'�') ||
                (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]))
            if (!valid) {
                i++
                continue
            }
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
            if (Character.isHighSurrogate(c)) {
                sb.append(s[i + 1])
                i++
            }
            i++
        }
        return sb.toString()
    }
}
