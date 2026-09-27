package com.klausms.vpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.klausms.vpn.R
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.ui.PingResult
import com.klausms.vpn.ui.components.CircleFlag
import com.klausms.vpn.ui.components.Countries
import com.klausms.vpn.ui.components.IosAlert
import com.klausms.vpn.ui.components.IosIcon
import com.klausms.vpn.ui.components.PrimaryButton
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SecondaryButton
import com.klausms.vpn.ui.components.SectionFooter
import com.klausms.vpn.ui.components.SectionHeader
import com.klausms.vpn.ui.components.SignalBars
import com.klausms.vpn.ui.components.TextAction
import com.klausms.vpn.ui.components.groupRow
import com.klausms.vpn.ui.components.pressScale
import com.klausms.vpn.ui.components.tap
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.formatBytes
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** What the server list can ask for. */
class ServerActions(
    val select: (String) -> Unit,
    val ping: (String) -> Unit,
    val pingAll: () -> Unit,
    val rename: (id: String, name: String) -> Unit,
    val delete: (String) -> Unit,
    val refreshSubscription: (String) -> Unit,
    val deleteSubscription: (String) -> Unit,
)

/**
 * The server list of the main screen as lazy items: own keys and
 * subscriptions, tap to pick, long press for more. One lazy item per
 * server, so big subscriptions stay smooth.
 */
fun LazyListScope.serverSections(
    profiles: ProfilesState,
    pings: Map<String, PingResult>,
    whitelist: Map<String, Int>,
    actions: ServerActions,
    onRename: (StoredProfile) -> Unit,
    onAdd: () -> Unit,
    /** Imports what is in the clipboard; false when it holds no text. */
    onPaste: suspend () -> Boolean = { false },
    /** Opens the QR scanner; null without a camera. */
    onScan: (() -> Unit)? = null,
) {
    val subIds = profiles.subscriptions.map { it.id }.toSet()
    // Servers whose subscription is gone are treated as own keys.
    val own = profiles.profiles.filter { it.subscriptionId == null || it.subscriptionId !in subIds }

    item(key = "servers-title") {
        ServersTitle(showPingAll = profiles.profiles.isNotEmpty(), onPingAll = actions.pingAll)
    }
    if (profiles.profiles.isEmpty()) {
        item(key = "servers-empty") { EmptyServers(onAdd, onPaste, onScan) }
        return
    }
    if (own.isNotEmpty()) {
        // A heading only when there is more than one group.
        if (profiles.subscriptions.isNotEmpty()) item(key = "own-header") { SectionHeader("Мои ключи") }
        itemsIndexed(own, key = { _, p -> p.id }) { i, p ->
            Column(Modifier.groupRow(first = i == 0, last = i == own.lastIndex).background(kc.card)) {
                if (i > 0) RowDivider(start = FLAG_TEXT_START)
                ServerRow(
                    profile = p,
                    selected = p.id == profiles.selectedId,
                    ping = pings[p.id],
                    whitelisted = whitelist[p.address] == 1,
                    canDelete = true,
                    actions = actions,
                    onRename = { onRename(p) },
                )
            }
        }
    }
    for (sub in profiles.subscriptions) {
        val servers = profiles.profiles.filter { it.subscriptionId == sub.id }
        item(key = "h-${sub.id}") { SectionHeader(sub.name) }
        item(key = "g-${sub.id}") {
            Column(Modifier.groupRow(first = true, last = servers.isEmpty()).background(kc.card)) {
                SubscriptionHeader(sub, onRefresh = { actions.refreshSubscription(sub.id) }, onDelete = { actions.deleteSubscription(sub.id) })
            }
        }
        itemsIndexed(servers, key = { _, p -> "${sub.id}/${p.id}" }) { i, p ->
            Column(Modifier.groupRow(first = false, last = i == servers.lastIndex).background(kc.card)) {
                RowDivider(start = if (i == 0) 16.dp else FLAG_TEXT_START)
                ServerRow(
                    profile = p,
                    selected = p.id == profiles.selectedId,
                    ping = pings[p.id],
                    whitelisted = whitelist[p.address] == 1,
                    canDelete = false,
                    actions = actions,
                    onRename = { onRename(p) },
                )
            }
        }
        item(key = "f-${sub.id}") {
            SectionFooter(sub.lastError?.let { "Не удалось обновить: $it" } ?: updatedText(sub.updatedAt))
        }
    }
    if (profiles.profiles.any { whitelist[it.address] == 1 }) {
        item(key = "whitelist-note") {
            SectionFooter("Серверы с пометкой «белый список» работают, даже когда мобильный интернет ограничен.")
        }
    }
}

/** "Серверы" with the check-all button. */
@Composable
private fun ServersTitle(showPingAll: Boolean, onPingAll: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 20.dp, top = 12.dp, bottom = 2.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Серверы", style = IosType.title3, color = kc.label, modifier = Modifier.weight(1f))
        if (showPingAll) RoundIconButton(R.drawable.ic_gauge_ios, "Проверить все серверы", onPingAll)
    }
}

/**
 * The first screen: a key or a subscription link copied from a messenger
 * goes in with one tap, or from a QR code; typing is the last resort.
 */
