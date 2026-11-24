package app.hybridreading

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * Блокировка рекламных сетей и трекеров на уровне WebView.
 *
 * [shouldBlock] — чистая функция (никакого Android API), её гоняют unit-тесты на JVM.
 */
object AdBlocker {

    /** Хосты, которые нельзя гасить ни при каких условиях: сам сайт, вход, CDN со скриптами. */
    private val allowHosts = setOf(
        "oauth.telegram.org",
        "accounts.google.com",
        "oauth.vk.com",
        "login.vk.com",
        "id.vk.com",
        "discord.com",
        "appleid.apple.com",
        "id.yandex.ru",
        "passport.yandex.ru",
        "gstatic.com",
        "fonts.gstatic.com",
        "googleapis.com",
        "cloudflare.com",
        "jsdelivr.net",
        "unpkg.com",
        "cdnjs.cloudflare.com",
        "githubusercontent.com",
    )

    /** Рекламные сети и счётчики: совпадение по хосту и любому поддомену. */
    private val adHosts = setOf(
        // Яндекс
        "an.yandex.ru",
        "ads.yandex.ru",
        "adfox.yandex.ru",
        "adfstat.yandex.ru",
        "mc.yandex.ru",
        // Google
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "google-analytics.com",
        "analytics.google.com",
        "googletagmanager.com",
        // VK / MyTarget / Mail.ru
        "ad.mail.ru",
        "ads.mail.ru",
        "top-fwz1.mail.ru",
        "mytarget.com",
        "ads.vk.com",
        // Прочие сети
        "adriver.ru",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "amazon-adsystem.com",
        "adnxs.com",
        "smartadserver.com",
        "flurry.com",
        "scorecardresearch.com",
        "quantserve.com",
        "moatads.com",
        "teads.tv",
    )

    /** Домены, где реклама живёт только на части путей: сам сайт не трогаем. */
    private val pathHosts = setOf("yandex.ru")

    /** appsflyer — трекер атрибуции; гасим только его рекламные эндпоинты. */
    private val trackerHosts = setOf("appsflyer.com")

    private val adPaths = listOf("/ads/", "/adv/", "/adserver/", "/pagead/", "/banners/", "/advert")

    /**
     * yandex.net целиком гасить нельзя: под ним лежат картинки (avatars.yandex.net) и файлы.
     * Блокируем только служебные поддомены рекламной сети.
     */
    private val yandexNetAdLabels = setOf("an", "ads", "adfox", "mc", "ad", "adfstat", "awaps")

    private val trackerPaths = listOf("/ad/", "/ads/", "/impression", "/click", "/adx")

    fun shouldBlock(url: String, siteHost: String? = null): Boolean {
        if (url.isBlank()) return false
        // URI падает на мусоре (пробелы, неэкранированные символы) — такой запрос не наш.
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.isEmpty()) return false

        // Запросы самого сайта и его поддоменов (CDN, картинки глав, офлайн-кэш) не трогаем:
        // иначе сайт не сможет скачать главы, а сервис-воркер отдаст битые файлы.
        if (siteHost != null) {
            val own = siteHost.lowercase().removePrefix("www.")
            if (host == own || host.endsWith(".$own")) return false
        }

        // Белый список главнее всего: сломать вход или CDN дороже, чем пропустить баннер.
        if (matches(host, allowHosts)) return false

        if (matches(host, adHosts)) return true
        if (host.endsWith("yandex.net") && host.substringBefore('.') in yandexNetAdLabels) return true

        val path = uri.path.orEmpty().lowercase()
        if (matches(host, pathHosts) && adPaths.any { path.contains(it) }) return true
        if (matches(host, trackerHosts) && trackerPaths.any { path.contains(it) }) return true

        return false
    }

    /** MIME рекламного запроса по расширению: пустое тело должно быть «того же типа». */
    fun mimeFor(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".ico") -> "image/x-icon"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
            path.endsWith(".ttf") -> "font/ttf"
            path.endsWith(".otf") -> "font/otf"
            path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            else -> "text/javascript"
        }
    }

    /** Пустой ответ: тело нулевой длины, чтобы страница не ждала загрузку рекламы. */
    fun emptyResponse(mime: String = "text/javascript"): WebResourceResponse =
        WebResourceResponse(
            mime.ifBlank { "text/javascript" },
            "utf-8",
            200,
            "OK",
            // Без CORS-заголовка fetch/XHR к заблокированному адресу валит JS сайта необработанным reject'ом.
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )

    private fun matches(host: String, list: Set<String>): Boolean =
        list.any { host == it || host.endsWith(".$it") }
}
