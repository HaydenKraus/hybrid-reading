package app.hybridreading

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Site(
    val id: String,
    val title: String,
    val url: String,
    /** Блокировать рекламу именно на этом сайте. */
    val adBlock: Boolean = true,
)

/**
 * Список сайтов приложения. Встроенных сайтов нет: пока список пуст, приложение показывает
 * домашнюю страницу, а сайты пользователь добавляет сам. Хранится в SharedPreferences("sites").
 */
object SitesStore {

    private const val PREFS = "sites"
    private const val KEY_LIST = "list"
    private const val KEY_ACTIVE = "active"

    private var prefs: SharedPreferences? = null

    private val _sites = MutableStateFlow<List<Site>>(emptyList())
    val sites: StateFlow<List<Site>> = _sites.asStateFlow()

    /** Активный сайт; null, пока пользователь не добавил ни одного. */
    private val _active = MutableStateFlow<Site?>(null)
    val active: StateFlow<Site?> = _active.asStateFlow()

    /** Идемпотентно: повторный вызов не перечитывает и не сбрасывает состояние. */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = decodeSites(stored.getString(KEY_LIST, null))
        val activeId = stored.getString(KEY_ACTIVE, null)
        prefs = stored
        _sites.value = list
        _active.value = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
    }

    fun select(id: String) {
        if (_active.value?.id == id) return
        val site = _sites.value.firstOrNull { it.id == id } ?: return
        _active.value = site
        prefs?.edit()?.putString(KEY_ACTIVE, id)?.apply()
    }

    fun add(rawUrl: String, rawTitle: String?, adBlock: Boolean = true): Result<Site> {
        val url = UrlTools.normalize(rawUrl)
            ?: return Result.failure(IllegalArgumentException("Некорректная ссылка"))
        val id = UrlTools.idFor(url)
            ?: return Result.failure(IllegalArgumentException("Некорректная ссылка"))
        val title = rawTitle?.trim()?.ifBlank { null } ?: UrlTools.titleFor(url)
        val site = Site(id, title, url, adBlock)
        val current = _sites.value
        val updated = if (current.any { it.id == id }) {
            current.map { if (it.id == id) site else it }
        } else {
            current + site
        }
        publish(updated, id)
        return Result.success(site)
    }

    /**
     * Правка сайта: название, ссылка и блокировка рекламы.
     * Смена домена меняет id, поэтому занятый адрес отклоняется с понятной ошибкой.
     */
    fun update(id: String, rawUrl: String, rawTitle: String?, adBlock: Boolean): Result<Site> {
        val current = _sites.value
        val existing = current.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalArgumentException("Сайт не найден"))
        val url = UrlTools.normalize(rawUrl)
            ?: return Result.failure(IllegalArgumentException("Некорректная ссылка"))
        val newId = UrlTools.idFor(url)
            ?: return Result.failure(IllegalArgumentException("Некорректная ссылка"))
        if (newId != id && current.any { it.id == newId }) {
            return Result.failure(IllegalArgumentException("Такой сайт уже добавлен"))
        }
        val title = rawTitle?.trim()?.ifBlank { null } ?: UrlTools.titleFor(url)
        val site = Site(newId, title, url, adBlock)
        publish(current.map { if (it.id == id) site else it }, if (_active.value?.id == id) newId else _active.value?.id)
        return Result.success(site)
    }

    fun remove(id: String) {
        val current = _sites.value
        if (current.none { it.id == id }) return
        val updated = current.filterNot { it.id == id }
        publish(updated, if (_active.value?.id == id) null else _active.value?.id)
    }

    /** Публикует новый список, выбирает валидный активный сайт и пишет всё в prefs. */
    private fun publish(list: List<Site>, activeId: String?) {
        _sites.value = list
        val active = list.firstOrNull { it.id == activeId }
            ?: _active.value?.let { current -> list.firstOrNull { it.id == current.id } }
            ?: list.firstOrNull()
        _active.value = active
        prefs?.edit()
            ?.putString(KEY_LIST, encodeSites(list))
            ?.putString(KEY_ACTIVE, active?.id ?: "")
            ?.apply()
    }
}

// --- Чистые функции разбора/сериализации: без SharedPreferences, тестируются на JVM ---

private data class RawSite(val id: String, val title: String, val url: String, val adBlock: Boolean?)

