package app.hybridreading

import android.content.Context

/**
 * История посещений и закладки.
 *
 * История — строки «адрес \t заголовок \t сайт \t время», до 500 записей, без дублей по адресу.
 * Закладки — отдельный список «адрес \t заголовок», попадает в экспорт профиля.
 */
object History {
    private const val PREFS = "history"
    private const val KEY_HISTORY = "items"
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val MAX = 500

    data class Entry(val url: String, val title: String, val siteId: String, val time: Long)

    fun add(
        context: Context,
        url: String,
        title: String,
        siteId: String,
    ) {
        if (!url.startsWith("http")) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lines =
            prefs.getString(KEY_HISTORY, null).orEmpty()
                .lineSequence().filter { it.isNotBlank() }.toMutableList()
        val cleanTitle = title.replace('\t', ' ').replace('\n', ' ').trim()
        val row = listOf(url, cleanTitle, siteId, System.currentTimeMillis().toString()).joinToString("\t")
        lines.removeAll { it.substringBefore('\t') == url }
        lines.add(0, row)
        while (lines.size > MAX) lines.removeAt(lines.size - 1)
        prefs.edit().putString(KEY_HISTORY, lines.joinToString("\n")).apply()
    }

    fun list(
        context: Context,
        query: String = "",
        limit: Int = 300,
    ): List<Entry> {
        val raw =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HISTORY, null) ?: return emptyList()
        val needle = query.trim().lowercase()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4) return@mapNotNull null
                Entry(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0L)
            }
            .filter { needle.isEmpty() || it.title.lowercase().contains(needle) || it.url.lowercase().contains(needle) }
            .take(limit)
            .toList()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
    }

    // --- закладки

    data class Bookmark(val url: String, val title: String)

    fun bookmarks(context: Context): List<Bookmark> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BOOKMARKS, null)
            .orEmpty()
            .lineSequence()
            .filter { it.contains('\t') }
            .map { Bookmark(it.substringBefore('\t'), it.substringAfter('\t')) }
            .toList()

    fun isBookmarked(
        context: Context,
        url: String,
    ): Boolean = bookmarks(context).any { it.url == url }

    /** Возвращает true, если страница теперь в закладках. */
    fun toggleBookmark(
        context: Context,
        url: String,
        title: String,
    ): Boolean {
        if (!url.startsWith("http")) return false
        val current = bookmarks(context).toMutableList()
        val exists = current.any { it.url == url }
        if (exists) {
            current.removeAll { it.url == url }
        } else {
            val cleanTitle = title.replace('\t', ' ').replace('\n', ' ').trim()
            current.add(0, Bookmark(url, cleanTitle))
        }
        saveBookmarks(context, current)
        return !exists
    }

    fun removeBookmark(
        context: Context,
        url: String,
    ) {
        val current = bookmarks(context).filterNot { it.url == url }
        saveBookmarks(context, current)
    }

    fun replaceBookmarks(
        context: Context,
        items: List<Bookmark>,
    ) {
        saveBookmarks(context, items)
    }

    private fun saveBookmarks(
        context: Context,
        items: List<Bookmark>,
    ) {
        val text = items.joinToString("\n") { it.url + "\t" + it.title }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOKMARKS, text)
            .apply()
    }
}
