package com.klausms.vpn.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.klausms.vpn.R
import com.klausms.vpn.data.RussianApps
import com.klausms.vpn.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class AppEntry(val pkg: String, val label: String)

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { AppEntry(it.packageName, pm.getApplicationLabel(it).toString()) }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
        .toList()
}

@Composable
fun AppsScreen(vm: MainViewModel, includeMode: Boolean, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) { value = withContext(Dispatchers.IO) { loadApps(context) } }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = if (includeMode) settings.includedApps else settings.excludedApps
    val icons = remember { HashMap<String, ImageBitmap>() }

    fun toggle(pkg: String) = vm.updateSettings { s ->
        if (includeMode) {
            s.copy(includedApps = if (pkg in s.includedApps) s.includedApps - pkg else s.includedApps + pkg)
        } else {
            s.copy(excludedApps = if (pkg in s.excludedApps) s.excludedApps - pkg else s.excludedApps + pkg)
        }
    }

    Scaffold(
        topBar = {
            BackTopBar(
                if (includeMode) "Приложения через VPN" else "Исключённые из VPN",
                onBack,
            ) {
                if (!includeMode) {
                    TextButton(onClick = {
                        val russian = RussianApps.installed(context.packageManager)
                        vm.updateSettings { it.copy(excludedApps = it.excludedApps + russian) }
                    }) { Text("+ российские") }
                }
            }
        },
    ) { padding ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val q = query.trim().lowercase(Locale.getDefault())
        val shown = list
            .filter { q.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(q) || it.pkg.contains(q) }
            .sortedByDescending { it.pkg in selected }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    if (includeMode) "Через VPN пойдут только отмеченные приложения. Остальные — напрямую."
                    else "Отмеченные приложения работают без VPN и не видят его.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Ic(R.drawable.ic_search) },
                    placeholder = { Text("Поиск") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(shown, key = { it.pkg }) { app ->
                val checked = app.pkg in selected
                Row(
                    Modifier.fillMaxWidth().clickable { toggle(app.pkg) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(context, app.pkg, icons)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            app.pkg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = { toggle(app.pkg) })
                }
            }
        }
    }
}

@Composable
private fun AppIcon(context: Context, pkg: String, cache: HashMap<String, ImageBitmap>) {
    val bitmap by produceState(cache[pkg], pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }?.also { cache[pkg] = it }
        }
    }
    val b = bitmap
    if (b != null) Image(b, null, Modifier.size(40.dp)) else Box(Modifier.size(40.dp))
}
