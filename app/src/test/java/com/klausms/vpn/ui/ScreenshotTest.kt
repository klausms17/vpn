package com.klausms.vpn.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.klausms.vpn.R
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.service.VpnStatus
import com.klausms.vpn.ui.components.TabBar
import com.klausms.vpn.ui.components.TabItem
import com.klausms.vpn.ui.screens.ServerActions
import com.klausms.vpn.ui.screens.ServersContent
import com.klausms.vpn.ui.screens.SettingsContent
import com.klausms.vpn.ui.screens.VpnContent
import com.klausms.vpn.ui.theme.KlausTheme
import com.klausms.vpn.widget.VpnWidget
import kotlinx.serialization.json.JsonArray
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws the real screens and the widget into PNG files (build/screenshots),
 * so the look can be checked without a phone. Not a pass/fail comparison.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File(System.getProperty("screenshots.dir") ?: "build/screenshots").apply { mkdirs() }

    // ------------------------------------------------------------- data

    private fun profile(id: String, name: String, network: String = "raw", security: String = "reality", sub: String? = null) =
        StoredProfile(
            id = id, name = name, protocol = "vless", address = "$id.example.com", port = 443,
            network = network, security = security, link = "vless://x@$id.example.com:443",
            outbounds = JsonArray(emptyList()), subscriptionId = sub,
        )

    private val nl = profile("nl", "🇳🇱 Амстердам")
    private val de = profile("de", "🇩🇪 Франкфурт", network = "xhttp", security = "tls")
    private val fi = profile("fi", "🇫🇮 Хельсинки", sub = "s1")
    private val us = profile("us", "🇺🇸 Нью-Йорк", sub = "s1")
    private val ru = profile("ru", "🇷🇺 Москва — белые списки", sub = "s1")
    private val subscription = Subscription(
        id = "s1", name = "Моя подписка", url = "https://example.com/sub",
        updatedAt = System.currentTimeMillis() - 3_600_000,
        userInfo = "upload=1073741824; download=16106127360; total=107374182400; expire=1790000000",
    )
    private val profiles = ProfilesState(listOf(nl, de, fi, us, ru), listOf(subscription), selectedId = "nl")
    private val pings = mapOf(
        "nl" to PingResult.Ok(48),
        "de" to PingResult.Ok(126),
        "fi" to PingResult.Ok(612),
        "us" to PingResult.Failed("timeout"),
        "ru" to PingResult.Testing,
    )
    private val tabs = listOf(
        TabItem("vpn", "VPN", R.drawable.ic_tab_vpn),
        TabItem("servers", "Серверы", R.drawable.ic_tab_servers),
        TabItem("settings", "Настройки", R.drawable.ic_tab_settings),
    )
    private val noServerActions = ServerActions({}, {}, {}, { _, _ -> }, {}, {}, {})

    // ------------------------------------------------------------ screens

    @Test fun vpnOff() = vpn("vpn-off", VpnStatus(VpnState.DISCONNECTED))

    @Test fun vpnConnected() = vpn("vpn-connected", connected())

    @Test fun vpnConnecting() = vpn("vpn-connecting", VpnStatus(VpnState.CONNECTING, profileId = "nl"))

    @Test fun vpnError() = vpn(
        "vpn-error",
        VpnStatus(VpnState.ERROR, message = "Сервер не отвечает. Проверьте ключ или выберите другой сервер."),
    )

    @Test fun vpnNoServers() = vpn("vpn-no-servers", VpnStatus(), ProfilesState())

    @Test
    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun vpnConnectedSmallPhone() = vpn("vpn-connected-small-phone", connected())

    @Test fun vpnConnectedLargeFont() = vpn("vpn-connected-font-130", connected(), fontScale = 1.3f)

    @Test fun servers() = shot("servers", "servers") {
        ServersContent(profiles, pings, mapOf("ru.example.com" to 1), noServerActions, onAdd = {})
    }

    @Test fun serversEmpty() = shot("servers-empty", "servers") {
        ServersContent(ProfilesState(), emptyMap(), emptyMap(), noServerActions, onAdd = {})
    }

    @Test fun serversLargeFont() = shot("servers-font-130", "servers", fontScale = 1.3f) {
        ServersContent(profiles, pings, mapOf("ru.example.com" to 1), noServerActions, onAdd = {})
    }

    @Test fun settings() = shot("settings", "settings") {
        SettingsContent(
            settings = AppSettings(excludedApps = setOf("ru.sberbankmobile", "ru.gosuslugi")),
            geoVersion = 1_790_000_000L,
            coreVersion = "26.9.9",
            onRussianApps = {},
            onUpdateGeo = {},
            onNavigate = {},
        )
    }

    private fun connected() = VpnStatus(
        VpnState.CONNECTED, profileId = "nl", profileName = nl.name,
        connectedSince = System.currentTimeMillis() - (1 * 3600 + 23 * 60 + 45) * 1000L,
    )

    private fun vpn(name: String, status: VpnStatus, state: ProfilesState = profiles, fontScale: Float = 1f) =
        shot(name, "vpn", fontScale) {
            VpnContent(status, state, pings, onToggle = {}, onOpenServers = {}, onAddServer = {}, onPing = {})
        }

    private fun shot(name: String, tab: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        // Manual clock: the session timer ticks forever and would never idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // The system font size setting, as the app would see it.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
                KlausTheme {
                    Box(Modifier.fillMaxSize()) {
                        content()
                        TabBar(tabs, tab, onSelect = {}, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
        repeat(4) { compose.mainClock.advanceTimeBy(500) }
        val bitmap = try {
            compose.onRoot().captureToImage().asAndroidBitmap()
        } catch (e: Throwable) {
            System.err.println("captureToImage failed for $name, drawing the view instead: $e")
            draw(compose.activity.window.decorView)
        }
        save(name, bitmap)
    }

    // ------------------------------------------------------------- widget

    @Test
    fun widget() {
        val context: Context = RuntimeEnvironment.getApplication()
        val since = System.currentTimeMillis() - (2 * 3600 + 5 * 60 + 12) * 1000L
        val cases = listOf(
            Triple("on", VpnState.CONNECTED, 48L),
            Triple("on-slow", VpnState.CONNECTED, 2753L),
            Triple("off", VpnState.DISCONNECTED, null),
            Triple("connecting", VpnState.CONNECTING, -2L),
            Triple("error", VpnState.ERROR, -1L),
        )
        for ((label, state, ping) in cases) {
            val views = VpnWidget.previews(context, state, since, nl, ping)
            for ((variant, rv) in views) {
                val (w, h) = when (variant) {
                    "FULL" -> 340 to 150
                    "ROW" -> 320 to 64
                    else -> 150 to 64
                }
                val parent = FrameLayout(context)
                val view = rv.apply(context, parent)
                save("widget-${variant.lowercase()}-$label", onWallpaper(context, view, w, h))
            }
        }
        // No server yet.
        val empty = VpnWidget.previews(context, VpnState.DISCONNECTED, 0, null, null)
        save("widget-full-no-server", onWallpaper(context, empty.getValue("FULL").apply(context, FrameLayout(context)), 340, 150))
    }

    private fun onWallpaper(context: Context, view: View, wDp: Int, hDp: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (wDp * d).toInt()
        val h = (hDp * d).toInt()
        val margin = (16 * d).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w + 2 * margin, h + 2 * margin, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x3A, 0x4E, 0x6B)) // a typical wallpaper tone
        canvas.translate(margin.toFloat(), margin.toFloat())
        view.draw(canvas)
        return bitmap
    }

    // -------------------------------------------------------------- files

    private fun draw(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, w, h, pixels, 0, w)
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }
}
