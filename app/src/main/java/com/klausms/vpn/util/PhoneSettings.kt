package com.klausms.vpn.util

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.klausms.vpn.BuildConfig

/**
 * What the phone does to apps in the background, and the system screens
 * that change it. Android's own battery allowlist is not the whole story:
 * several brands add an "autostart" switch of their own, off by default for
 * apps installed from a file, and kill such apps with the VPN inside.
 */
object PhoneSettings {
    private const val HUAWEI_HINT = "«Запуск приложений» → «Вручную», все три переключателя"

    /**
     * Brands whose firmware stops background apps beyond Android's rules.
     * [screens]: the brand's autostart screens (package to activity), newest
     * firmware first. They are not public API and move between versions, so
     * each is only tried.
     */
    enum class Oem(val hint: String, val screens: List<Pair<String, String>>) {
        XIAOMI(
            "Включите автозапуск, а «Контроль активности» — «Нет ограничений»",
            listOf("com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        HUAWEI(
            HUAWEI_HINT,
            listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
        ),
        HONOR(
            HUAWEI_HINT,
            listOf(
                "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),

        /** ColorOS: Oppo, Realme and OnePlus. */
        OPPO(
            "Разрешите автозапуск и работу в фоне",
            listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.view.StartupAppListActivity",
                "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
                "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            ),
        ),
        VIVO(
            "Разрешите работу в фоне и автозапуск",
            listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            ),
        ),
    }

    /** The brand family by Build.MANUFACTURER, then Build.BRAND; null for stock-like phones. */
    fun oem(manufacturer: String?, brand: String?): Oem? {
        fun of(name: String?): Oem? = when (name?.trim()?.lowercase()) {
            "xiaomi", "redmi", "poco" -> Oem.XIAOMI
            "huawei" -> Oem.HUAWEI
            "honor" -> Oem.HONOR
            "oppo", "realme", "oneplus" -> Oem.OPPO
            "vivo", "iqoo" -> Oem.VIVO
            else -> null
        }
        return of(manufacturer) ?: of(brand)
    }

    fun oem(): Oem? = oem(Build.MANUFACTURER, Build.BRAND)

    /** On Android's battery allowlist («Без ограничений»). */
    fun batteryUnrestricted(context: Context): Boolean = probe("battery allowlist", fallback = false) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    /**
     * «Ограничено» battery mode or "Restrict background activity": Android
     * may then silently drop the widget's "on" and hide the VPN notification.
     */
    fun backgroundRestricted(context: Context): Boolean = probe("background restriction", fallback = false) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
    }

    /** Off means the "VPN switched off" and "server switched" messages are never seen. */
    fun notificationsEnabled(context: Context): Boolean = probe("notifications", fallback = true) {
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
    }

    /**
     * [read], or [fallback] when the system service fails: these are read
     * while drawing Settings and when the app starts, where a throw would
     * crash the screen or skip setting up the core.
     */
    private inline fun probe(what: String, fallback: Boolean, read: () -> Boolean): Boolean = try {
        read()
    } catch (e: Exception) {
        AppLog.w("cannot read $what", e)
        fallback
    }

    /** Something here may stop the VPN in the background. */
    fun needsSetup(context: Context): Boolean =
        !batteryUnrestricted(context) || backgroundRestricted(context) || oem() != null

    /** One line for the log: the phone and what it allows in the background. */
    fun summary(context: Context): String = buildString {
        append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        if (!Build.BRAND.equals(Build.MANUFACTURER, ignoreCase = true)) append(" (").append(Build.BRAND).append(')')
        append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(')')
        append(", app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(')')
        append("; battery unrestricted ").append(yesNo(batteryUnrestricted(context)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            append(", background restricted ").append(yesNo(backgroundRestricted(context)))
            append(", standby bucket ").append(standbyBucket(context))
        }
        append(", notifications ").append(if (notificationsEnabled(context)) "on" else "off")
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    /** How often Android lets the app run in the background (Android 9+). */
    private fun standbyBucket(context: Context): String {
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        return bucket?.let(::bucketName) ?: "unknown"
    }

    fun bucketName(bucket: Int): String = when (bucket) {
        5 -> "exempted"
        10 -> "active"
        20 -> "working_set"
        30 -> "frequent"
        40 -> "rare"
        45 -> "restricted"
        50 -> "never"
        else -> bucket.toString()
    }

    // ------------------------------------------------------ system screens

    /**
     * «Работа в фоне»: lifts a background restriction (the app's own page,
     * Battery), asks to join the allowlist, or shows the list when already
     * on it.
     */
    fun openBatterySettings(context: Context) {
        when {
            backgroundRestricted(context) -> openAppDetails(context)
            batteryUnrestricted(context) ->
                open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || openAppDetails(context)
            else -> {
                @Suppress("BatteryLife") // A VPN has to survive in the background; the user decides.
                val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
                open(context, request) ||
                    open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) ||
                    openAppDetails(context)
            }
        }
    }

    /** The brand's autostart screen, else the app's own page (it has the same switches there on most of them). */
    fun openAutostart(context: Context, oem: Oem) {
        for ((pkg, cls) in oem.screens) {
            if (open(context, Intent().setComponent(ComponentName(pkg, cls)), quiet = true)) return
        }
        AppLog.i("no autostart screen for ${oem.name}, opening the app page")
        openAppDetails(context)
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!open(context, intent)) openAppDetails(context)
    }

    fun openAppDetails(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))

    /** Firmware may lack a screen or not export it: never a crash, only a log line. */
    fun open(context: Context, intent: Intent, quiet: Boolean = false): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        if (!quiet) AppLog.w("cannot open system settings", e)
        false
    }

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
}
