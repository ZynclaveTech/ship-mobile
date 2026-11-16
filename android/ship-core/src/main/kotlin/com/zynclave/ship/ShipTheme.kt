package com.zynclave.ship

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Theme color overrides from `config/theme.json` of the applied release:
 * `{"primary": "#FF7A00"}` or `{"primary": {"light": "#..", "dark": "#.."}}`.
 *
 * Only allowed tokens are honoured (the lookups take the allowed set, default
 * [DEFAULT_TOKENS]), and a bad value (wrong shape, bad
 * hex) means "no override", so a bad file can never make an app unreadable.
 * Colors are returned as ARGB longs (`0xFFRRGGBB`), independent of any UI toolkit.
 */
public object ShipTheme {
    /**
     * The SDUI colour tokens a release may override by default, the ones the
     * Ship validator accepts. A host app with its own palette passes its own
     * names as `allowed` to the lookups.
     */
    public val DEFAULT_TOKENS: Set<String> = setOf(
        "primary", "accent", "surface", "surfaceVariant", "onSurface", "onSurfaceVariant", "onPrimary",
    )

    internal data class ThemeColor(val light: Long, val dark: Long)

    private class Cached(val content: ShipContent, val releaseId: String, val theme: Map<String, ThemeColor>)

    @Volatile private var cache: Cached? = null

    /** Parses [text], keeping only the tokens in [allowed]. */
    internal fun parse(text: String, allowed: Set<String> = DEFAULT_TOKENS): Map<String, ThemeColor> =
        parseAll(text).filterKeys { it in allowed }

    /** Every token with a valid value, whatever its name. */
    private fun parseAll(text: String): Map<String, ThemeColor> {
        val obj = runCatching { ManifestVerifier.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        val out = HashMap<String, ThemeColor>()
        for ((token, value) in obj) {
            when (value) {
                is JsonPrimitive -> hexColor(value)?.let { out[token] = ThemeColor(it, it) }
                is JsonObject -> {
                    val light = (value["light"] as? JsonPrimitive)?.let(::hexColor)
                    val dark = (value["dark"] as? JsonPrimitive)?.let(::hexColor)
                    if (light != null && dark != null) out[token] = ThemeColor(light, dark)
                }
                else -> Unit
            }
        }
        return out
    }

    private fun hexColor(p: JsonPrimitive): Long? = if (p.isString) hexColor(p.contentOrNull ?: return null) else null

    /** Strict `#RRGGBB` (or `RRGGBB`) as opaque ARGB; anything else is rejected rather than guessed. */
    internal fun hexColor(hex: String): Long? {
        val h = hex.removePrefix("#")
        if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return 0xFF000000L or h.toLong(16)
    }

    internal fun color(token: String, dark: Boolean, theme: Map<String, ThemeColor>): Long? =
        theme[token]?.let { if (dark) it.dark else it.light }

    /**
     * The override for [token] in the applied release, or null. Only tokens in
     * [allowed] are honoured. [dark] is the host's current mode.
     */
    public fun color(token: String, dark: Boolean, content: ShipContent?, allowed: Set<String> = DEFAULT_TOKENS): Long? =
        if (token in allowed) color(token, dark, theme(content)) else null

    /**
     * Parsed once per release with no token filter, so the cache is right for
     * any [allowed] set: the caller filters. Many color reads per frame cost
     * a field read and a map lookup.
     */
    private fun theme(content: ShipContent?): Map<String, ThemeColor> {
        val state = content?.state
        if (content == null || state == null || state.revertToEmbedded) return emptyMap()
        cache?.takeIf { it.content === content && it.releaseId == state.releaseId }?.let { return it.theme }
        val parsed = content.file("config/theme.json")?.let { f -> runCatching { parseAll(f.readText()) }.getOrNull() }.orEmpty()
        cache = Cached(content, state.releaseId, parsed)
        return parsed
    }
}
