package app.hybridreading

import android.content.Context
import android.content.SharedPreferences

/**
 * Скрытые пользователем блоки: CSS-селекторы, которые прячутся на страницах конкретного сайта.
 * Хранится по одному ключу на сайт, селекторы разделены переводом строки.
 */
object HiddenBlocks {

    private const val PREFS = "hidden_blocks"
    private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefs ?: context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .also { prefs = it }

    fun get(context: Context, siteId: String): List<String> =
        prefs(context).getString(siteId, null)
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()

    fun count(context: Context, siteId: String): Int = get(context, siteId).size

    /** Возвращает false, если такой селектор уже скрыт. */
    fun add(context: Context, siteId: String, selector: String): Boolean {
        val clean = selector.trim()
        if (clean.isEmpty()) return false
        val current = get(context, siteId)
        if (clean in current) return false
        prefs(context).edit().putString(siteId, (current + clean).joinToString("\n")).apply()
        return true
    }

    fun clear(context: Context, siteId: String) {
        prefs(context).edit().remove(siteId).apply()
    }

    /** CSS, который прячет все сохранённые блоки сайта. */
    fun css(context: Context, siteId: String): String =
        get(context, siteId).joinToString("") { it + "{display:none!important}" }
}
