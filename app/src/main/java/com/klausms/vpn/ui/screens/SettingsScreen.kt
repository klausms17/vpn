package com.klausms.vpn.ui.screens

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.components.IconTile
import com.klausms.vpn.ui.components.InsetGroup
import com.klausms.vpn.ui.components.IosSwitch
import com.klausms.vpn.ui.components.LargeTitle
import com.klausms.vpn.ui.components.ListRow
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SectionFooter
import com.klausms.vpn.ui.components.SectionHeader
import com.klausms.vpn.ui.components.TabBarSpace
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
fun SettingsScreen(vm: MainViewModel, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val geoVersion by vm.geoVersion.collectAsStateWithLifecycle()
    val coreVersion by vm.coreVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmGeo by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize().background(kc.page)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = TabBarSpace + 16.dp)) {
            item { LargeTitle("Настройки") }

            item { SectionHeader("Приложения без VPN") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Российские приложения без VPN",
                        leading = { IconTile(R.drawable.ic_split_ios, TileBlue) },
                        trailing = {
                            IosSwitch(
                                checked = settings.bypassRussianApps,
                                onCheckedChange = { v -> vm.updateSettings { it.copy(bypassRussianApps = v) } },
                                description = "Российские приложения без VPN",
                            )
                        },
                        onClick = { vm.updateSettings { it.copy(bypassRussianApps = !settings.bypassRussianApps) } },
                    )
                }
            }
            item { SectionFooter("Банки, Госуслуги, маркетплейсы и операторы работают напрямую и не видят VPN.") }
            item {
                InsetGroup(Modifier.androidPaddingTop()) {
                    ListRow(
                        title = "Выбрать приложения",
                        value = "Выбрано: ${settings.excludedApps.size}",
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_grid_ios, TileIndigo) },
                        onClick = { onNavigate("apps") },
                    )
                }
            }

            item { SectionHeader("Надёжность") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Постоянный VPN",
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_power_ios, TileGreen) },
                        onClick = { openSystem(context, Intent(Settings.ACTION_VPN_SETTINGS)) },
                    )
                }
            }
            item {
                SectionFooter(
                    "Включите «Постоянная VPN» — VPN сам запустится после перезагрузки телефона. " +
                        "«Блокировать соединения без VPN» не включайте: банки и Госуслуги останутся без интернета.",
                )
            }
            item {
                InsetGroup(Modifier.androidPaddingTop()) {
                    ListRow(
                        title = "Работа в фоне",
                        value = if (batteryUnrestricted) "Разрешено" else "Нужно разрешить",
                        valueColor = if (batteryUnrestricted) kc.green else kc.orange,
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_battery_ios, TileOrange) },
                        onClick = { requestUnrestrictedBattery(context) },
                    )
                }
            }
            if (!batteryUnrestricted) {
                item { SectionFooter("Без этого Xiaomi, Huawei, Samsung и другие могут выключать VPN в фоне.") }
            }

            item { SectionHeader("Если что-то не работает") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Журнал",
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_list_ios, TileGray) },
                        onClick = { onNavigate("logs") },
                    )
                    RowDivider(start = 57.dp)
                    val date = if (geoVersion > 0) "от " + SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(geoVersion * 1000)) else null
                    ListRow(
                        title = "Обновить списки российских сайтов",
                        value = date,
                        leading = { IconTile(R.drawable.ic_refresh_ios, TileBlue) },
                        onClick = { confirmGeo = true },
                    )
                }
            }

            item { SectionHeader("О приложении") }
            item {
                InsetGroup {
                    ListRow(
                        title = "Klaus VPN",
                        value = BuildConfig.VERSION_NAME,
                        leading = { IconTile(R.drawable.ic_tab_vpn, TileGreen) },
                    )
                    RowDivider(start = 57.dp)
                    ListRow(
                        title = "Ядро Xray",
                        value = coreVersion.ifBlank { "—" },
                        leading = { IconTile(R.drawable.ic_chip_ios, TileGray) },
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
            confirmButton = { TextButton(onClick = { confirmGeo = false; vm.updateGeo() }) { Text("Обновить", color = kc.green) } },
            dismissButton = { TextButton(onClick = { confirmGeo = false }) { Text("Отмена", color = kc.green) } },
        )
    }
}

/** Space between two groups that belong to one section. */
private fun Modifier.androidPaddingTop(): Modifier = this.then(Modifier.padding(top = 16.dp))

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
