package app.hybridreading

import android.content.Context
import android.webkit.WebView

/**
 * Где пользователь остановился: адрес страницы и позиция прокрутки.
 *
 * Зачем: ночью систему может выгрузить процесс, и утром страница открылась бы с корня сайта.
 * Здесь запоминается последняя страница, чтобы продолжить чтение с того же места.
 */
object ReadingState {
    private const val PREFS = "reading_state"
    private const val KEY_URL = "url"
    private const val KEY_SCROLL = "scroll"
    private const val KEY_TIME = "time"
    private const val KEY_HOME = "was_home"
    private const val KEY_RECENT = "recent"
    private const val MAX_RECENT = 12

    /** Считаем позицию актуальной трое суток. */
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    fun save(
        context: Context,
        url: String,
        scroll: Float,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url)
            .putFloat(KEY_SCROLL, scroll)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    /** Позиция прокрутки спрашивается у страницы асинхронно, поэтому сохраняем через колбэк. */
    fun remember(
        webView: WebView,
        context: Context,
    ) {
        val url = webView.url ?: return
        if (!url.startsWith("http")) return
        val app = context.applicationContext
        webView.evaluateJavascript(
            "(function(){try{return String(Math.round(window.scrollY||0))}catch(e){return '0'}})()",
        ) { raw ->
            val scroll = raw?.trim('"')?.toFloatOrNull() ?: 0f
            save(app, url, scroll)
        }
    }

    /** Страница, на которой пользователь остановился — для списка «Продолжить чтение». */
    data class Recent(val url: String, val title: String, val siteId: String, val time: Long)

    /** Дописывает страницу в историю чтения (последние 12, без дублей по адресу). */
    fun rememberPage(
        context: Context,
        url: String,
        title: String,
        siteId: String,
    ) {
        if (!url.startsWith("http")) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(KEY_RECENT, null).orEmpty()
        val lines = current.lineSequence().filter { it.isNotBlank() }.toMutableList()
        val entry =
            listOf(url, title.replace('\t', ' ').replace('\n', ' '), siteId, System.currentTimeMillis().toString())
                .joinToString("\t")
        lines.removeAll { it.substringBefore('\t') == url }
        lines.add(0, entry)
        while (lines.size > MAX_RECENT) lines.removeAt(lines.size - 1)
        prefs.edit().putString(KEY_RECENT, lines.joinToString("\n")).apply()
    }

    /** Свежие страницы для домашней страницы, новые сверху. */
    fun recentPages(
        context: Context,
        limit: Int = 6,
    ): List<Recent> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        val now = System.currentTimeMillis()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                val time = parts[3].toLongOrNull() ?: return@mapNotNull null
                if (now - time > MAX_AGE_MS) return@mapNotNull null
                Recent(parts[0], parts[1], parts[2], time)
            }
            .take(limit)
            .toList()
    }

    fun forgetPage(
        context: Context,
        url: String,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kept =
            prefs.getString(KEY_RECENT, null).orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() && it.substringBefore('\t') != url }
                .joinToString("\n")
        prefs.edit().putString(KEY_RECENT, kept).apply()
    }

    /** Последняя страница, если запись свежая и относится к тому же сайту, что и [siteUrl]. */
    fun lastUrl(
        context: Context,
        siteUrl: String,
    ): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, null) ?: return null
        if (!isFresh(prefs.getLong(KEY_TIME, 0))) return null
        if (UrlTools.host(url) != UrlTools.host(siteUrl)) return null
        return url
    }

    /** Сохранённая прокрутка для [url]; -1, если восстанавливать нечего. */
    fun scrollFor(
        context: Context,
        url: String,
    ): Float {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_URL, null) != url) return -1f
        if (!isFresh(prefs.getLong(KEY_TIME, 0))) return -1f
        return prefs.getFloat(KEY_SCROLL, -1f)
    }

    /** Запоминает, был ли пользователь на домашней странице, а не на сайте. */
    fun saveHome(
        context: Context,
        atHome: Boolean,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HOME, atHome)
            .apply()
    }

    fun wasHome(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME, false)

    private fun isFresh(time: Long): Boolean {
        val age = System.currentTimeMillis() - time
        return time > 0 && age in 0..MAX_AGE_MS
    }
}
