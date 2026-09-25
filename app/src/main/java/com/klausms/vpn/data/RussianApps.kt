package com.klausms.vpn.data

import android.content.pm.PackageManager

/**
 * Russian apps that commonly refuse to work or complain when they see a VPN
 * (banks, Gosuslugi, marketplaces, carriers, VK). With "Russian apps bypass
 * the VPN" on they are excluded from the tunnel completely, so they see a
 * normal connection. Browsers are deliberately NOT listed: people open
 * blocked sites in them.
 */
object RussianApps {
    val packages: Set<String> = setOf(
        // Banks and payments
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "ru.gazprombank.android.mobilebank.app", "ru.rshb.dbo",
        "ru.nspk.mirpay", "ru.nspk.sbpay", "com.yandex.bank", "ru.ozon.fintech.finance",
        "ru.raiffeisen.mobile.new", "ru.psbank.mobile", "ru.pochtabank.pochtaapp", "ru.mkb.mobile",
        // Government
        "ru.rostel", "ru.fns.lkfl", "ru.mos.app", "ru.gosuslugi.pos",
        // Marketplaces and services
        "ru.ozon.app.android", "com.wildberries.ru", "com.avito.android", "ru.beru.android",
        "ru.yandex.taxi", "ru.yandex.yandexmaps", "ru.yandex.yandexnavi", "ru.yandex.music",
        "ru.kinopoisk", "ru.yandex.disk", "ru.yandex.mail", "ru.foodfox.client", "ru.yandex.market",
        "ru.dublgis.dgismobile", "ru.rutube.app", "ru.hh.android", "ru.cian.main", "ru.rzd.pass",
        "ru.vk.store",
        // Carriers
        "ru.mts.mymts", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2",
        // Social
        "com.vkontakte.android", "com.vk.im", "ru.ok.android", "ru.oneme.app", "ru.mail.mailapp",
    )

    /** The listed apps that are actually installed. */
    fun installed(pm: PackageManager): Set<String> = packages.filterTo(mutableSetOf()) { pkg ->
        try {
            pm.getApplicationInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
