package app.hybridreading

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Экспорт и импорт профиля: сайты, их названия, лимиты кэша и скрытые блоки.
 *
 * Куки и сессии сюда не попадают — вход на сайтах придётся выполнить заново, об этом
 * приложение честно пишет в диалоге импорта.
 */
object ProfileBackup {

    private const val VERSION = 1

    fun buildJson(context: Context): String {
        val sites = JSONArray()
        for (site in SitesStore.sites.value) {
            val hidden = JSONArray()
            HiddenBlocks.get(context, site.id).forEach { hidden.put(it) }
            sites.put(
                JSONObject().apply {
                    put("id", site.id)
                    put("title", site.title)
                    put("url", site.url)
                    put("limitMb", site.cacheLimitMb)
                    put("hidden", hidden)
                },
            )
        }
        return JSONObject().apply {
            put("app", "Hybrid Reading")
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("sites", sites)
        }.toString(2)
    }

    /** Возвращает человекочитаемый итог или бросает исключение с понятным текстом. */
    fun restore(context: Context, json: String): String {
        val root = runCatching { JSONObject(json) }.getOrElse {
            throw IllegalArgumentException("Это не файл профиля Hybrid Reading")
        }
        val array = root.optJSONArray("sites")
            ?: throw IllegalArgumentException("В файле нет списка сайтов")
        if (array.length() == 0) throw IllegalArgumentException("В файле нет ни одного сайта")

        val restored = ArrayList<Site>()
        val hiddenBySite = HashMap<String, List<String>>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = UrlTools.normalize(item.optString("url")) ?: continue
            val id = UrlTools.idFor(url) ?: continue
            val title = item.optString("title").trim().ifBlank { UrlTools.titleFor(url) }
            restored += Site(id, title, url, item.optInt("limitMb", 0).coerceAtLeast(0))
            val hidden = item.optJSONArray("hidden") ?: continue
            val list = ArrayList<String>()
            for (j in 0 until hidden.length()) {
                val selector = hidden.optString(j).trim()
                if (selector.isNotEmpty()) list += selector
            }
            if (list.isNotEmpty()) hiddenBySite[id] = list
        }
        if (restored.isEmpty()) throw IllegalArgumentException("Ни один сайт из файла не подошёл")

        SitesStore.replaceAll(restored)
        for ((id, selectors) in hiddenBySite) {
            HiddenBlocks.clear(context, id)
            selectors.forEach { HiddenBlocks.add(context, id, it) }
        }
        val hiddenTotal = hiddenBySite.values.sumOf { it.size }
        return "Сайтов: ${restored.size}, скрытых блоков: $hiddenTotal"
    }

    /** Имя файла вида hybrid-reading-2026-11-25.json. */
    fun suggestedFileName(): String {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        return "hybrid-reading-$date.json"
    }
}
