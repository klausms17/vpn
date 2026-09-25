package com.klausms.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.PingResult
import com.klausms.vpn.ui.components.CircleFlag
import com.klausms.vpn.ui.components.Countries
import com.klausms.vpn.ui.components.GlassIconButton
import com.klausms.vpn.ui.components.InsetGroup
import com.klausms.vpn.ui.components.IosIcon
import com.klausms.vpn.ui.components.LargeTitle
import com.klausms.vpn.ui.components.PrimaryButton
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SectionFooter
import com.klausms.vpn.ui.components.SectionHeader
import com.klausms.vpn.ui.components.SignalBars
import com.klausms.vpn.ui.components.TabBarSpace
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.formatBytes
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The Servers tab: own keys and subscriptions, pick one, check latency. */
@Composable
fun ServersScreen(vm: MainViewModel, onAdd: () -> Unit) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val pings by vm.pings.collectAsStateWithLifecycle()
    val whitelist by vm.whitelist.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<StoredProfile?>(null) }

    val subIds = profiles.subscriptions.map { it.id }.toSet()
    // Servers whose subscription is gone are treated as own keys.
    val own = profiles.profiles.filter { it.subscriptionId == null || it.subscriptionId !in subIds }

    Box(Modifier.fillMaxSize().background(kc.page)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = TabBarSpace + 16.dp)) {
            item {
                LargeTitle("Серверы") {
                    if (profiles.profiles.isNotEmpty()) {
                        GlassIconButton(R.drawable.ic_gauge_ios, "Проверить все серверы", onClick = { vm.pingAll() })
                    }
                    GlassIconButton(R.drawable.ic_plus_ios, "Добавить сервер", onClick = onAdd, fill = kc.cta, iconSize = 18.dp)
                }
            }
            if (profiles.profiles.isEmpty()) {
                item { EmptyServers(onAdd) }
            }
            if (own.isNotEmpty()) {
                item { SectionHeader("Мои ключи") }
                item {
                    InsetGroup {
                        own.forEachIndexed { i, p ->
                            if (i > 0) RowDivider(start = 72.dp)
                            ServerRow(
                                profile = p,
                                selected = p.id == profiles.selectedId,
                                ping = pings[p.id],
                                whitelisted = whitelist[p.address] == 1,
                                canDelete = true,
                                vm = vm,
                                onRename = { renameTarget = p },
                            )
                        }
                    }
                }
            }
            for (sub in profiles.subscriptions) {
                val servers = profiles.profiles.filter { it.subscriptionId == sub.id }
                item(key = "h-${sub.id}") { SectionHeader(sub.name) }
                item(key = "g-${sub.id}") {
                    InsetGroup {
                        SubscriptionHeader(sub, onRefresh = { vm.refreshSubscription(sub.id) }, onDelete = { vm.deleteSubscription(sub.id) })
                        servers.forEach { p ->
                            RowDivider(start = if (servers.first() == p) 16.dp else 72.dp)
                            ServerRow(
                                profile = p,
                                selected = p.id == profiles.selectedId,
                                ping = pings[p.id],
                                whitelisted = whitelist[p.address] == 1,
                                canDelete = false,
                                vm = vm,
                                onRename = { renameTarget = p },
                            )
                        }
                    }
                }
                item(key = "f-${sub.id}") {
                    SectionFooter(sub.lastError?.let { "Не удалось обновить: $it" } ?: updatedText(sub.updatedAt))
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(Countries.stripFlags(target.name), onDismiss = { renameTarget = null }) { name ->
            // Keep the flag emoji, which carries the country.
            val flag = Countries.codeFrom(target.name)?.let { Countries.flag(it) + " " }.orEmpty()
            vm.rename(target.id, flag + name)
            renameTarget = null
        }
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Пока нет ни одного сервера", style = IosType.title3, color = kc.label)
        Spacer(Modifier.height(8.dp))
        Text(
            "Вставьте ключ (vless://…) или ссылку на подписку. Можно также «Поделиться» ключом из мессенджера в это приложение.",
            style = IosType.subhead,
            color = kc.secondary,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Добавить сервер", onClick = onAdd, icon = R.drawable.ic_plus_ios)
    }
}

private fun updatedText(at: Long): String {
    if (at <= 0) return "Ещё не обновлялась"
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val now = Calendar.getInstance()
    val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
    return if (sameDay) "Обновлено сегодня в $time" else "Обновлено " + SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(at)) + " в $time"
}

/** "VLESS · REALITY", with the transport when it is not the default. */
fun describe(p: StoredProfile): String {
    val proto = when (p.protocol) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        "hysteria2" -> "Hysteria2"
        else -> p.protocol.uppercase(Locale.ROOT)
    }
    val parts = mutableListOf(proto)
    when (p.security) {
        "reality" -> parts += "REALITY"
        "tls" -> parts += "TLS"
    }
    if (p.network.isNotEmpty() && p.network != "raw" && p.network != "tcp" && p.network != "hysteria") parts += p.network.uppercase(Locale.ROOT)
    return parts.joinToString(" · ")
}

