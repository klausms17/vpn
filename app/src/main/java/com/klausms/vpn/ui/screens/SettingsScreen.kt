package com.klausms.vpn.ui.screens

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.BuildConfig
import com.klausms.vpn.R
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.components.IconTile
import com.klausms.vpn.ui.components.InsetGroup
import com.klausms.vpn.ui.components.IosSwitch
import com.klausms.vpn.ui.components.ListRow
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SectionFooter
import com.klausms.vpn.ui.components.SectionHeader
import com.klausms.vpn.ui.components.NavBar
import com.klausms.vpn.ui.components.navBarClearance
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TileBlue = Color(0xFF0A84FF)
private val TileIndigo = Color(0xFF5E5CE6)
private val TileGreen = Color(0xFF30D158)
private val TileOrange = Color(0xFFFF9F0A)
private val TileGray = Color(0xFF636366)

/**
 * Settings in iOS style. Only what a person can understand and may want to
 * change: the smart defaults (Russian sites direct, instant recovery when
 * the network changes, IPv6 kept inside the tunnel) are not settings.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val geoVersion by vm.geoVersion.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        geoVersion = geoVersion,
        onRussianApps = { v -> vm.updateSettings { it.copy(bypassRussianApps = v) } },
        onUpdateGeo = { vm.updateGeo() },
        onBack = onBack,
        onNavigate = onNavigate,
    )
}

@Composable
fun SettingsContent(
    settings: AppSettings,
    geoVersion: Long,
    onRussianApps: (Boolean) -> Unit,
    onUpdateGeo: () -> Unit,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val context = LocalContext.current
    var confirmGeo by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(kc.page)) {
        NavBar("Настройки", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarClearance() + 24.dp)) {

            item { SectionHeader("Приложения без VPN") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Российские сервисы",
                        leading = { IconTile(R.drawable.ic_split_ios, TileBlue) },
                        trailing = {
                            IosSwitch(
                                checked = settings.bypassRussianApps,
                                onCheckedChange = onRussianApps,
                                description = "Российские сервисы без VPN",
                            )
                        },
                        onClick = { onRussianApps(!settings.bypassRussianApps) },
                    )
                    RowDivider(start = 57.dp)
                    ListRow(
                        title = "Другие приложения",
                        value = settings.excludedApps.size.takeIf { it > 0 }?.toString(),
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_grid_ios, TileIndigo) },
                        onClick = { onNavigate("apps") },
                    )
                }
            }
            item { SectionFooter("Банки, Госуслуги, маркетплейсы и операторы работают напрямую и не видят VPN.") }

            item { SectionHeader("Надёжность") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Постоянный VPN",
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_power_ios, TileGreen) },
                        onClick = { openSystem(context, Intent(Settings.ACTION_VPN_SETTINGS)) },
                    )
                    RowDivider(start = 57.dp)
                    ListRow(
                        title = "Работа в фоне",
                        value = if (batteryUnrestricted) "Разрешено" else "Разрешить",
                        valueColor = if (batteryUnrestricted) kc.secondary else kc.orange,
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_battery_ios, TileOrange) },
                        onClick = { requestUnrestrictedBattery(context) },
                    )
                }
            }
            item {
                SectionFooter(
                    buildString {
                        if (!batteryUnrestricted) append("Разрешите работу в фоне, иначе телефон может выключать VPN. ")
                        append("В «Постоянном VPN» включите «Постоянная VPN» — VPN сам запустится после перезагрузки. ")
                        append("«Блокировать соединения без VPN» не включайте: банки и Госуслуги останутся без интернета.")
                    },
                )
            }

            item { SectionHeader("Если что-то не работает") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Сообщить о проблеме",
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_list_ios, TileGray) },
                        onClick = { onNavigate("logs") },
                    )
                    RowDivider(start = 57.dp)
                    ListRow(
                        title = "Обновить списки",
                        subtitle = listsDate(geoVersion)?.let { "Обновлены $it" },
                        leading = { IconTile(R.drawable.ic_refresh_ios, TileBlue) },
                        onClick = { confirmGeo = true },
                    )
                }
            }
            item { SectionFooter("Если российский сайт не открывается или открывается через VPN, обновите списки.") }

            item { SectionHeader("О приложении") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Версия",
                        value = BuildConfig.VERSION_NAME,
                        leading = { IconTile(R.drawable.ic_tab_vpn, TileGreen) },
                    )
                    RowDivider(start = 57.dp)
                    ListRow(
                        title = "Лицензии",
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_doc_ios, TileGray) },
                        onClick = { onNavigate("licenses") },
                    )
                }
            }
        }
    }

    if (confirmGeo) {
        AlertDialog(
            onDismissRequest = { confirmGeo = false },
            containerColor = kc.card,
            title = { Text("Обновить списки?", style = IosType.headline, color = kc.label) },
            text = {
                Text(
                    "Будет скачано около 90 МБ (лучше по Wi-Fi). Приложение оставит только нужное, проверит файлы и применит их.",
                    style = IosType.subhead,
                    color = kc.secondary,
                )
            },
            confirmButton = { TextButton(onClick = { confirmGeo = false; onUpdateGeo() }) { Text("Обновить", color = kc.green) } },
            dismissButton = { TextButton(onClick = { confirmGeo = false }) { Text("Отмена", color = kc.green) } },
        )
    }
}

/** "24 сентября", with the year when it is not this one. */
private fun listsDate(epochSeconds: Long): String? {
    if (epochSeconds <= 0) return null
    val date = Date(epochSeconds * 1000)
    val ru = Locale.forLanguageTag("ru")
    val thisYear = SimpleDateFormat("yyyy", ru).format(Date()) == SimpleDateFormat("yyyy", ru).format(date)
    return SimpleDateFormat(if (thisYear) "d MMMM" else "d MMMM yyyy", ru).format(date)
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

private fun requestUnrestrictedBattery(context: Context) {
    if (isIgnoringBatteryOptimizations(context)) {
        openSystem(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return
    }
    @Suppress("BatteryLife") // A VPN has to survive in the background; the user decides.
    val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
    if (!openSystem(context, request)) {
        openSystem(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun openSystem(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    AppLog.w("cannot open system settings", e)
    false
}
