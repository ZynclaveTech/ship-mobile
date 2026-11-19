package com.zynclave.ship.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import com.zynclave.ship.ShipContent
import com.zynclave.ship.ShipStrings
import com.zynclave.ship.ShipTheme

/**
 * Receives the analytics events the views raise: `sdui_impression`,
 * `sdui_tap`, `sdui_dismiss`, `popup_shown` and `popup_action`. Forward them
 * to whatever the app uses, behind its own consent gate.
 */
public fun interface ShipAnalyticsSink {
    public fun log(name: String, params: Map<String, String>)
}

/**
 * The ids the backend listed for the signed-in user. Targeted cards and
 * popups show only when named here.
 */
public data class ShipEligible(val cards: Set<String>, val popups: Set<String>) {
    public companion object {
        /** Nothing targeted is shown. */
        public val None: ShipEligible = ShipEligible(emptySet(), emptySet())
    }
}

/**
 * Everything app-specific the views need. The library never reaches for the
 * app's navigation, strings, colours, user or analytics: it asks here.
 *
 * The functions are called on the main thread, except [eligibility] and
 * [popupGate], which may also be read from a background thread.
 */
public class ShipUiOptions(
    /**
     * The URL scheme a `deeplink` action must use, e.g. `myapp`: RFC 3986
     * scheme syntax, and not `http` or `https` (web links are `web` actions).
     * Anything else is a programming error and throws IllegalArgumentException.
     */
    public val deeplinkScheme: String,
    /**
     * A theme token (`primary`, `accent`, `surface`, `surfaceVariant`,
     * `onSurface`, `onSurfaceVariant`, `onPrimary`) to the app's colour.
     * Resolution order: the release's `config/theme.json` for that token (when
     * it is in [themeTokens]), then this, then a default (Material theme
     * colours for the surface and text tokens). Return null to decline.
     */
    public val color: (token: String, dark: Boolean) -> Color? = { _, _ -> null },
    /** Opens a `deeplink` action through the app's own routing. */
    public val openDeeplink: (Uri) -> Unit,
    /**
     * The language code used to look up a release's OTA strings
     * (`strings/<code>.json`). Default (null): the language of the resources
     * the popup or card is drawn with, so it follows the app's own language
     * choice (per-app language, or an in-app picker that wraps the context).
     * [currentLanguage] gives the resolved code for the host's own text.
     */
    public val language: (() -> String)? = null,
    /**
     * A text `key` to the app's own (localised) string, or null to use the
     * definition's `fallback`. Text resolves as: the release's OTA string for
     * [language], then this, then the literal fallback.
     *
     * The SDK also asks it for one reserved key of its own, `common_close`:
     * the accessibility label of the close button on popups and cards
     * (default "Close").
     */
    public val appString: (key: String) -> String? = { null },
    /** Last step over every text, e.g. to replace `{first_name}`. Default: drops the placeholder, as for a user with no name. */
    public val personalize: (String) -> String = { Sdui.personalize(it, firstName = null) },
    /** Targeted cards and popups show only when named here, so the default shows none of them. */
    public val eligibility: () -> ShipEligible = { ShipEligible.None },
    /**
     * Whether a popup may be on screen right now. Return false during a call,
     * a live room, a lock screen, or while signed out. Call
     * [ShipPopups.gateChanged] when the answer changes. Default: always.
     */
    public val popupGate: () -> Boolean = { true },
    /**
     * Asked before a selected popup is presented, e.g. to load the live room
     * or community it shows. Return false to skip it this time: it is not
     * marked shown, the next best candidate is tried, and it is considered
     * again at the next [ShipPopups.armSoon]. Default: always true.
     */
    public val popupPreflight: suspend (Sdui.Popup) -> Boolean = { true },
    /**
     * Called when a popup that was showing closes (the person closed it, a
     * button closed it, or [ShipPopups.close]), and for a popup [popupPreflight]
     * accepted that was then not shown after all. Release whatever
     * [popupPreflight] loaded for it. Default: nothing.
     *
     * Runs on the thread that closed it: the main thread for the person's taps,
     * the caller's thread for [ShipPopups.close].
     */
    public val onPopupClosed: (Sdui.Popup) -> Unit = {},
    public val analytics: ShipAnalyticsSink? = null,
    /**
     * App-registered component types, drawn from their `fields`. The keys are
     * the only extra `type`s the parser accepts; a built-in type name can never
     * be replaced.
     *
     * A component that acts on a tap (navigates, opens something) should first
     * call [LocalShipComponentWillAct], when it is not null: inside a popup that
     * closes the popup and logs `popup_action` with `component`, inside a card
     * it logs `sdui_tap` with `component`, exactly as a built-in button does.
     *
     * ```
     * val willAct = LocalShipComponentWillAct.current
     * Button(onClick = { willAct?.invoke(); openRoom(id) }) { ... }
     * ```
     */
    public val components: Map<String, @Composable (Sdui.Node.Custom) -> Unit> = emptyMap(),
    /**
     * A folder in the app's assets holding `sdui/popups/<id>.json` and the files
     * they name (`lottie/...`, `images/...`) at their release paths, e.g.
     * `ship-embedded`. They show without any Ship release.
     */
    public val embeddedPopupsAssetDir: String? = null,
    /**
     * The tokens a release's `config/theme.json` may override. Default:
     * [ShipTheme.DEFAULT_TOKENS], the SDUI colour tokens. A host app with its
     * own palette lists its own names here.
     */
    public val themeTokens: Set<String> = ShipTheme.DEFAULT_TOKENS,
) {
    init {
        require(isValidDeeplinkScheme(deeplinkScheme)) {
            "deeplinkScheme \"$deeplinkScheme\" must be an RFC 3986 scheme (a letter, then letters, digits, +, - or .), and not http or https"
        }
    }
}

