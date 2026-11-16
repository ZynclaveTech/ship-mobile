package com.zynclave.ship

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * Theme color overrides from `config/theme.json` of the applied release:
 * `{"primary300": "#FF7A00"}` or `{"primary300": {"light": "#..", "dark": "#.."}}`.
 *
 * Only allowlisted tokens are honoured, and a bad value (wrong shape, bad
 * hex) means "no override", so a bad file can never make an app unreadable.
 * Colors are returned as ARGB longs (`0xFFRRGGBB`), independent of any UI toolkit.
 */
object ShipTheme {
    /**
     * Theme tokens a release may override. Backgrounds, surfaces and text
     * colors are deliberately absent: a bad file must never be able to make
     * the app unreadable.
     */
    val allowlist: Set<String> = setOf("primary300", "primary200", "accent500")

    data class ThemeColor(val light: Long, val dark: Long)

    private class Cached(val content: ShipContent, val releaseId: String, val theme: Map<String, ThemeColor>)

    @Volatile private var cache: Cached? = null

    fun parse(text: String): Map<String, ThemeColor> {
        val obj = runCatching { ManifestVerifier.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        val out = HashMap<String, ThemeColor>()
        for ((token, value) in obj) {
            if (token !in allowlist) continue
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
    fun hexColor(hex: String): Long? {
        val h = hex.removePrefix("#")
        if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return 0xFF000000L or h.toLong(16)
    }

    fun color(token: String, dark: Boolean, theme: Map<String, ThemeColor>): Long? =
        theme[token]?.let { if (dark) it.dark else it.light }

    /** The override for an allowlisted [token] in the applied release, or null. [dark] is the host's current mode. */
    fun color(token: String, dark: Boolean, content: ShipContent?): Long? = color(token, dark, theme(content))

    /** Parsed once per release, so the many color reads per frame cost a field read and a map lookup. */
    private fun theme(content: ShipContent?): Map<String, ThemeColor> {
        val state = content?.state
        if (content == null || state == null || state.revertToEmbedded) return emptyMap()
        cache?.takeIf { it.content === content && it.releaseId == state.releaseId }?.let { return it.theme }
        val parsed = content.file("config/theme.json")?.let { f -> runCatching { parse(f.readText()) }.getOrNull() }.orEmpty()
        cache = Cached(content, state.releaseId, parsed)
        return parsed
    }
}