/** Пустой/битый json -> пустой список; дубликаты по id отбрасываются, порядок сохраняется. */
internal fun decodeSites(raw: String?): List<Site> {
    val stored = raw?.let { parseSiteArray(it) } ?: return emptyList()
    val result = LinkedHashMap<String, Site>()
    for (entry in stored) {
        val url = UrlTools.normalize(entry.url) ?: continue
        val id = entry.id.trim().ifEmpty { UrlTools.idFor(url).orEmpty() }
        if (id.isEmpty() || result.containsKey(id)) continue
        result[id] = Site(
            id = id,
            title = entry.title.trim().ifBlank { UrlTools.titleFor(url) },
            url = url,
            adBlock = entry.adBlock ?: true,
        )
    }
    return result.values.toList()
}

internal fun encodeSites(list: List<Site>): String = list.joinToString(",", "[", "]") { site ->
    "{\"id\":${quoted(site.id)},\"title\":${quoted(site.title)},\"url\":${quoted(site.url)},\"adblock\":${site.adBlock}}"
}

private fun quoted(value: String): String = buildString(value.length + 2) {
    append('"')
    for (c in value) when {
        c == '"' -> append("\\\"")
        c == '\\' -> append("\\\\")
        c == '\n' -> append("\\n")
        c == '\r' -> append("\\r")
        c == '\t' -> append("\\t")
        c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
        else -> append(c)
    }
    append('"')
}

private fun parseSiteArray(raw: String): List<RawSite>? {
    val cursor = JsonCursor(raw)
    if (!cursor.take('[')) return null
    val list = ArrayList<RawSite>()
    if (cursor.take(']')) return list
    while (true) {
        val obj = cursor.readObject() ?: return null
        list += RawSite(
            id = obj["id"].orEmpty(),
            title = obj["title"].orEmpty(),
            url = obj["url"].orEmpty(),
            adBlock = obj["adblock"]?.toBooleanStrictOrNull(),
        )
        if (cursor.take(',')) continue
        if (cursor.take(']')) return list
        return null
    }
}

/**
 * Минимальный JSON-читатель под наш формат: org.json в JVM-тестах — заглушка из android.jar,
 * поэтому разбор написан вручную и остаётся чистым.
 */
private class JsonCursor(private val src: String) {
    private var pos = 0

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    fun take(ch: Char): Boolean {
        skipWs()
        if (pos < src.length && src[pos] == ch) {
            pos++
            return true
        }
        return false
    }

    private fun readString(): String? {
        skipWs()
        if (pos >= src.length || src[pos] != '"') return null
        pos++
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= src.length) return null
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > src.length) return null
                            val code = src.substring(pos, pos + 4).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
        }
        return null
    }

    fun readObject(): Map<String, String>? {
        if (!take('{')) return null
        val map = LinkedHashMap<String, String>()
        if (take('}')) return map
        while (true) {
            val key = readString() ?: return null
            if (!take(':')) return null
            skipWs()
            if (pos < src.length && src[pos] == '"') {
                map[key] = readString() ?: return null
            } else {
                val literal = readLiteralText()
                if (literal != null) {
                    map[key] = literal
                } else if (!skipValue()) {
                    return null
                }
            }
            if (take(',')) continue
            if (take('}')) return map
            return null
        }
    }

    /** true/false/null — литералы, а не строки: нужен только булев флаг adblock. */
    private fun readLiteralText(): String? {
        skipWs()
        val rest = src.substring(pos)
        val token = when {
            rest.startsWith("true") -> "true"
            rest.startsWith("false") -> "false"
            rest.startsWith("null") -> "null"
            else -> return null
        }
        pos += token.length
        return token
    }

    private fun skipValue(): Boolean {
        skipWs()
        if (pos >= src.length) return false
        return when (src[pos]) {
            '"' -> readString() != null
            '{' -> skipObject()
            '[' -> skipArray()
            else -> {
                val start = pos
                while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '-' || src[pos] == '+' || src[pos] == '.')) pos++
                pos > start
            }
        }
    }

    private fun skipObject(): Boolean {
        if (!take('{')) return false
        if (take('}')) return true
        while (true) {
            if (readString() == null) return false
            if (!take(':')) return false
            if (!skipValue()) return false
            if (take(',')) continue
            return take('}')
        }
    }

    private fun skipArray(): Boolean {
        if (!take('[')) return false
        if (take(']')) return true
        while (true) {
            if (!skipValue()) return false
            if (take(',')) continue
            return take(']')
        }
    }
}
