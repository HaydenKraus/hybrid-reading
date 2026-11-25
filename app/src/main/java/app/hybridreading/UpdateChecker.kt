package app.hybridreading

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка обновлений через GitHub Releases и загрузка APK.
 *
 * Репозиторий публичный, аутентификация не нужна. Сравниваем versionName из последнего релиза
 * с установленной версией; если там новее — предлагаем скачать и поставить.
 */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/HaydenKraus/hybrid-reading/releases/latest"
    private const val APK_NAME = "hybrid-reading-update.apk"

    data class Release(val version: String, val tag: String, val apkUrl: String, val notes: String)

    sealed interface Check {
        data class Newer(val release: Release) : Check

        data class Actual(val version: String) : Check

        data class Failed(val message: String) : Check
    }

    fun checkInstalledVersion(): String = BuildConfig.VERSION_NAME

    /** Сетевой вызов: дергать из фонового потока. */
    fun check(): Check {
        val connection =
            (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "HybridReading/${BuildConfig.VERSION_NAME}")
            }
        return try {
            if (connection.responseCode != 200) {
                return Check.Failed("GitHub ответил кодом ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").ifBlank { return Check.Failed("В ответе нет тега релиза") }
            val version = tag.removePrefix("v").trim()
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) return Check.Failed("В релизе нет APK")
            val release = Release(version, tag, apkUrl, json.optString("body").take(600))
            if (isNewer(
                    version,
                    BuildConfig.VERSION_NAME,
                )
            ) {
                Check.Newer(release)
            } else {
                Check.Actual(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            Check.Failed(e.message ?: "Не удалось связаться с GitHub")
        } finally {
            connection.disconnect()
        }
    }

    /** Сравнение версий по числовым частям: 1.9.0 < 1.10.0. */
    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        fun parts(value: String) =
            value.split('.', '-', '+')
                .map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    /** Скачивает APK во внутренний кэш и отдаёт файл для установки. */
    fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
    ): File {
        val target = File(context.cacheDir, APK_NAME)
        if (target.exists()) target.delete()
        val connection =
            (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "HybridReading/${BuildConfig.VERSION_NAME}")
            }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Не удалось скачать APK (код ${connection.responseCode})")
            }
            val total = connection.contentLength.toLong()
            var read = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) onProgress(((read * 100) / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (target.length() < 100_000) throw IllegalStateException("Файл обновления скачался не полностью")
        return target
    }

    /** Открывает системный установщик для скачанного APK. */
    fun install(
        context: Context,
        apk: File,
    ) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }
}