@Composable
private fun ServerRow(
    profile: StoredProfile,
    selected: Boolean,
    ping: PingResult?,
    whitelisted: Boolean,
    canDelete: Boolean,
    vm: MainViewModel,
    onRename: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val code = Countries.codeFrom(profile.name)
    Box {
        com.klausms.vpn.ui.components.ListRow(
            title = Countries.stripFlags(profile.name),
            subtitle = describe(profile),
            leading = { CircleFlag(code) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (whitelisted) WhitelistBadge()
                    RowPing(ping)
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                        if (selected) IosIcon(R.drawable.ic_checkmark_ios, kc.green, Modifier.size(width = 16.dp, height = 14.dp))
                    }
                }
            },
            onClick = { vm.select(profile.id) },
            onLongClick = { menu = true },
            minHeight = 64.dp,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = kc.cardPressed) {
            MenuItem("Проверить отклик", R.drawable.ic_gauge_ios) { menu = false; vm.ping(listOf(profile.id)) }
            MenuItem("Переименовать", R.drawable.ic_pencil_ios) { menu = false; onRename() }
            profile.link?.let { link ->
                MenuItem("Скопировать ключ", R.drawable.ic_copy_ios) { menu = false; copySensitive(context, link) }
            }
            if (canDelete) {
                MenuItem("Удалить", R.drawable.ic_trash_ios, destructive = true) { menu = false; confirmDelete = true }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = kc.card,
            title = { Text("Удалить «${Countries.stripFlags(profile.name)}»?", style = IosType.headline, color = kc.label) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(profile.id) }) { Text("Удалить", color = kc.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена", color = kc.green) } },
        )
    }
}

@Composable
private fun MenuItem(text: String, icon: Int, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) kc.red else kc.label
    DropdownMenuItem(
        text = { Text(text, style = IosType.body, color = color) },
        trailingIcon = { IosIcon(icon, color, Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

@Composable
private fun RowPing(ping: PingResult?) {
    when (ping) {
        is PingResult.Ok -> {
            val (level, color) = pingLevel(ping.ms)
            SignalBars(level, if (level >= 3) color else kc.gray)
            Text(
                "${ping.ms} мс",
                style = IosType.subhead.copy(fontFeatureSettings = "tnum"),
                color = if (level >= 3) color else kc.secondary,
                maxLines = 1,
            )
        }
        is PingResult.Failed -> Text("нет связи", style = IosType.subhead, color = kc.red, maxLines = 1)
        PingResult.Testing -> Text("…", style = IosType.subhead, color = kc.secondary)
        null -> Unit
    }
}

@Composable
private fun WhitelistBadge() {
    Text(
        "белый список",
        style = IosType.caption,
        color = Color(0xFF64B5FF),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x290A84FF))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun SubscriptionHeader(sub: Subscription, onRefresh: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    val usage = parseUserInfo(sub.userInfo)
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    usage?.text ?: "Подписка",
                    style = IosType.subhead,
                    color = kc.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (usage?.fraction != null) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(kc.fill)) {
                        Box(
                            Modifier
                                .fillMaxWidth(usage.fraction.coerceIn(0.02f, 1f))
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(if (usage.fraction > 0.9f) kc.orange else kc.green),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            GlassIconButton(R.drawable.ic_refresh_ios, "Обновить подписку", onClick = onRefresh, iconSize = 18.dp)
            Spacer(Modifier.width(6.dp))
            GlassIconButton(R.drawable.ic_trash_ios, "Удалить подписку", onClick = { confirmDelete = true }, tint = kc.red, iconSize = 18.dp)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = kc.card,
            title = { Text("Удалить подписку «${sub.name}» со всеми серверами?", style = IosType.headline, color = kc.label) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить", color = kc.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена", color = kc.green) } },
        )
    }
}

private class Usage(val text: String, val fraction: Float?)

/** "upload=1; download=2; total=3; expire=4" -> "12,4 ГБ из 100 ГБ · до 25.10.2026". */
private fun parseUserInfo(raw: String?): Usage? {
    if (raw.isNullOrBlank()) return null
    val values = raw.split(';').mapNotNull { part ->
        val kv = part.split('=', limit = 2)
        if (kv.size != 2) return@mapNotNull null
        val v = kv[1].trim().toLongOrNull() ?: return@mapNotNull null
        kv[0].trim().lowercase(Locale.ROOT) to v
    }.toMap()
    val used = (values["upload"] ?: 0) + (values["download"] ?: 0)
    val total = values["total"] ?: 0
    val expire = values["expire"] ?: 0
    val parts = mutableListOf<String>()
    if (total > 0) parts += "${formatBytes(used)} из ${formatBytes(total)}" else if (used > 0) parts += formatBytes(used)
    if (expire > 0) parts += "до " + SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(expire * 1000))
    if (parts.isEmpty()) return null
    return Usage(parts.joinToString(" · "), if (total > 0) (used.toDouble() / total).toFloat() else null)
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = kc.card,
        title = { Text("Название сервера", style = IosType.headline, color = kc.label) },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                textStyle = IosType.body.copy(color = kc.label),
                cursorBrush = SolidColor(kc.green),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(kc.fill)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(text.trim()) }, enabled = text.isNotBlank()) { Text("Готово", color = kc.green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = kc.green) } },
    )
}
