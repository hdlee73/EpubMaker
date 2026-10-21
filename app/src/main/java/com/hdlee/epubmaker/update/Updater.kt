package com.hdlee.epubmaker.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hdlee.epubmaker.AppInfo
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class UpdateException(message: String) : Exception(message)

class Release(
    val tag: String,
    val version: List<Int>,
    val notes: String,
    val htmlUrl: String,
    val assets: Map<String, String>, // 파일명 -> 다운로드 URL
)

/**
 * GitHub Releases 기반 업데이트 + 서명(ECDSA P-256 / SHA-256) 검증.
 *
 * 보안 원칙
 *  - 업데이트 파일은 이 저장소(hdlee73/EpubMaker)의 릴리스에서만 받는다.
 *  - 릴리스 APK마다 `<파일명>.sig` 서명이 있어야 하고, 앱에 내장된 공개키로 검증에 성공해야만 설치 화면을 연다.
 *  - 서명용 비공개키와 APK 서명 키스토어는 저장소가 아니라 GitHub Secrets 에만 보관한다.
 *  - 그리고 안드로이드 자체가 "같은 키로 서명된 APK만" 기존 앱을 업데이트하도록 강제한다.
 *  - 공개키가 비어 있으면(키 생성 전) 자동 설치는 하지 않고 릴리스 페이지만 연다 (안전하게 실패).
 */
object Updater {
    /** tools/gen_keys.py 로 만든 공개키(Base64, X.509 SubjectPublicKeyInfo). 공개되어도 안전하다. */
    const val PUBLIC_KEY_B64 = ""

    private const val API_LATEST =
        "https://api.github.com/repos/${AppInfo.GITHUB_OWNER}/${AppInfo.GITHUB_REPO}/releases/latest"
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun parseVersion(text: String): List<Int> =
        Regex("\\d+").findAll(text).map { it.value.toInt() }.take(4).toList().ifEmpty { listOf(0) }

    fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun isNewer(release: Release, currentVersion: String): Boolean =
        compareVersions(release.version, parseVersion(currentVersion)) > 0

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val u = URL(url)
        if (u.protocol != "https" || u.host !in ALLOWED_HOSTS) {
            throw UpdateException("허용되지 않은 주소입니다: ${u.host}")
        }
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "${AppInfo.NAME}-Android")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        }
    }

    /** 최신 릴리스 정보. 릴리스가 없거나 네트워크 오류면 null. (백그라운드 스레드에서 호출) */
    fun fetchLatest(): Release? = try {
        val conn = open(API_LATEST, 8000)
        try {
            if (conn.responseCode != 200) null
            else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name")
                if (tag.isEmpty()) null
                else {
                    val assets = LinkedHashMap<String, String>()
                    val arr = json.optJSONArray("assets")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            val name = a.optString("name")
                            val url = a.optString("browser_download_url")
                            if (name.isNotEmpty() && url.isNotEmpty()) assets[name] = url
                        }
                    }
                    Release(
                        tag = tag,
                        version = parseVersion(tag),
                        notes = json.optString("body").trim(),
                        htmlUrl = json.optString("html_url").ifEmpty { AppInfo.GITHUB_URL + "/releases" },
                        assets = assets,
                    )
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    fun pickApk(release: Release): String? = release.assets.keys.firstOrNull { it.endsWith(".apk", ignoreCase = true) }

    fun verifySignature(file: File, signature: ByteArray, publicKeyB64: String = PUBLIC_KEY_B64) {
        if (publicKeyB64.isEmpty()) {
            throw UpdateException("업데이트 서명용 공개키가 아직 설정되지 않아 자동 설치를 할 수 없습니다.")
        }
        val ok = try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            file.inputStream().use { ins ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    sig.update(buf, 0, n)
                }
            }
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
        if (!ok) throw UpdateException("서명 검증에 실패했습니다. 변조되었거나 공식 배포본이 아닐 수 있어 설치하지 않습니다.")
    }

    /** APK와 서명을 내려받아 검증까지 마친 파일을 돌려준다. (백그라운드 스레드에서 호출) */
    fun downloadAndVerify(
        context: Context,
        release: Release,
        apkName: String,
        progress: (Long, Long) -> Unit,
    ): File {
        val sigName = "$apkName.sig"
        val apkUrl = release.assets[apkName] ?: throw UpdateException("릴리스에 APK 파일이 없습니다.")
        val sigUrl = release.assets[sigName] ?: throw UpdateException("릴리스에 서명 파일(.sig)이 없어 설치하지 않습니다.")

        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val dest = File(dir, apkName.substringAfterLast('/'))
        val conn = open(apkUrl, 60000)
        try {
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { ins ->
                dest.outputStream().use { outs ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val sigConn = open(sigUrl, 30000)
        val sig = try {
            sigConn.inputStream.use { it.readBytes() }
        } finally {
            sigConn.disconnect()
        }
        if (sig.isEmpty() || sig.size > 4096) throw UpdateException("서명 파일이 올바르지 않습니다.")
        try {
            verifySignature(dest, sig)
        } catch (e: UpdateException) {
            dest.delete()
            throw e
        }
        return dest
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
