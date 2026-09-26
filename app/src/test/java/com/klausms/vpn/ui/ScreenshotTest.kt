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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.service.VpnStatus
import com.klausms.vpn.ui.screens.ServerActions
import com.klausms.vpn.ui.screens.HomeContent
import com.klausms.vpn.ui.screens.SettingsContent
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
// Android 15: the Android 16 runtime needs JDK internals Robolectric cannot always reach.
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi", application = Application::class)
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
    private val whitelist = mapOf("ru.example.com" to 1)
    private val noServerActions = ServerActions({}, {}, {}, { _, _ -> }, {}, {}, {})

    // ------------------------------------------------------------ screens

    @Test fun homeOff() = home("home-off", VpnStatus(VpnState.DISCONNECTED))

    @Test fun homeConnected() = home("home-connected", connected())

    @Test fun homeConnecting() = home("home-connecting", VpnStatus(VpnState.CONNECTING, profileId = "nl"))

    @Test fun homeError() = home(
        "home-error",
        VpnStatus(VpnState.ERROR, message = "Сервер не отвечает. Проверьте ключ или выберите другой сервер."),
    )

    @Test fun homeNoServers() = home("home-no-servers", VpnStatus(), ProfilesState())

    @Test
    @Config(qualifiers = "w360dp-h720dp-xxhdpi")
    fun homeConnectedSmallPhone() = home("home-connected-small-phone", connected())

    @Test fun homeConnectedLargeFont() = home("home-connected-font-130", connected(), fontScale = 1.3f)

    /** Scrolled down to the servers: the map fades, a small title appears. */
    @Test fun homeScrolled() = home("home-scrolled", connected(), scrollTo = 4)

    @Test fun settings() = shot("settings") {
        SettingsContent(
            settings = AppSettings(excludedApps = setOf("ru.sberbankmobile", "ru.gosuslugi")),
            geoVersion = 1_790_000_000L,
            onRussianApps = {},
            onUpdateGeo = {},
            onBack = {},
            onNavigate = {},
        )
    }

    private fun connected() = VpnStatus(
        VpnState.CONNECTED, profileId = "nl", profileName = nl.name,
        connectedSince = System.currentTimeMillis() - (1 * 3600 + 23 * 60 + 45) * 1000L,
    )

    private fun home(
        name: String,
        status: VpnStatus,
        state: ProfilesState = profiles,
        fontScale: Float = 1f,
        scrollTo: Int? = null,
    ) = shot(name, fontScale, scrollTo) {
        HomeContent(
            status = status,
            profiles = state,
            pings = pings,
            whitelist = whitelist,
            actions = noServerActions,
            onToggle = {},
            onAdd = {},
            onOpenSettings = {},
            onPing = {},
        )
    }

    private fun shot(name: String, fontScale: Float = 1f, scrollTo: Int? = null, content: @Composable () -> Unit) {
        // Manual clock: the session timer ticks forever and would never idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            // The system font size setting, as the app would see it.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
                KlausTheme {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
        repeat(2) { compose.mainClock.advanceTimeBy(500) }
        if (scrollTo != null) compose.onNode(hasScrollToIndexAction()).performScrollToIndex(scrollTo)
        repeat(2) { compose.mainClock.advanceTimeBy(500) }
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
