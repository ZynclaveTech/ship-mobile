package com.zynclave.ship

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Remote-config flag values from `config/flags.json` of the applied release:
 * `{"auto_play_enabled": false, "max_items": 5}`.
 *
 * Every read returns null when there is no override: no release (or a
 * revert), a missing or undecodable file, a missing key or a value of the
 * wrong JSON type. The caller then uses its built-in default.
 */
object ShipFlags {
    private class Cached(val content: ShipContent, val releaseId: String, val flags: Map<String, JsonElement>)

    @Volatile private var cache: Cached? = null

    /** A top-level JSON object, or nothing. Values stay as decoded and are type-checked on read. */
    fun parse(text: String): Map<String, JsonElement> =
        runCatching { ManifestVerifier.json.parseToJsonElement(text) as? JsonObject }.getOrNull().orEmpty()

    /** Parsed once per release, so repeated reads cost a field read and a map lookup. */
    private fun flags(content: ShipContent?): Map<String, JsonElement> {
        val state = content?.state
        if (content == null || state == null || state.revertToEmbedded) return emptyMap()
        cache?.takeIf { it.content === content && it.releaseId == state.releaseId }?.let { return it.flags }
        val parsed = content.file("config/flags.json")?.let { f -> runCatching { parse(f.readText()) }.getOrNull() }.orEmpty()
        cache = Cached(content, state.releaseId, parsed)
        return parsed
    }

    // Typed reads on parsed flags.

    fun boolean(key: String, flags: Map<String, JsonElement>): Boolean? {
        val p = flags[key] as? JsonPrimitive ?: return null
        return if (p.isString) null else when (p.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    fun int(key: String, flags: Map<String, JsonElement>): Int? {
        val d = number(flags[key]) ?: return null
        return if (d == Math.rint(d) && Math.abs(d) < Int.MAX_VALUE.toDouble()) d.toInt() else null
    }

    fun double(key: String, flags: Map<String, JsonElement>): Double? = number(flags[key])

    fun string(key: String, flags: Map<String, JsonElement>): String? {
        val p = flags[key] as? JsonPrimitive ?: return null
        return if (p.isString) p.content else null
    }

    /** A JSON number, not a string or boolean posing as one. */
    private fun number(e: JsonElement?): Double? {
        val p = e as? JsonPrimitive ?: return null
        return if (p.isString) null else p.content.toDoubleOrNull()
    }

    // Reads on applied content.

    fun boolean(key: String, content: ShipContent?): Boolean? = boolean(key, flags(content))
    fun int(key: String, content: ShipContent?): Int? = int(key, flags(content))
    fun double(key: String, content: ShipContent?): Double? = double(key, flags(content))
    fun string(key: String, content: ShipContent?): String? = string(key, flags(content))
}
