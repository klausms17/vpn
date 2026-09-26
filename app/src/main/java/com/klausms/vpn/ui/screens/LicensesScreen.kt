package com.klausms.vpn.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klausms.vpn.ui.components.navBarClearance
import com.klausms.vpn.ui.components.InsetGroup
import com.klausms.vpn.ui.components.ListRow
import com.klausms.vpn.ui.components.NavBar
import com.klausms.vpn.ui.components.RowDivider
import com.klausms.vpn.ui.components.SectionHeader
import com.klausms.vpn.ui.theme.kc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val components = listOf(
    Triple("Xray-core", "Mozilla Public License 2.0", "github.com/XTLS/Xray-core"),
    Triple("Шрифт Inter", "SIL Open Font License 1.1", "rsms.me/inter"),
    Triple("Списки российских сайтов", "runetfreedom / russia-v2ray-rules-dat", "github.com/runetfreedom"),
    Triple("Карта", "Natural Earth, общественное достояние", "naturalearthdata.com"),
    Triple("Jetpack Compose, AndroidX, Kotlin", "Apache License 2.0", "developer.android.com"),
)

private fun readOfl(context: Context): String =
    context.assets.open("licenses/inter-OFL.txt").bufferedReader().use { it.readText() }

@Composable
fun LicensesScreen(coreVersion: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val ofl by produceState("") { value = withContext(Dispatchers.IO) { runCatching { readOfl(context) }.getOrDefault("") } }
    Column(Modifier.fillMaxSize().background(kc.page)) {
        NavBar("Лицензии", onBack, backLabel = "Настройки")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp + navBarClearance())) {
            item { SectionHeader("В приложении использованы") }
            item {
                InsetGroup {
                    components.forEachIndexed { i, (name, license, site) ->
                        if (i > 0) RowDivider()
                        // The core's version is shown here, not among the settings.
                        val title = if (i == 0 && coreVersion.isNotBlank()) "$name $coreVersion" else name
                        ListRow(title = title, subtitle = "$license · $site")
                    }
                }
            }
            if (ofl.isNotEmpty()) {
                item { SectionHeader("Inter — SIL Open Font License") }
                item {
                    Text(
                        ofl,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = kc.secondary,
                        modifier = Modifier.padding(horizontal = 36.dp),
                    )
                }
            }
        }
    }
}
