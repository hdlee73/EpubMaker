@file:OptIn(ExperimentalMaterial3Api::class)

package com.hdlee.epubmaker.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hdlee.epubmaker.AlignChoice
import com.hdlee.epubmaker.AppInfo
import com.hdlee.epubmaker.BuildConfig
import com.hdlee.epubmaker.Changelog
import com.hdlee.epubmaker.FontChoice
import com.hdlee.epubmaker.MainViewModel
import com.hdlee.epubmaker.ParagraphChoice
import com.hdlee.epubmaker.Screen
import com.hdlee.epubmaker.TableStrictness
import kotlin.math.roundToInt

/** 앱 전체 화면. 뒤로가기/제스처는 앱 종료가 아니라 "이전 화면"으로 이동한다. */
@Composable
fun EpubmakerApp(vm: MainViewModel, onPickPdf: () -> Unit, onConvert: () -> Unit) {
    // 홈 화면이 아닐 때만 가로채서 이전 화면으로. 홈에서는 시스템 기본 동작(앱 종료).
    BackHandler(enabled = vm.stack.size > 1) { vm.pop() }

    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (vm.current) {
                            Screen.HOME -> AppInfo.NAME
                            Screen.FORMAT -> "읽기 서식"
                            Screen.ABOUT -> "정보 · 업데이트"
                        },
                    )
                },
                navigationIcon = {
                    if (vm.stack.size > 1) {
                        IconButton(onClick = { vm.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
                actions = {
                    if (vm.current == Screen.HOME) {
                        IconButton(onClick = { vm.checkUpdate(silent = false) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "업데이트 확인")
                        }
                        IconButton(onClick = { vm.push(Screen.ABOUT) }) {
                            Icon(Icons.Filled.Info, contentDescription = "정보")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (vm.current) {
                Screen.HOME -> HomeScreen(vm, onPickPdf, onConvert, ctx)
                Screen.FORMAT -> FormatScreen(vm)
                Screen.ABOUT -> AboutScreen(vm, ctx)
            }
        }
    }

    vm.updateRelease?.let { rel ->
        AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("새 버전 ${rel.tag}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("현재 버전: v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(rel.notes.ifBlank { "업데이트 내용이 없습니다." })
                }
            },
            confirmButton = { TextButton(onClick = { vm.startUpdate() }) { Text("업데이트") } },
            dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("나중에") } },
        )
    }
}

