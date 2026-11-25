package app.hybridreading

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.util.Locale

/**
 * Данные одного сайта: сколько занимают, очистка кэша глав и полная очистка.
 *
 * Cache API, localStorage и IndexedDB привязаны к origin, поэтому работаем с ними в отдельном
 * скрытом WebView: открываем нужный сайт и обращаемся к его хранилищу. Соседние сайты не
 * затрагиваются, общий HTTP-кэш WebView тоже остаётся.
 *
 * [clearChapters] — только Cache API (скачанные главы и картинки): вход сохраняется.
 * [clearAll] — плюс localStorage, IndexedDB, service worker и куки этого домена: сайт «как новый».
 * [measure] — сколько байт занимает хранилище origin.
 */
object SiteCache {
    private const val TIMEOUT_MS = 25_000L
    private const val REPORT_DELAY_MS = 3_000L

    /** Кэш глав без потери авторизации. */
    fun clearChapters(
        context: Context,
        url: String,
        onDone: (Int) -> Unit,
    ) {
        run(context, url, CLEAR_CHAPTERS_JS) { report ->
            onDone(deletedCount(report))
        }
    }

    /** Полная очистка сайта вместе с сессией. */
    fun clearAll(
        context: Context,
        url: String,
        onDone: (Int) -> Unit,
    ) {
        run(context, url, CLEAR_ALL_JS, clearHttpCache = true) { report ->
            expireSiteCookies(url)
            onDone(deletedCount(report))
        }
    }

    private const val STATS = "cache_stats"
    private const val STATS_TTL_MS = 10 * 60 * 1000L

    /**
     * Размер кэша сайта с кэшированием результата на 10 минут: открытие правки сайта больше не грузит
     * страницу каждый раз. [force] — пересчитать принудительно (кнопка «Пересчитать»).
     */
    fun measure(
        context: Context,
        url: String,
        siteId: String,
        force: Boolean = false,
        onDone: (Long?) -> Unit,
    ) {
        val prefs = context.getSharedPreferences(STATS, Context.MODE_PRIVATE)
        if (!force) {
            val saved = prefs.getLong("b_$siteId", -1)
            val time = prefs.getLong("t_$siteId", 0)
            if (saved >= 0 && System.currentTimeMillis() - time < STATS_TTL_MS) {
                onDone(saved)
                return
            }
        }
        measure(context, url) { bytes ->
            if (bytes != null) {
                prefs.edit()
                    .putLong("b_$siteId", bytes)
                    .putLong("t_$siteId", System.currentTimeMillis())
                    .apply()
            }
            onDone(bytes)
        }
    }

    /** Занятое хранилищем origin, байт; null — не удалось узнать (сайт не открылся). */
    fun measure(
        context: Context,
        url: String,
        onDone: (Long?) -> Unit,
    ) {
        run(context, url, MEASURE_JS) { report ->
            val bytes = report?.substringAfter(':', "")?.toLongOrNull()
            onDone(bytes)
        }
    }

    private fun deletedCount(report: String?): Int =
        report?.substringAfter(':', "")?.toIntOrNull() ?: if (report == null) -1 else 0

