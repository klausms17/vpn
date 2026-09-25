package com.klausms.vpn.ui.screens

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.BuildConfig
import com.klausms.vpn.R
import com.klausms.vpn.data.AppMode
import com.klausms.vpn.data.RoutingMode
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val geoVersion by vm.geoVersion.collectAsStateWithLifecycle()
    val coreVersion by vm.coreVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmGeo by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    Scaffold(topBar = { BackTopBar("Настройки", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { BusyBar(busy) }

            item { SectionTitle("Маршрутизация") }
            for (mode in RoutingMode.entries) {
                item {
                    RadioRow(
                        title = mode.title,
                        subtitle = mode.description,
                        selected = settings.mode == mode,
                        onClick = { vm.updateSettings { it.copy(mode = mode) } },
                    )
                }
            }

            item { SectionTitle("Приложения") }
            item {
                SwitchRow(
                    title = "Российские приложения в обход VPN",
                    subtitle = "Банки, Госуслуги, маркетплейсы, операторы и VK работают без VPN и не видят его. Браузеры сюда не входят.",
                    checked = settings.bypassRussianApps,
                    enabled = settings.appMode == AppMode.ALL_EXCEPT,
                    onChange = { v -> vm.updateSettings { it.copy(bypassRussianApps = v) } },
                )
            }
            item {
                RadioRow(
                    title = "Все приложения через VPN, кроме исключённых",
                    selected = settings.appMode == AppMode.ALL_EXCEPT,
                    onClick = { vm.updateSettings { it.copy(appMode = AppMode.ALL_EXCEPT) } },
                )
            }
            item {
                RadioRow(
                    title = "Только выбранные приложения через VPN",
                    selected = settings.appMode == AppMode.ONLY_SELECTED,
                    onClick = { vm.updateSettings { it.copy(appMode = AppMode.ONLY_SELECTED) } },
                )
            }
            item {
                if (settings.appMode == AppMode.ALL_EXCEPT) {
                    NavRow("Исключённые приложения", "Выбрано: ${settings.excludedApps.size}") { onNavigate("apps:exclude") }
                } else {
                    NavRow("Приложения через VPN", "Выбрано: ${settings.includedApps.size}") { onNavigate("apps:include") }
                }
            }

            item { SectionTitle("Свои правила") }
            item { NavRow("Всегда напрямую", "Сайтов и адресов: ${settings.directRules.size}") { onNavigate("rules:DIRECT") } }
            item { NavRow("Всегда через VPN", "Сайтов и адресов: ${settings.proxyRules.size}") { onNavigate("rules:PROXY") } }
            item { NavRow("Блокировать", "Сайтов и адресов: ${settings.blockRules.size}") { onNavigate("rules:BLOCK") } }

            item { SectionTitle("Сеть") }
            item {
                SwitchRow(
                    title = "IPv6",
                    subtitle = "Включайте, только если и сеть, и сервер поддерживают IPv6. Когда выключено, IPv6 не утекает мимо VPN.",
                    checked = settings.ipv6,
                    onChange = { v -> vm.updateSettings { it.copy(ipv6 = v) } },
                )
            }
            item {
                SwitchRow(
                    title = "Быстрое восстановление при смене сети",
                    subtitle = "При переключении Wi-Fi ↔ мобильная сеть соединения сразу пересоздаются, мессенджеры и видео не «зависают».",
                    checked = settings.resetOnNetworkChange,
                    onChange = { v -> vm.updateSettings { it.copy(resetOnNetworkChange = v) } },
                )
            }

            item { SectionTitle("Базы российских сайтов и IP") }
            item {
                val date = if (geoVersion > 0) SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(geoVersion * 1000)) else "—"
                NavRow("Обновить базы", "Текущая версия от $date. Источник: runetfreedom") { confirmGeo = true }
            }

            item { SectionTitle("Надёжность") }
            item {
                NavRow(
                    "Постоянный VPN",
                    "Включите «Постоянная VPN» и «Блокировать соединения без VPN» — VPN запустится сам после перезагрузки, а трафик не пойдёт мимо него.",
                ) { openSystem(context, Intent(Settings.ACTION_VPN_SETTINGS)) }
            }
            item {
                NavRow(
                    "Работа в фоне",
                    if (batteryUnrestricted) "Ограничения батареи сняты" else "Разрешите работу без ограничений, чтобы прошивка (Xiaomi, Huawei, Samsung…) не отключала VPN",
                ) { requestUnrestrictedBattery(context) }
            }

            item { SectionTitle("Диагностика") }
            item {
                SwitchRow(
                    title = "Подробный журнал",
                    subtitle = "Только для поиска проблем: пишет больше данных.",
                    checked = settings.verboseLog,
                    onChange = { v -> vm.updateSettings { it.copy(verboseLog = v) } },
                )
            }
            item { NavRow("Журнал", "Ошибки подключения и работы ядра") { onNavigate("logs") } }

            item { SectionTitle("О приложении") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Klaus VPN ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Ядро Xray $coreVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmGeo) {
        AlertDialog(
            onDismissRequest = { confirmGeo = false },
            title = { Text("Обновить базы?") },
            text = {
                Text("Будет скачано около 90 МБ (лучше по Wi-Fi). Приложение оставит только нужные категории, проверит файлы и применит их.")
            },
            confirmButton = { TextButton(onClick = { confirmGeo = false; vm.updateGeo() }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = { confirmGeo = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Ic(R.drawable.ic_chevron, null)
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    }
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
