package com.klausms.vpn.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.BuildConfig
import com.klausms.vpn.R
import com.klausms.vpn.data.AccountStatus
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.ui.AccountView
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.components.IconTile
import com.klausms.vpn.ui.components.InsetGroup
import com.klausms.vpn.ui.components.IosAlert
import com.klausms.vpn.ui.components.IosSwitch
import com.klausms.vpn.ui.components.ListRow
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SectionFooter
import com.klausms.vpn.ui.components.SectionHeader
import com.klausms.vpn.ui.components.NavBar
import com.klausms.vpn.ui.components.navBarClearance
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.PhoneSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TileBlue = Color(0xFF0A84FF)
private val TileIndigo = Color(0xFF5E5CE6)
private val TileGreen = Color(0xFF30D158)
private val TileOrange = Color(0xFFFF9F0A)
private val TileGray = Color(0xFF636366)
private val TileRed = Color(0xFFFF453A)
private val TileTeal = Color(0xFF40C8E0)

/**
 * Settings in iOS style. Only what a person can understand and may want to
 * change: the smart defaults (Russian sites direct, instant recovery when
 * the network changes, IPv6 kept inside the tunnel) are not settings.
 */
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val geoVersion by vm.geoVersion.collectAsStateWithLifecycle()
    val geoUpdating by vm.geoUpdating.collectAsStateWithLifecycle()
    val account by vm.accountView.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        geoVersion = geoVersion,
        account = account,
        onRussianApps = { v -> vm.updateSettings { it.copy(bypassRussianApps = v) } },
        onUpdateGeo = { vm.updateGeo() },
        onBack = onBack,
        onNavigate = onNavigate,
        geoUpdating = geoUpdating,
    )
}

@Composable
fun SettingsContent(
    settings: AppSettings,
    geoVersion: Long,
    /** Shown first when the build has accounts. */
    account: AccountView = AccountView(),
    onRussianApps: (Boolean) -> Unit,
    onUpdateGeo: () -> Unit,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    /** «Обновить списки» is running: not offered again until it ends. */
    geoUpdating: Boolean = false,
) {
    val context = LocalContext.current
    var confirmGeo by remember { mutableStateOf(false) }
    // What the phone allows in the background; read again on return from
    // the system screens these rows open.
    var batteryUnrestricted by remember { mutableStateOf(PhoneSettings.batteryUnrestricted(context)) }
    var backgroundRestricted by remember { mutableStateOf(PhoneSettings.backgroundRestricted(context)) }
    var notificationsOn by remember { mutableStateOf(PhoneSettings.notificationsEnabled(context)) }
    val oem = remember { PhoneSettings.oem() }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = PhoneSettings.batteryUnrestricted(context)
        backgroundRestricted = PhoneSettings.backgroundRestricted(context)
        notificationsOn = PhoneSettings.notificationsEnabled(context)
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(kc.page)) {
        NavBar("Настройки", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = navBarClearance() + 24.dp)) {

            if (account.available) {
                item { SectionHeader("Аккаунт") }
                item {
                    InsetGroup {
                        ListRow(
                            title = account.email.ifEmpty { "Войти или создать аккаунт" },
                            subtitle = when (account.status) {
                                AccountStatus.SIGNED_OUT -> "Серверы сами появятся на всех ваших устройствах"
                                AccountStatus.UNCONFIRMED -> "Подтвердите почту"
                                AccountStatus.PENDING -> "Ждёт доступа"
                                AccountStatus.ACTIVE -> "Доступ есть"
                                AccountStatus.REJECTED -> "Доступ не выдан"
                            },
                            chevron = true,
                            leading = { IconTile(R.drawable.ic_person_ios, TileBlue) },
                            onClick = { onNavigate("account") },
                        )
                    }
                }
            }

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
                        onClick = { PhoneSettings.open(context, Intent(Settings.ACTION_VPN_SETTINGS)) },
                    )
                    RowDivider(start = 57.dp)
                    ListRow(
                        title = "Работа в фоне",
                        value = when {
                            backgroundRestricted -> "Ограничено"
                            batteryUnrestricted -> "Разрешено"
                            else -> "Разрешить"
                        },
                        valueColor = if (batteryUnrestricted && !backgroundRestricted) kc.secondary else kc.orange,
                        chevron = true,
                        leading = { IconTile(R.drawable.ic_battery_ios, TileOrange) },
                        onClick = { PhoneSettings.openBatterySettings(context) },
                    )
                    // Xiaomi, Huawei and others have a switch of their own
                    // that Android cannot read: always shown there.
                    if (oem != null) {
                        RowDivider(start = 57.dp)
                        ListRow(
                            title = "Автозапуск",
                            subtitle = oem.hint,
                            chevron = true,
                            leading = { IconTile(R.drawable.ic_autostart_ios, TileTeal) },
                            onClick = { PhoneSettings.openAutostart(context, oem) },
                        )
                    }
                    if (!notificationsOn) {
                        RowDivider(start = 57.dp)
                        ListRow(
                            title = "Уведомления",
                            value = "Выключены",
                            valueColor = kc.orange,
                            chevron = true,
                            leading = { IconTile(R.drawable.ic_bell_ios, TileRed) },
                            onClick = { PhoneSettings.openNotificationSettings(context) },
                        )
                    }
                }
            }
            item {
                SectionFooter(
                    buildString {
                        when {
                            backgroundRestricted -> append("Работа в фоне ограничена: в настройках приложения откройте «Батарея» и выберите «Без ограничений», иначе телефон может выключать VPN. ")
                            !batteryUnrestricted -> append("Разрешите работу в фоне, иначе телефон может выключать VPN. ")
                        }
                        if (oem != null) append("На этом телефоне включите и «Автозапуск», а в списке недавних приложений закрепите Kirov VPN замком. ")
                        if (!notificationsOn) append("Без уведомлений вы не узнаете, что VPN выключился. ")
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
                        subtitle = if (geoUpdating) "Обновляются…" else listsDate(geoVersion)?.let { "Обновлены $it" },
                        leading = { IconTile(R.drawable.ic_refresh_ios, TileBlue) },
                        onClick = if (geoUpdating) null else ({ confirmGeo = true }),
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
        IosAlert(
            title = "Обновить списки?",
            text = "Будет скачано около 90 МБ (лучше по Wi-Fi). Приложение оставит только нужное, проверит файлы и применит их.",
            onDismiss = { confirmGeo = false },
            confirm = "Обновить",
            onConfirm = { confirmGeo = false; onUpdateGeo() },
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