@Composable
private fun EmptyServers(onAdd: () -> Unit, onPaste: suspend () -> Boolean, onScan: (() -> Unit)?) {
    var clipboardEmpty by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(kc.card)
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Добавьте сервер", style = IosType.headline, color = kc.label)
        Spacer(Modifier.height(6.dp))
        Text(
            if (onScan != null) {
                "Скопируйте ключ (vless://…) или ссылку на подписку и нажмите «Вставить из буфера». Или отсканируйте QR-код."
            } else {
                "Скопируйте ключ (vless://…) или ссылку на подписку и нажмите «Вставить из буфера»."
            },
            style = IosType.subhead,
            color = kc.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Вставить из буфера", onClick = { scope.launch { clipboardEmpty = !onPaste() } }, icon = R.drawable.ic_clipboard_ios)
        if (clipboardEmpty) {
            Text(
                "В буфере обмена нет текста: сначала скопируйте ключ или ссылку",
                style = IosType.footnote,
                color = kc.orange,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (onScan != null) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Сканировать QR-код", onClick = { clipboardEmpty = false; onScan() }, icon = R.drawable.ic_qr_ios)
        }
        Spacer(Modifier.height(4.dp))
        TextAction("Ввести вручную", onClick = onAdd, style = IosType.subhead)
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
    actions: ServerActions,
    onRename: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val code = Countries.codeFrom(profile.name)
    Box {
        com.klausms.vpn.ui.components.ListRow(
            title = Countries.stripFlags(profile.name),
            modifier = Modifier.semantics { this.selected = selected },
            subtitle = describe(profile),
            badge = if (whitelisted) ({ WhitelistBadge() }) else null,
            titleMaxLines = 1,
            leading = { CircleFlag(code, 32.dp) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RowPing(ping)
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                        if (selected) IosIcon(R.drawable.ic_checkmark_ios, kc.green, Modifier.size(width = 16.dp, height = 14.dp))
                    }
                }
            },
            onClick = { actions.select(profile.id) },
            onLongClick = { menu = true },
            minHeight = 64.dp,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = kc.cardPressed) {
            MenuItem("Проверить отклик", R.drawable.ic_gauge_ios) { menu = false; actions.ping(profile.id) }
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
        IosAlert(
            title = "Удалить «${Countries.stripFlags(profile.name)}»?",
            onDismiss = { confirmDelete = false },
            confirm = "Удалить",
            onConfirm = { confirmDelete = false; actions.delete(profile.id) },
            destructive = true,
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

/** Where row text starts after a 32 dp flag: 16 + 32 + 12. */
private val FLAG_TEXT_START = 60.dp

@Composable
private fun RowPing(ping: PingResult?) {
    when (ping) {
        is PingResult.Ok -> {
            // Graded like the widget (pingGrade), drawn in the app's own bars and colours.
            val (level, color) = pingLevel(ping.ms)
            SignalBars(level, color)
            Text(
                "${ping.ms} мс",
                style = IosType.subhead.copy(fontFeatureSettings = "tnum"),
                color = if (level >= 3) kc.secondary else color,
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
    var menu by remember { mutableStateOf(false) }
    val usage = parseUserInfo(sub.userInfo)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box {
        // Tap or long press: refresh or delete the subscription.
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (pressed) kc.cardPressed else Color.Transparent)
                .combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onClickLabel = "Действия с подпиской",
                    onClick = { menu = true },
                    onLongClick = { menu = true },
                )
                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    usage?.text ?: "Подписка",
                    style = IosType.subhead,
                    color = if (usage?.expired == true) kc.red else kc.label,
                    maxLines = 2,
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
                // What the panel said instead of servers (device limit,
                // expired, ...): the servers from before stay usable.
                sub.notice?.let { notice ->
                    Spacer(Modifier.height(6.dp))
                    Text(notice, style = IosType.footnote, color = kc.orange, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                sub.announce?.let { announce ->
                    Spacer(Modifier.height(6.dp))
                    Text(announce, style = IosType.footnote, color = kc.secondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(R.drawable.ic_refresh_ios, "Обновить подписку", onRefresh)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = kc.cardPressed) {
            MenuItem("Обновить", R.drawable.ic_refresh_ios) { menu = false; onRefresh() }
            MenuItem("Удалить подписку", R.drawable.ic_trash_ios, destructive = true) { menu = false; confirmDelete = true }
        }
    }
    if (confirmDelete) {
        IosAlert(
            title = "Удалить подписку «${sub.name}» со всеми серверами?",
            onDismiss = { confirmDelete = false },
            confirm = "Удалить",
            onConfirm = { confirmDelete = false; onDelete() },
            destructive = true,
        )
    }
}

/** A 34 dp filled circle inside a 44 dp touch target, as in the mockup. */
@Composable
private fun RoundIconButton(icon: Int, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source)
    Box(
        Modifier
            .size(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .tap(source, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(kc.fill), contentAlignment = Alignment.Center) {
            IosIcon(icon, kc.green, Modifier.size(17.dp))
        }
    }
}

private class Usage(val text: String, val fraction: Float?, val expired: Boolean = false)

/** "25 октября", with the year when it is not this one. */
private fun russianDate(millis: Long): String {
    val ru = Locale.forLanguageTag("ru")
    val year = SimpleDateFormat("yyyy", ru)
    val sameYear = year.format(Date(millis)) == year.format(Date())
    return SimpleDateFormat(if (sameYear) "d MMMM" else "d MMMM yyyy", ru).format(Date(millis))
}

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
    val expired = expire > 0 && expire * 1000 < System.currentTimeMillis()
    if (expire > 0) parts += (if (expired) "истекла " else "до ") + russianDate(expire * 1000)
    if (parts.isEmpty()) return null
    return Usage(parts.joinToString(" · "), if (total > 0) (used.toDouble() / total).toFloat() else null, expired)
}

@Composable
internal fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    IosAlert(
        title = "Название сервера",
        onDismiss = onDismiss,
        confirm = "Готово",
        onConfirm = { onRename(text.trim()) },
        confirmEnabled = text.isNotBlank(),
        content = {
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
    )
}
