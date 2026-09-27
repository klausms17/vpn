package com.klausms.vpn.ui

import java.io.ByteArrayOutputStream

/**
 * "Add to Kirov VPN" links from subscription pages and messengers:
 * - klausvpn://add/<link> and klausvpn://import/<link>: the raw text after
 *   the prefix (a subscription URL or a key), encoded or not;
 * - klausvpn://install-config?url=<link>[&name=…] (v2rayNG style), where
 *   the link may be unencoded and runs to "&name=" or the end.
 * The result goes to the same confirmation as shared text: nothing is added
 * without the user's tap.
 */
object DeepLink {
    private const val SCHEME = "klausvpn://"
    private const val MAX_LENGTH = 64 * 1024

    // Some browsers fold "//" inside a path into one slash.
    private val FOLDED_SLASH = Regex("^(https?):/(?!/)", RegexOption.IGNORE_CASE)
    private val HTTP = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val LINK = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")

    /** The key or subscription link inside [data] (Intent.getDataString()), or null. */
    fun payload(data: String?): String? {
        val link = data?.trim() ?: return null
        if (!link.startsWith(SCHEME, ignoreCase = true)) return null
        val rest = link.substring(SCHEME.length)
        val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val tail = rest.substring(host.length)
        val raw = when (host.lowercase()) {
            "add", "import" -> if (tail.startsWith("/")) tail.substring(1) else queryUrl(tail)
            "install-config" -> queryUrl(tail)
            else -> null
        } ?: return null
        // Decoded once, only when it is still encoded: "vless://…#My%20server"
        // must reach the parser as it is.
        var text = (if ("://" in raw) raw else percentDecode(raw)).trim()
        text = FOLDED_SLASH.replace(text, "$1://")
        // A subscription page may add "#name"; for a key it is the server's name.
        if (HTTP.containsMatchIn(text)) text = text.substringBefore('#')
        // Only a link or key goes on to the confirmation: no control
        // characters, and a scheme at the very start.
        if (text.any { it.isISOControl() } || !LINK.containsMatchIn(text)) return null
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }

    /** The host of an http(s) URL, to show where a subscription comes from. */
    fun urlHost(url: String): String? {
        val t = url.trim()
        if (!HTTP.containsMatchIn(t)) return null
        val authority = t.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' && !it.isWhitespace() }
        val host = authority.substringAfterLast('@').let { a ->
            if (a.startsWith("[")) a.substringBefore(']') + "]" else a.substringBefore(':')
        }
        return host.takeIf { it.isNotEmpty() }
    }

    /** The text after "url=" in "?…": to "&name=" or the end, since the link may carry its own "&". */
    private fun queryUrl(tail: String): String? {
        if (!tail.startsWith("?")) return null
        val query = tail.substring(1)
        val start = when {
            query.startsWith("url=") -> 4
            "&url=" in query -> query.indexOf("&url=") + 5
            else -> return null
        }
        val value = query.substring(start)
        val end = value.indexOf("&name=")
        return if (end >= 0) value.substring(0, end) else value
    }

    /** %XX sequences as UTF-8; "+" stays "+" (a path, not a form). */
    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        val bytes = ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
        }
        var i = 0
        while (i < s.length) {
            val hi = if (s[i] == '%' && i + 2 < s.length) Character.digit(s[i + 1], 16) else -1
            val lo = if (hi >= 0) Character.digit(s[i + 2], 16) else -1
            if (lo >= 0) {
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                flush()
                out.append(s[i])
                i++
            }
        }
        flush()
        return out.toString()
    }
}
