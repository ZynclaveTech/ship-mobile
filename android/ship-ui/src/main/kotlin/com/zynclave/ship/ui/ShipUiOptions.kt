package com.zynclave.ship.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.zynclave.ship.ShipContent
import com.zynclave.ship.ShipStrings
import com.zynclave.ship.ShipTheme
import java.util.Locale

/**
 * Receives the analytics events the views raise: `sdui_impression`,
 * `sdui_tap`, `sdui_dismiss`, `popup_shown` and `popup_action`. Forward them
 * to whatever the app uses, behind its own consent gate.
 */
fun interface ShipAnalyticsSink {
    fun log(name: String, params: Map<String, String>)
}

/**
 * The ids the backend listed for the signed-in user. Targeted cards and
 * popups show only when named here.
 */
data class ShipEligible(val cards: Set<String>, val popups: Set<String>) {
    companion object {
        val None = ShipEligible(emptySet(), emptySet())
    }
}

/**
 * Everything app-specific the views need. The library never reaches for the
 * app's navigation, strings, colours, user or analytics: it asks here.
 *
 * The functions are called on the main thread, except [eligibility] and
 * [popupGate], which may also be read from a background thread.
 */
data class ShipUiOptions(
    /** The URL scheme a `deeplink` action must use, e.g. `myapp`. */
    val deeplinkScheme: String,
    /**
     * A theme token (`primary`, `accent`, `surface`, `surfaceVariant`,
     * `onSurface`, `onSurfaceVariant`, `onPrimary`) to the app's colour.
     * null falls back to the release's `config/theme.json`, then to a default
     * (Material theme colours for the surface and text tokens).
     */
    val color: (token: String, dark: Boolean) -> Color? = { _, _ -> null },
    /** Opens a `deeplink` action through the app's own routing. */
    val openDeeplink: (Uri) -> Unit,
    /**
     * The language code used to look up a release's OTA strings
     * (`strings/<code>.json`): the app's chosen language. Default: the
     * device's primary language code.
     */
    val language: () -> String = ::deviceLanguage,
    /**
     * A text `key` to the app's own (localised) string, or null to use the
     * definition's `fallback`. Text resolves as: the release's OTA string for
     * [language], then this, then the literal fallback.
     */
    val appString: (key: String) -> String? = { null },
    /** Last step over every text, e.g. to replace `{first_name}`. Default: drops the placeholder, as for a user with no name. */
    val personalize: (String) -> String = { Sdui.personalize(it, firstName = null) },
    /** Targeted cards and popups show only when named here, so the default shows none of them. */
    val eligibility: () -> ShipEligible = { ShipEligible.None },
    /**
     * Whether a popup may be on screen right now. Return false during a call,
     * a live room, a lock screen, or while signed out. Call
     * [ShipPopups.gateChanged] when the answer changes. Default: always.
     */
    val popupGate: () -> Boolean = { true },
    /**
     * Asked before a selected popup is presented, e.g. to load the live room
     * or community it shows. Return false to skip it this time: it is not
     * marked shown, the next best candidate is tried, and it is considered
     * again at the next [ShipPopups.armSoon]. Default: always true.
     */
    val popupPreflight: suspend (Sdui.Popup) -> Boolean = { true },
    /**
     * Called when a popup that was showing closes (the person closed it, a
     * button closed it, or [ShipPopups.close]). Release whatever
     * [popupPreflight] loaded for it. Default: nothing.
     */
    val onPopupClosed: (Sdui.Popup) -> Unit = {},
    val analytics: ShipAnalyticsSink? = null,
    /**
     * App-registered component types, drawn from their `fields`. The keys are
     * the only extra `type`s the parser accepts.
     */
    val components: Map<String, @Composable (Sdui.Node.Custom) -> Unit> = emptyMap(),
    /**
     * A folder in the app's assets holding `sdui/popups/<id>.json` and the files
     * they name (`lottie/...`, `images/...`) at their release paths, e.g.
     * `ship-embedded`. They show without any Ship release.
     */
    val embeddedPopupsAssetDir: String? = null,
)

/** The device's primary language code, e.g. `hi`. */
internal fun deviceLanguage(): String = ShipStrings.releaseLanguage(Locale.getDefault().language.ifEmpty { "en" })

/** What a rendered definition needs from its host, passed down the composition. */
internal class ShipRuntime(
    val options: ShipUiOptions,
    val content: ShipContent?,
    /** Where `lottie/...` and `images/...` paths are read from; by default the applied release. */
    private val filesOverride: ReleaseFiles? = null,
) {
    // Read each time: the content object stays, but the release it holds moves on.
    val files: ReleaseFiles? get() = filesOverride ?: content?.files()

    /**
     * A key through the release's OTA string in the app's language, then the
     * app's own string; the literal fallback when neither has it. Then
     * `personalize`.
     */
    fun text(v: Sdui.TextValue): String {
        var text = v.fallback.orEmpty()
        v.key?.let { key -> (ota(key) ?: options.appString(key))?.let { text = it } }
        return options.personalize(text)
    }

    private fun ota(key: String): String? =
        content?.takeIf { it.state?.revertToEmbedded == false }?.string(key, options.language())

    /** The app's colour, then the release's theme; null when neither has one (the caller supplies the default). */
    fun color(token: String, dark: Boolean): Color? {
        options.color(token, dark)?.let { return it }
        val name = themeNames[token] ?: return null
        return ShipTheme.color(name, dark, content)?.let { Color(it.toInt()) }
    }

    companion object {
        /** SDUI tokens that a release's `config/theme.json` may override. */
        val themeNames = mapOf("primary" to "primary300", "accent" to "accent500")
    }
}

internal val LocalShipRuntime = staticCompositionLocalOf {
    ShipRuntime(ShipUiOptions(deeplinkScheme = "", openDeeplink = {}), content = null)
}

/** Run before an SDUI button's action: a popup closes itself first, so a deep link or web page opens over the app rather than under the popup. */
internal val LocalSduiActionWillRun = staticCompositionLocalOf<(() -> Unit)?> { null }
