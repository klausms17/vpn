package com.klausms.vpn.ui

/**
 * How pasted, shared or deep-linked text is read. One place, so the
 * confirmation dialog shows exactly what import() will fetch.
 */
object ImportText {
    private val LINK_START = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*://""")
    private val LINK_ANYWHERE = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://\S+""")

    /**
     * Keys and links in the text. A line that starts with a link is taken
     * whole (names after "#" may contain spaces); inside other text, such as
     * a messenger message, each link is picked out.
     */
    fun links(input: String): List<String> {
        val text = input.trim()
        // A pasted subscription body (Xray JSON) is not a list of links.
        if (text.startsWith("{") || text.startsWith("[")) return emptyList()
        return text.lines().map { it.trim() }.flatMap { line ->
            if (LINK_START.containsMatchIn(line)) {
                listOf(line)
            } else {
                LINK_ANYWHERE.findAll(line).map { it.value.trimEnd('.', ',', ';', ')', '»', '"', '\'') }.toList()
            }
        }
    }

    /** The subscription URL when the text is a single http(s) link, else null. */
    fun subscriptionUrl(input: String): String? =
        links(input).singleOrNull()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
}