// ============================================================ 홈
@Composable
private fun HomeScreen(vm: MainViewModel, onPickPdf: () -> Unit, onConvert: () -> Unit, ctx: Context) {
    vm.updateMessage?.let {
        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }

    SectionCard("PDF 파일") {
        Button(onClick = onPickPdf, enabled = !vm.running) { Text(if (vm.pdfUri == null) "PDF 선택" else "다른 PDF 선택") }
        if (vm.pdfName.isNotEmpty()) Text(vm.pdfName, style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = vm.title, onValueChange = { vm.title = it },
            label = { Text("책 제목") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.author, onValueChange = { vm.author = it },
            label = { Text("저자 (비우면 PDF 정보 사용)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
    }

    SectionCard("읽기 서식") {
        val s = vm.settings
        Text("${s.fontSizePt}pt · 줄 간격 ${"%.1f".format(s.lineHeight)} · 좌우 여백 ${s.marginPct}% · ${s.font.label}")
        OutlinedButton(onClick = { vm.push(Screen.FORMAT) }) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text("서식 설정 · 미리보기")
        }
    }

    SectionCard("변환 옵션") {
        val o = vm.options
        SwitchRow("머리글/바닥글·쪽번호 제거", o.removeHeadersFooters) { vm.updateOptions(o.copy(removeHeadersFooters = it)) }
        SwitchRow("끊긴 문단 이어 붙이기", o.mergeParagraphs) { vm.updateOptions(o.copy(mergeParagraphs = it)) }
        SwitchRow("그림 포함", o.includeImages) { vm.updateOptions(o.copy(includeImages = it)) }
        SwitchRow("표는 이미지로 캡처해서 삽입", o.tablesAsImages) { vm.updateOptions(o.copy(tablesAsImages = it)) }
        if (o.tablesAsImages) {
            ChipRow("표 인식 강도", TableStrictness.entries, o.tableStrictness, { it.label }) {
                vm.updateOptions(o.copy(tableStrictness = it))
            }
            Text(
                if (o.tableStrictness == TableStrictness.STRICT) "엄격: 표가 아닌 것을 표로 잘못 인식하는 일을 줄입니다. 복잡한 표는 글자로 남을 수 있습니다."
                else "보통: 더 많이 표로 인식합니다. 표가 아닌 부분이 이미지로 바뀔 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SwitchRow("첫 페이지를 표지로 사용", vm.settings.useCover) { vm.updateSettings(vm.settings.copy(useCover = it)) }
    }

    if (vm.running) {
        OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("취소") }
    } else {
        Button(onClick = onConvert, enabled = vm.pdfUri != null, modifier = Modifier.fillMaxWidth()) { Text("EPUB로 변환") }
    }
    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
    Text(vm.status, style = MaterialTheme.typography.bodyMedium)

    vm.result?.let { r ->
        SectionCard("변환 완료") {
            Text("장 ${r.stats.chapters}개 · 문단 ${r.stats.paragraphs}개 · 그림 ${r.stats.images}개 · 표(이미지) ${r.stats.tables}개")
            r.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openEpub(ctx, r.outUri) }) { Text("열기") }
                OutlinedButton(onClick = { shareEpub(ctx, r.outUri) }) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("공유")
                }
            }
        }
    }

    if (vm.log.isNotEmpty()) {
        SectionCard("기록") {
            vm.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ============================================================ 서식 (미리보기 포함)
private const val PREVIEW_1 = "가나다라마바사 아자차카타파하. 글자 크기, 줄 간격, 여백이 이렇게 보입니다. 설정을 바꾸면 이 미리보기가 바로 달라집니다."
private const val PREVIEW_2 = "The quick brown fox jumps over the lazy dog. 두 번째 문단입니다."

@Composable
private fun FormatScreen(vm: MainViewModel) {
    val s = vm.settings

    SectionCard("미리보기") {
        val family = when (s.font) {
            FontChoice.SERIF -> FontFamily.Serif
            FontChoice.SANS -> FontFamily.SansSerif
            FontChoice.READER -> FontFamily.Default
        }
        val style = TextStyle(
            fontFamily = family,
            fontSize = s.fontSizePt.sp,
            lineHeight = (s.fontSizePt * s.lineHeight).sp,
            textAlign = if (s.align == AlignChoice.JUSTIFY) TextAlign.Justify else TextAlign.Start,
            textIndent = if (s.paragraph == ParagraphChoice.INDENT) TextIndent(firstLine = s.fontSizePt.sp) else null,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(vertical = 10.dp, horizontal = (s.marginPct * 2).dp),
        ) {
            Text(PREVIEW_1, style = style)
            Spacer(Modifier.height(if (s.paragraph == ParagraphChoice.SPACE) (s.fontSizePt * 0.8f).dp else 0.dp))
            Text(PREVIEW_2, style = style)
        }
        Text("실제 EPUB 리더 앱에서도 이 값을 다시 바꿀 수 있습니다.", style = MaterialTheme.typography.bodySmall)
    }

    SectionCard("크기와 간격") {
        LabeledSlider("글자 크기", "${s.fontSizePt} pt", s.fontSizePt.toFloat(), 10f..32f, 21) {
            vm.updateSettings(s.copy(fontSizePt = it.roundToInt()))
        }
        LabeledSlider("줄 간격", "${"%.1f".format(s.lineHeight)} 배", s.lineHeight, 1.0f..2.8f, 17) {
            vm.updateSettings(s.copy(lineHeight = (it * 10).roundToInt() / 10f))
        }
        LabeledSlider("좌우 여백", "${s.marginPct} %", s.marginPct.toFloat(), 0f..20f, 19) {
            vm.updateSettings(s.copy(marginPct = it.roundToInt()))
        }
    }

    SectionCard("글꼴 · 정렬 · 문단") {
        ChipRow("글꼴", FontChoice.entries, s.font, { it.label }) { vm.updateSettings(s.copy(font = it)) }
        ChipRow("정렬", AlignChoice.entries, s.align, { it.label }) { vm.updateSettings(s.copy(align = it)) }
        ChipRow("문단 구분", ParagraphChoice.entries, s.paragraph, { it.label }) { vm.updateSettings(s.copy(paragraph = it)) }
    }

    SectionCard("장(챕터) 나누기") {
        Text("제목을 인식해서 장을 나눕니다. 제목을 찾지 못하면 나누지 않고 한 권으로 만듭니다.", style = MaterialTheme.typography.bodyMedium)
    }

    OutlinedButton(onClick = { vm.resetSettings() }, modifier = Modifier.fillMaxWidth()) { Text("기본값으로 되돌리기") }
}

// ============================================================ 정보 · 업데이트
@Composable
private fun AboutScreen(vm: MainViewModel, ctx: Context) {
    SectionCard(AppInfo.NAME) {
        Text("버전 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text("만든이: ${AppInfo.AUTHOR} (${AppInfo.EMAIL})")
        OutlinedButton(onClick = { openUrl(ctx, AppInfo.GITHUB_URL) }) { Text("GitHub 저장소 열기") }
    }

    SectionCard("업데이트") {
        SwitchRow("앱을 열 때 업데이트 자동 확인", vm.autoCheck) { vm.changeAutoCheck(it) }
        Button(onClick = { vm.checkUpdate(silent = false) }, enabled = !vm.updateBusy) { Text("지금 업데이트 확인") }
        vm.updateMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            "업데이트는 공식 GitHub 릴리스에서만 받으며, 서명이 확인된 파일만 설치합니다.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    SectionCard("업데이트 정보") {
        Changelog.entries.forEach { (ver, notes) ->
            Text("v$ver", fontWeight = FontWeight.Bold)
            notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ============================================================ 공통 부품
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueText, fontWeight = FontWeight.Bold)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun <T> ChipRow(label: String, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { opt ->
                FilterChip(selected = opt == selected, onClick = { onSelect(opt) }, label = { Text(text(opt)) })
            }
        }
    }
}

// ============================================================ 외부 앱 연동
private fun openEpub(ctx: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/epub+zip")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "EPUB을 열 수 있는 앱이 없습니다. 파일은 저장되어 있습니다.", Toast.LENGTH_LONG).show()
    }
}

private fun shareEpub(ctx: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("application/epub+zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "EPUB 공유"))
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "브라우저를 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
}
