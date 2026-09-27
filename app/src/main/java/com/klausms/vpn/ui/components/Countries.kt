package com.klausms.vpn.ui.components

/**
 * Server names from subscriptions usually start with a flag emoji
 * ("🇳🇱 Амстердам"). The flag gives the country: its name for the UI and a
 * point for the map. Nothing is looked up on the network.
 */
object Countries {
    private data class Info(val name: String, val lon: Double, val lat: Double)

    private val table = mapOf(
        "NL" to Info("Нидерланды", 4.9, 52.37),
        "DE" to Info("Германия", 8.68, 50.11),
        "FI" to Info("Финляндия", 24.94, 60.17),
        "SE" to Info("Швеция", 18.07, 59.33),
        "NO" to Info("Норвегия", 10.75, 59.91),
        "DK" to Info("Дания", 12.57, 55.68),
        "EE" to Info("Эстония", 24.75, 59.44),
        "LV" to Info("Латвия", 24.11, 56.95),
        "LT" to Info("Литва", 25.28, 54.69),
        "PL" to Info("Польша", 21.01, 52.23),
        "CZ" to Info("Чехия", 14.42, 50.09),
        "AT" to Info("Австрия", 16.37, 48.21),
        "CH" to Info("Швейцария", 8.54, 47.37),
        "FR" to Info("Франция", 2.35, 48.86),
        "GB" to Info("Великобритания", -0.13, 51.51),
        "UK" to Info("Великобритания", -0.13, 51.51),
        "IE" to Info("Ирландия", -6.26, 53.35),
        "BE" to Info("Бельгия", 4.35, 50.85),
        "LU" to Info("Люксембург", 6.13, 49.61),
        "ES" to Info("Испания", -3.7, 40.42),
        "PT" to Info("Португалия", -9.14, 38.72),
        "IT" to Info("Италия", 9.19, 45.46),
        "RO" to Info("Румыния", 26.1, 44.43),
        "BG" to Info("Болгария", 23.32, 42.7),
        "HU" to Info("Венгрия", 19.04, 47.5),
        "RS" to Info("Сербия", 20.46, 44.79),
        "MD" to Info("Молдова", 28.86, 47.01),
        "UA" to Info("Украина", 30.52, 50.45),
        "BY" to Info("Беларусь", 27.56, 53.9),
        "GE" to Info("Грузия", 44.79, 41.72),
        "AM" to Info("Армения", 44.51, 40.18),
        "AZ" to Info("Азербайджан", 49.87, 40.41),
        "TR" to Info("Турция", 28.98, 41.01),
        "GR" to Info("Греция", 23.73, 37.98),
        "CY" to Info("Кипр", 33.38, 35.19),
        "IL" to Info("Израиль", 34.78, 32.08),
        "AE" to Info("ОАЭ", 55.27, 25.2),
        "KZ" to Info("Казахстан", 76.95, 43.24),
        "UZ" to Info("Узбекистан", 69.24, 41.3),
        "KG" to Info("Киргизия", 74.59, 42.87),
        "RU" to Info("Россия", 37.62, 55.76),
        "US" to Info("США", -77.04, 38.9),
        "CA" to Info("Канада", -79.38, 43.65),
        "JP" to Info("Япония", 139.69, 35.69),
        "SG" to Info("Сингапур", 103.82, 1.35),
        "HK" to Info("Гонконг", 114.17, 22.32),
        "IN" to Info("Индия", 72.88, 19.08),
        "AU" to Info("Австралия", 151.21, -33.87),
        "BR" to Info("Бразилия", -46.63, -23.55),
    )

    /** Two-letter code from the first flag emoji in [text], or null. */
    fun codeFrom(text: String): String? {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            if (cp in 0x1F1E6..0x1F1FF && next < text.length) {
                val cp2 = text.codePointAt(next)
                if (cp2 in 0x1F1E6..0x1F1FF) {
                    return "${('A' + (cp - 0x1F1E6))}${('A' + (cp2 - 0x1F1E6))}"
                }
            }
            i = next
        }
        return null
    }

    /** [text] without flag emoji and the spaces around them. */
    fun stripFlags(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp !in 0x1F1E6..0x1F1FF) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString().trim().ifEmpty { text.trim() }
    }

    /** The flag emoji for a two-letter code. */
    fun flag(code: String): String {
        val c = code.uppercase()
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return ""
        return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
    }

    fun name(code: String?): String? = code?.let { table[it.uppercase()]?.name }

    /** Longitude and latitude of the capital, for the map pin. */
    fun point(code: String?): Pair<Double, Double>? = code?.let { table[it.uppercase()] }?.let { it.lon to it.lat }
}