    /**
     * Открывает сайт в скрытом WebView, выполняет [script] и возвращает строку отчёта
     * (`window.__inkCacheReport`). null — страница не загрузилась.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun run(
        context: Context,
        url: String,
        script: String,
        clearHttpCache: Boolean = false,
        onReport: (String?) -> Unit,
    ) {
        val view = WebView(context.applicationContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.setBackgroundColor(0)

        val ui = Handler(Looper.getMainLooper())
        var finished = false

        fun finish(report: String?) {
            if (finished) return
            finished = true
            if (clearHttpCache) runCatching { view.clearCache(true) }
            ui.post {
                runCatching {
                    view.stopLoading()
                    view.destroy()
                }
            }
            onReport(report)
        }

        view.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(
                    v: WebView,
                    pageUrl: String?,
                ) {
                    v.evaluateJavascript(script, null)
                    ui.postDelayed({
                        v.evaluateJavascript("(window.__inkCacheReport || '')") { raw ->
                            finish(raw?.trim('"')?.takeIf { it.isNotBlank() })
                        }
                    }, REPORT_DELAY_MS)
                }

                override fun onReceivedError(
                    v: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) finish(null)
                }
            }

        ui.postDelayed({ finish(null) }, TIMEOUT_MS)
        runCatching { view.loadUrl(url) }.onFailure { finish(null) }
    }

    /** Гасим все куки домена — и host-only, и для поддоменов. */
    private fun expireSiteCookies(url: String) {
        val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull() ?: return
        val manager = CookieManager.getInstance()
        val raw = runCatching { manager.getCookie("https://$host/") }.getOrNull().orEmpty()
        raw.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) null else trimmed.substring(0, eq)
        }.forEach { name ->
            runCatching { manager.setCookie("https://$host/", "$name=; Max-Age=0; Path=/; Domain=$host") }
            runCatching { manager.setCookie("https://$host/", "$name=; Max-Age=0; Path=/") }
        }
        manager.flush()
    }

    /** Только Cache API: там сайт держит скачанные главы и картинки. */
    private const val CLEAR_CHAPTERS_JS = """
(function () {
  window.__inkCacheReport = '';
  (async function () {
    try {
      if (!window.caches) { window.__inkCacheReport = 'deleted:0'; return; }
      var keys = await caches.keys();
      await Promise.all(keys.map(function (name) { return caches.delete(name); }));
      window.__inkCacheReport = 'deleted:' + keys.length;
    } catch (e) {
      window.__inkCacheReport = 'error';
    }
  })();
})();
"""

    /** Всё хранилище origin: кэши, localStorage, IndexedDB, service worker. Куки гасит Kotlin-часть. */
    private const val CLEAR_ALL_JS = """
(function () {
  window.__inkCacheReport = '';
  (async function () {
    var removed = 0;
    try {
      if (window.caches) {
        var keys = await caches.keys();
        await Promise.all(keys.map(function (name) { return caches.delete(name); }));
        removed += keys.length;
      }
    } catch (e) {}
    try {
      localStorage.clear();
      sessionStorage.clear();
    } catch (e) {}
    try {
      if (indexedDB.databases) {
        var dbs = await indexedDB.databases();
        for (var i = 0; i < dbs.length; i++) {
          if (dbs[i] && dbs[i].name) {
            await new Promise(function (resolve) {
              var req = indexedDB.deleteDatabase(dbs[i].name);
              req.onsuccess = req.onerror = req.onblocked = function () { resolve(); };
            });
            removed++;
          }
        }
      }
    } catch (e) {}
    try {
      if (navigator.serviceWorker) {
        var regs = await navigator.serviceWorker.getRegistrations();
        for (var j = 0; j < regs.length; j++) { await regs[j].unregister(); }
      }
    } catch (e) {}
    window.__inkCacheReport = 'deleted:' + removed;
  })();
})();
"""

    /** Сколько байт занято хранилищем этого origin (Cache API + localStorage + IndexedDB). */
    private const val MEASURE_JS = """
(function () {
  window.__inkCacheReport = '';
  (async function () {
    try {
      var total = 0;
      if (navigator.storage && navigator.storage.estimate) {
        var est = await navigator.storage.estimate();
        total = est && est.usage ? est.usage : 0;
      }
      window.__inkCacheReport = 'bytes:' + total;
    } catch (e) {
      window.__inkCacheReport = 'bytes:0';
    }
  })();
})();
"""
}

/** «6,3 МБ», «512 КБ», «0 Б» — единицы как в системных настройках. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val text =
        if (value >= 10 || value % 1.0 < 0.05) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    return "$text ${units[unit]}".replace('.', ',')
}
