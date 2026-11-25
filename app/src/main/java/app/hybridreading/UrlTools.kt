package app.hybridreading

import java.net.IDN

/**
 * Разбор ссылок на сайты. Чистый Kotlin (без Android), чтобы тестировать на JVM.
 */
object UrlTools {
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")
    private val OPAQUE = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "ac", "edu")

    /** "example.net" -> "https://example.net/"; мусор/чужая схема/нет хоста -> null. */
    fun normalize(raw: String): String? {
        var rest = raw.trim()
        if (rest.isEmpty()) return null

        val scheme: String
        val explicit = SCHEME.find(rest)
        if (explicit != null) {
            scheme = explicit.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            rest = rest.substring(explicit.range.last + 1)
        } else {
            val opaque = OPAQUE.find(rest)
            val prefix = opaque?.groupValues?.get(1)?.lowercase()
            // "example.net:8080" и "localhost:3000" — хост с портом, а не схема.
            val hostWithPort =
                prefix != null &&
                    (prefix.contains('.') || rest.getOrNull(opaque!!.range.last + 1)?.isDigit() == true)
            if (prefix != null && !hostWithPort) {
                if (prefix != "http" && prefix != "https") return null
                scheme = prefix
                rest = rest.substring(opaque.range.last + 1)
            } else {
                scheme = "https"
            }
        }

        rest = rest.substringBefore('#')
        val cut = rest.indexOfFirst { it == '/' || it == '?' }
        val authority = (if (cut < 0) rest else rest.substring(0, cut)).substringAfterLast('@')
        var tail = if (cut < 0) "" else rest.substring(cut)
        if (authority.isEmpty()) return null

        val colon = authority.lastIndexOf(':')
        val hostPart = if (colon >= 0) authority.substring(0, colon) else authority
        val port = if (colon >= 0) authority.substring(colon + 1) else null
        if (hostPart.isEmpty()) return null
        if (port != null) {
            val number = port.toIntOrNull()
            if (number == null || number !in 1..65535) return null
        }

        val host = hostPart.lowercase()
        if (!isValidHost(host)) return null

        if (tail.isEmpty() || tail[0] == '?') tail = "/$tail"
        tail = encodeSpaces(tail)
        val portPart = port?.let { ":$it" }.orEmpty()
        return "$scheme://$host$portPart$tail"
    }

    /** "https://WWW.Example.net/x" -> "example.net": нижний регистр, без порта и ведущего www. */
    fun host(url: String): String? {
        val normalized = normalize(url) ?: return null
        val rest = normalized.substring(normalized.indexOf("://") + 3)
        val cut = rest.indexOfFirst { it == '/' || it == '?' }
        var host = if (cut < 0) rest else rest.substring(0, cut)
        val colon = host.lastIndexOf(':')
        if (colon >= 0) host = host.substring(0, colon)
        host = host.removePrefix("www.")
        return host.ifEmpty { null }
    }

    /** Стабильный id сайта: хост без www/порта. */
    fun idFor(url: String): String? = host(url)

    /** "https://www.example.me/" -> "Пример"; домен без зоны, с заглавной, до 24 символов. */
    fun titleFor(url: String): String {
        val host = host(url) ?: return "Сайт"
        val labels = host.split('.').filter { it.isNotEmpty() }
        var name = if (labels.size >= 2) labels[labels.size - 2] else host
        if (labels.size >= 3 && name in SECOND_LEVEL) name = labels[labels.size - 3]
        if (name.isEmpty()) return "Сайт"
        return name.replaceFirstChar { it.titlecase() }.take(24)
    }

    private fun isValidHost(host: String): Boolean {
        if (!host.contains('.')) return false
        if (host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false
        if (IPV4.matches(host)) {
            return host.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        }
        // IDN.toASCII отсеивает пробелы, слишком длинные метки и прочий мусор,
        // но в результат отдаём исходный (кириллический) домен.
        val ascii =
            try {
                IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
            } catch (e: IllegalArgumentException) {
                return false
            }
        if (ascii.isEmpty()) return false
        val labels = ascii.split('.')
        if (labels.size < 2) return false
        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith('-') || label.endsWith('-')) return false
            val invalid =
                label.any { ch ->
                    ch !in 'a'..'z' && ch !in 'A'..'Z' && ch !in '0'..'9' && ch != '-' && ch != '_'
                }
            if (invalid) return false
        }
        return labels.last().any { !it.isDigit() }
    }

    private fun encodeSpaces(path: String): String {
        if (path.indexOf(' ') < 0 && path.indexOf('\t') < 0) return path
        return buildString(path.length + 8) {
            for (c in path) when (c) {
                ' ' -> append("%20")
                '\t' -> append("%09")
                else -> append(c)
            }
        }
    }
}
