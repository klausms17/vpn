package com.klausms.vpn.ui.screens

import android.content.Context
import android.content.Intent
import android.util.LruCache
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.components.IosIcon
import com.klausms.vpn.ui.components.IosSwitch
import com.klausms.vpn.ui.components.ListRow
import com.klausms.vpn.ui.components.NavBar
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SectionFooter
import com.klausms.vpn.ui.components.groupRow
import com.klausms.vpn.ui.components.navBarClearance
import com.klausms.vpn.ui.theme.IosType
import com.klausms.vpn.ui.theme.kc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

private data class AppEntry(val pkg: String, val label: String)

/**
 * A few icons at a time: a fast fling over hundreds of apps must not start
 * dozens of decodes at once on a small phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val iconLoader = Dispatchers.IO.limitedParallelism(4)

/** Decoded icons, capped by size (a phone can have hundreds of apps). */
private class IconCache : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@Composable
fun AppsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { loadApps(context) } }
    var query by rememberSaveable { mutableStateOf("") }
    val excluded = settings.excludedApps
    val icons = remember { IconCache() }
    val activity = LocalActivity.current

    // The tunnel takes the new list once, when this screen closes or the
    // app goes to the background (e.g. Home, to open the app just excluded).
    // Not on a rotation: the screen comes back at once with the list open.
    fun applyUnlessRotating() {
        if (activity?.isChangingConfigurations != true) vm.applyAppLists()
    }
    DisposableEffect(vm) { onDispose { applyUnlessRotating() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { applyUnlessRotating() }

    fun toggle(pkg: String) = vm.updateAppLists { s ->
        s.copy(excludedApps = if (pkg in s.excludedApps) s.excludedApps - pkg else s.excludedApps + pkg)
    }

    Column(Modifier.fillMaxSize().background(kc.page)) {
        NavBar("Без VPN", onBack, backLabel = "Настройки")
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = kc.secondary)
            }
            return@Column
        }
        val q = query.trim().lowercase()
        // Checked apps go first, but only as they were when the screen opened
        // (or the search changed): rows must not jump under the finger.
        val sortExcluded = remember(q) { excluded }
        val shown = list
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
            .sortedByDescending { it.pkg in sortExcluded }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + navBarClearance())) {
            item {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    singleLine = true,
                    textStyle = IosType.body.copy(color = kc.label),
                    cursorBrush = SolidColor(kc.green),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(kc.fill)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    decorationBox = { inner ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IosIcon(R.drawable.ic_search, kc.secondary, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Box {
                                if (query.isEmpty()) Text("Поиск", style = IosType.body, color = kc.secondary)
                                inner()
                            }
                        }
                    },
                )
            }
            item {
                SectionFooter(
                    "Отмеченные приложения работают без VPN и не видят его. Российские банки и Госуслуги уже работают без VPN, если включён этот режим в настройках.\n\n" +
                        "Если видеорегистратор, умный дом (Mi Home) или Android Auto не подключается к своей Wi-Fi-сети, отметьте здесь его приложение.",
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
            // One lazy item per app (a phone can have hundreds), drawn as
            // one inset group: rounded corners on the first and last row.
            itemsIndexed(shown, key = { _, app -> app.pkg }) { i, app ->
                Column(Modifier.groupRow(first = i == 0, last = i == shown.lastIndex).background(kc.card)) {
                    if (i > 0) RowDivider(start = 68.dp)
                    val checked = app.pkg in excluded
                    ListRow(
                        title = app.label,
                        leading = { AppIcon(context, app.pkg, icons) },
                        trailing = {
                            IosSwitch(checked = checked, onCheckedChange = { toggle(app.pkg) }, description = app.label)
                        },
                        onClick = { toggle(app.pkg) },
                        minHeight = 56.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(context: Context, pkg: String, cache: IconCache) {
    // Decoded at the size it is drawn at, not larger.
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(cache.get(pkg), pkg) {
        if (value == null) {
            value = withContext(iconLoader) {
                try {
                    // Cached here, so a row scrolled away meanwhile does not waste the work.
                    context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap().also { cache.put(pkg, it) }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    val b = bitmap
    if (b != null) Image(b, null, Modifier.size(40.dp)) else Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)).background(kc.cardPressed))
}