/**
 * RFC 3986 `scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )`, and not a web
 * scheme: `web` actions carry those.
 */
internal fun isValidDeeplinkScheme(scheme: String): Boolean {
    if (scheme.isEmpty() || scheme.first() !in 'a'..'z' && scheme.first() !in 'A'..'Z') return false
    if (!scheme.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return false
    return !scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)
}


/**
 * The language Ship reads OTA strings in, as a release code (`in` → `id`):
 * [ShipUiOptions.language] when the app set one, else the language of the
 * resources this composition draws with.
 */
@Composable
public fun ShipUiOptions.currentLanguage(): String =
    language?.invoke() ?: configurationLanguage(LocalConfiguration.current.locales[0]?.language)

internal fun configurationLanguage(code: String?): String =
    ShipStrings.releaseLanguage(code?.ifEmpty { null } ?: "en")

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
    fun text(v: Sdui.TextValue, language: String = options.language?.invoke() ?: configurationLanguage(null)): String {
        var text = v.fallback.orEmpty()
        v.key?.let { key -> (ota(key, language) ?: options.appString(key))?.let { text = it } }
        return options.personalize(text)
    }

    private fun ota(key: String, language: String): String? =
        content?.takeIf { it.state?.revertToEmbedded == false }?.string(key, language)

    /**
     * The release's theme override for [token] (if allowed), then the app's
     * colour; null when neither has one (the caller supplies the default).
     */
    fun color(token: String, dark: Boolean): Color? {
        ShipTheme.color(token, dark, content, options.themeTokens)?.let { return Color(it.toInt()) }
        return options.color(token, dark)
    }
}

internal val LocalShipRuntime = staticCompositionLocalOf {
    ShipRuntime(ShipUiOptions(deeplinkScheme = "none", openDeeplink = {}), content = null)
}

/**
 * What a component must call just before it acts on a tap, or null outside a
 * popup or card. Built-in buttons call it; an app-registered component (see
 * [ShipUiOptions.components]) that navigates or opens something must call it
 * too. Inside a popup it closes the popup first, so what the tap opens is not
 * under it, and logs `popup_action` with `component`; inside a card it logs
 * `sdui_tap` with `component`. Main thread.
 */
public val LocalShipComponentWillAct: ProvidableCompositionLocal<(() -> Unit)?> =
    staticCompositionLocalOf { null }
