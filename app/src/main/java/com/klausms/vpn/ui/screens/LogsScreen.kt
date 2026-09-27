package com.klausms.vpn.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.klausms.vpn.ui.MainViewModel
import com.klausms.vpn.ui.components.navBarClearance
import com.klausms.vpn.ui.components.NavBar
import com.klausms.vpn.ui.components.TextAction
import com.klausms.vpn.ui.theme.kc
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.PhoneSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun readLogs(context: Context): String {
    val dir = AppLog.logDir(context)
    val sections = listOf(
        "Приложение (VPN)" to listOf("app-vpn.log.1", "app-vpn.log"),
        "Приложение (интерфейс)" to listOf("app-ui.log.1", "app-ui.log"),
        "Ядро Xray" to listOf("xray.log.1", "xray.log"),
        "Сбои ядра" to listOf("go-crash.log.1", "go-crash.log"),
    )
    // The runtime writes a crash report without a time: the file's own
    // time tells when the last one happened.
    val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)
    fun lines(name: String): List<String> {
        val f = File(dir, name)
        // Only the end of each file: the core's log can grow large.
        val body = AppLog.tail(f, 300)
        if (body.isEmpty() || !name.startsWith("go-crash")) return body
        return listOf("-- $name, записан ${stamp.format(Date(f.lastModified()))}") + body
    }
    return buildString {
        // The phone and what it allows in the background: most "the VPN
        // turned itself off" reports come down to these.
        append("== Телефон ==\n")
        append(
            try {
                PhoneSettings.summary(context)
            } catch (e: Exception) {
                "(не удалось прочитать: ${e.javaClass.simpleName})"
            },
        ).append("\n\n")
        for ((title, files) in sections) {
            val lines = files.flatMap { lines(it) }.takeLast(300)
            append("== ").append(title).append(" ==\n")
            if (lines.isEmpty()) append("(пусто)\n") else lines.forEach { append(it).append('\n') }
            append('\n')
        }
    }
}

@Composable
fun LogsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceState("Загрузка…") { value = withContext(Dispatchers.IO) { readLogs(context) } }
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(kc.page)) {
        NavBar("Журнал", onBack, backLabel = "Настройки") {
            TextAction(if (copied) "Скопировано" else "Копировать", onClick = {
                copySensitive(context, text)
                copied = true
            })
        }
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = kc.secondary,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + navBarClearance()),
        )
    }
}
