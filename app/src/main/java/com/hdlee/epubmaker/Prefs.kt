package com.hdlee.epubmaker

import android.content.Context

/** 사용자 설정 저장 (SharedPreferences) */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("epubmaker_prefs", Context.MODE_PRIVATE)

    var autoCheckUpdate: Boolean
        get() = sp.getBoolean("auto_check_update", true)
        set(v) = sp.edit().putBoolean("auto_check_update", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    private inline fun <reified E : Enum<E>> enumOr(key: String, default: E): E =
        runCatching { enumValueOf<E>(sp.getString(key, default.name) ?: default.name) }.getOrDefault(default)

    fun loadSettings(): EpubSettings {
        val d = EpubSettings()
        return EpubSettings(
            fontSizePt = sp.getInt("font_size", d.fontSizePt),
            lineHeight = sp.getFloat("line_height", d.lineHeight),
            marginPct = sp.getInt("margin_pct", d.marginPct),
            font = enumOr("font", d.font),
            align = enumOr("align", d.align),
            paragraph = enumOr("paragraph", d.paragraph),
            useCover = sp.getBoolean("use_cover", d.useCover),
        )
    }

    fun saveSettings(s: EpubSettings) {
        sp.edit()
            .putInt("font_size", s.fontSizePt)
            .putFloat("line_height", s.lineHeight)
            .putInt("margin_pct", s.marginPct)
            .putString("font", s.font.name)
            .putString("align", s.align.name)
            .putString("paragraph", s.paragraph.name)
            .putBoolean("use_cover", s.useCover)
            .apply()
    }

    fun loadExtractOptions(): ExtractOptions {
        val d = ExtractOptions()
        return ExtractOptions(
            removeHeadersFooters = sp.getBoolean("remove_hf", d.removeHeadersFooters),
            mergeParagraphs = sp.getBoolean("merge_paragraphs", d.mergeParagraphs),
            includeImages = sp.getBoolean("include_images", d.includeImages),
            tablesAsImages = sp.getBoolean("tables_as_images", d.tablesAsImages),
            tableStrictness = enumOr("table_strictness", d.tableStrictness),
        )
    }

    fun saveExtractOptions(o: ExtractOptions) {
        sp.edit()
            .putBoolean("remove_hf", o.removeHeadersFooters)
            .putBoolean("merge_paragraphs", o.mergeParagraphs)
            .putBoolean("include_images", o.includeImages)
            .putBoolean("tables_as_images", o.tablesAsImages)
            .putString("table_strictness", o.tableStrictness.name)
            .apply()
    }
}
