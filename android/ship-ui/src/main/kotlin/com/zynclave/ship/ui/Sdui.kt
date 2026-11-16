package com.zynclave.ship.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/*
 * Server-driven cards and popups. A release carries `sdui/cards/<id>.json` and
 * `sdui/popups/<id>.json`: a small tree of components the app already has.
 * Parsing is STRICT - anything unknown, oversized or malformed drops the whole
 * definition, never part of it. No code is downloaded.
 *
 * The eight built-in components are vstack, hstack, text, image, lottie,
 * button, badge and spacer. Any other `type` is accepted only if the app
 * registered it in `customTypes`; the app then renders it from its raw fields.
 *
 * A definition `ship validate` passes must parse here too. Where the iOS
 * parser is looser than the validator (true read as 1, a 1 read as true),
 * this one follows the validator, so the difference only ever drops a
 * definition the publisher was already told was wrong.
 */
object Sdui {
    const val SCHEMA_VERSION = 1
    const val RENDERER_VERSION = 1
    const val MAX_NODES = 40
    const val MAX_DEPTH = 6
    const val MAX_TEXT_LENGTH = 500

    /** At most this many server cards per tab per feed. */
    const val MAX_CARDS_PER_FEED = 2

    const val CARDS_DIR = "sdui/cards/"
    const val POPUPS_DIR = "sdui/popups/"

    val textStyles = setOf("title", "headline", "body", "caption")
    val buttonStyles = setOf("primary", "secondary", "text")
    val colorTokens = setOf("primary", "accent", "surface", "surfaceVariant", "onSurface", "onSurfaceVariant", "onPrimary")
    val tabs = setOf("discover", "following")

    data class Card(
        val id: String,
        /** Shown only to users the backend lists for this id. */
        val targeted: Boolean,
        val start: Instant?,
        val end: Instant?,
        val tabs: Set<String>,
        /** Posts before the card, 0-50. */
        val position: Int,
        val priority: Int,
        val dismissible: Boolean,
        val tap: Action?,
        val body: Node,
    )

    /** A string-table key with fallback text; at least one is set. */
    data class TextValue(val key: String?, val fallback: String?)

    sealed interface Action {
        /** `<scheme>://<route>`; opened through the app's own deep-link handling. */
        data class Deeplink(val url: String) : Action

        /** `https://<host>`; opened in the in-app browser. */
        data class Web(val url: String) : Action

        data object Dismiss : Action
    }

    sealed interface Node {
        data class Stack(
            val axis: Axis,
            val spacing: Float,
            val padding: Float,
            val background: String?,
            val cornerRadius: Float,
            val children: List<Node>,
        ) : Node

        data class Text(val value: TextValue, val style: String, val maxLines: Int?, val color: String?) : Node

        data class Image(val asset: String, val aspect: Float?, val cornerRadius: Float) : Node

        /** A Lottie animation from the same release (`lottie/<name>.json`). */
        data class Lottie(val asset: String, val height: Float, val loop: Boolean) : Node

        data class Button(val label: TextValue, val style: String, val action: Action) : Node

        data class Badge(val text: TextValue, val color: String?) : Node

        data class Spacer(val height: Float) : Node

        /**
         * An app-registered component: its `type` was in `customTypes`. The app
         * renders it from [fields] (every key except `type`); the library never
         * interprets them.
         */
        data class Custom(val type: String, val fields: JsonObject) : Node

        enum class Axis { Vertical, Horizontal }
    }

    class Invalid(val reason: String) : Exception(reason)

    private val definitionId = Regex("^[a-z0-9-]{1,64}$")

    // What iOS's ISO8601DateFormatter accepts by default (and `ship validate`
    // checks): seconds, no fraction, Z or a +hh:mm offset.
    private val isoDate = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-]\d{2}:\d{2})$""")

    private val json = Json

    // ---- Parsing (strict) ----

    /**
     * Parses one card file. [assetExists] says whether an `images/...` or
     * `lottie/...` path is in the same release; one that isn't makes the
     * card invalid.
     */
    fun parseCard(data: String, scheme: String, customTypes: Set<String>, assetExists: (String) -> Boolean): Card {
        val (obj, id, targeted, start, end) = header(data)
        val placement = obj["placement"] as? JsonObject ?: throw Invalid("bad placement")
        val tabList = (placement["tabs"] as? JsonArray)?.map { string(it) }
        if (tabList.isNullOrEmpty() || !tabList.all { it in tabs }) throw Invalid("bad placement")
        val position = integer(placement["position"])?.takeIf { it in 0..50 } ?: throw Invalid("bad placement")
        val priority = integer(placement["priority"]) ?: 0
        val dismissible = bool(obj["dismissible"]) ?: true
        val tap = obj["tap"]?.let { action(it, scheme) }

        val body = obj["body"] ?: throw Invalid("no body")
        val counter = intArrayOf(0)
        return Card(
            id = id,
            targeted = targeted,
            start = start,
            end = end,
            tabs = tabList.filterNotNull().toSet(),
            position = position.toInt(),
            priority = priority.toInt(),
            dismissible = dismissible,
            tap = tap,
            body = node(body, depth = 1, count = counter, scheme = scheme, customTypes = customTypes, assetExists = assetExists),
        )
    }

    /** What cards and popups share, checked the same way for both. */
    private data class Header(val obj: JsonObject, val id: String, val targeted: Boolean, val start: Instant?, val end: Instant?)

    private fun header(data: String): Header {
        val obj = try {
            json.parseToJsonElement(data) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw Invalid("not a JSON object")

        val schema = integer(obj["schema"])
        if (schema == null || schema < 1 || schema > SCHEMA_VERSION) throw Invalid("unsupported schema")
        obj["minRenderer"]?.let {
            val m = integer(it)
            if (m == null || m > RENDERER_VERSION) throw Invalid("needs a newer renderer")
        }
        val id = string(obj["id"])?.takeIf(definitionId::matches) ?: throw Invalid("bad id")
        return Header(obj, id, audienceIsTargeted(obj["audience"]), optionalDate(obj["start"], "start"), optionalDate(obj["end"], "end"))
    }

    /**
     * A popup (`sdui/popups/<id>.json`): the same strict component tree
     * as a card, shown once as a modal over the app.
     */
    data class Popup(
        val id: String,
        /** Shown only to users the backend lists for this id. */
        val targeted: Boolean,
        val start: Instant?,
        val end: Instant?,
        val priority: Int,
        val closeButton: Boolean,
        val body: Node,
    )

    /** Same strictness as [parseCard]: anything unknown or malformed drops the whole popup. */
    fun parsePopup(data: String, scheme: String, customTypes: Set<String>, assetExists: (String) -> Boolean): Popup {
        val (obj, id, targeted, start, end) = header(data)
        val body = obj["body"] ?: throw Invalid("no body")
        return Popup(
            id = id,
            targeted = targeted,
            start = start,
            end = end,
            priority = (integer(obj["priority"]) ?: 0).toInt(),
            closeButton = bool(obj["closeButton"]) ?: true,
            body = node(body, depth = 1, count = intArrayOf(0), scheme = scheme, customTypes = customTypes, assetExists = assetExists),
        )
    }

    /**
     * In schedule, never shown, targeted ones only if [eligible] names them,
     * best first (priority, then id, so every device picks the same one).
     */
    fun eligiblePopups(all: List<Popup>, now: Instant, shown: Set<String>, eligible: Set<String>): List<Popup> =
        all.filter { p ->
            p.id !in shown && (!p.targeted || p.id in eligible) &&
                (p.start?.let { !now.isBefore(it) } ?: true) && (p.end?.let { !now.isAfter(it) } ?: true)
        }.sortedWith(compareByDescending<Popup> { it.priority }.thenBy { it.id })

    /**
     * "all" or "targeted"; anything else - a typo, a value from a newer spec -
     * drops the definition rather than guessing who should see it.
     */
    fun audienceIsTargeted(value: JsonElement?): Boolean = when (string(value)) {
        "all" -> false
        "targeted" -> true
        else -> throw Invalid("bad audience")
    }

    private fun optionalDate(value: JsonElement?, field: String): Instant? {
        value ?: return null
        val s = string(value)?.takeIf(isoDate::matches) ?: throw Invalid("bad $field")
        return try {
            OffsetDateTime.parse(s).toInstant()
        } catch (_: DateTimeParseException) {
            throw Invalid("bad $field")
        }
    }

    fun action(value: JsonElement, scheme: String): Action {
        val obj = value as? JsonObject ?: throw Invalid("bad action")
        return when (string(obj["type"])) {
            "dismiss" -> Action.Dismiss
            "deeplink" -> string(obj["url"])?.takeIf { linkWithHost(it, scheme) }?.let(Action::Deeplink)
                ?: throw Invalid("deeplink must be $scheme://")
            "web" -> string(obj["url"])?.takeIf { linkWithHost(it, "https") }?.let(Action::Web)
                ?: throw Invalid("web link must be https://")
            else -> throw Invalid("unknown action")
        }
    }

    /**
     * `<scheme>://` exactly as written (case included, as iOS compares it),
     * no whitespace, and a non-empty host: the authority without user info
     * and port. iOS's `URL.host` test. `ship validate` checks Go's `u.Host`,
     * which keeps the port, so it passes `<scheme>://user@:80/x`; iOS and Android
     * both drop that card.
     */
    internal fun linkWithHost(s: String, scheme: String): Boolean {
        val prefix = "$scheme://"
        if (!s.startsWith(prefix) || s.any { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) return false
        val rest = s.substring(prefix.length)
        val authority = rest.substring(0, rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it })
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.isNotEmpty() && host != "[]"
    }

    /** `{"key": ..., "fallback": ...}`, either one; a fallback over the length cap drops the card. */
    private fun text(obj: JsonObject?): TextValue {
        obj ?: throw Invalid("bad text")
        val key = string(obj["key"])
        val fallback = string(obj["fallback"])
        if (key == null && fallback == null) throw Invalid("text needs key or fallback")
        // Code points, as `ship validate` counts (runes). iOS counts grapheme
        // clusters, which is never more, so whatever passes validate passes here.
        if (fallback != null && fallback.codePointCount(0, fallback.length) > MAX_TEXT_LENGTH) {
            throw Invalid("text too long")
        }
        return TextValue(key, fallback)
    }

    private fun color(value: JsonElement?): String? {
        value ?: return null
        val token = string((value as? JsonObject)?.get("token"))
        if (token == null || token !in colorTokens) throw Invalid("colors must be theme tokens")
        return token
    }

    /** Absent means [default]; present must be a number in 0..[max] (null and booleans refused). */
    private fun number(value: JsonElement?, default: Float, max: Float): Float {
        value ?: return default
        // doubleOrNull is already null for true and null.
        val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        if (n == null || n < 0 || n > max) throw Invalid("bad number")
        return n.toFloat()
    }

    internal fun node(
        value: JsonElement,
        depth: Int,
        count: IntArray,
        scheme: String,
        customTypes: Set<String>,
        assetExists: (String) -> Boolean,
    ): Node {
        count[0] += 1
        if (count[0] > MAX_NODES) throw Invalid("too many nodes")
        if (depth > MAX_DEPTH) throw Invalid("nested too deep")
        val obj = value as? JsonObject ?: throw Invalid("bad node")
        return when (val type = string(obj["type"]) ?: throw Invalid("bad node")) {
            "vstack", "hstack" -> {
                val raw = obj["children"] as? JsonArray
                if (raw.isNullOrEmpty()) throw Invalid("stack needs children")
                val children = raw.map { node(it, depth + 1, count, scheme, customTypes, assetExists) }
                Node.Stack(
                    axis = if (type == "vstack") Node.Axis.Vertical else Node.Axis.Horizontal,
                    spacing = number(obj["spacing"], default = 8f, max = 64f),
                    padding = number(obj["padding"], default = 0f, max = 64f),
                    background = color(obj["background"]),
                    cornerRadius = number(obj["cornerRadius"], default = 0f, max = 48f),
                    children = children,
                )
            }
            "text" -> {
                // A present but non-text style is refused, as `ship validate` refuses it (iOS
                // would read it as the default; see the file comment).
                val style = if (obj.containsKey("style")) string(obj["style"]) else "body"
                if (style == null || style !in textStyles) throw Invalid("bad text style")
                val maxLines = integer(obj["maxLines"])
                if (maxLines != null && maxLines !in 1..20) throw Invalid("bad maxLines")
                Node.Text(text(obj), style, maxLines?.toInt(), color(obj["color"]))
            }
            "image" -> {
                val src = string(obj["src"])
                if (src == null || !src.startsWith("images/") || ".." in src || !assetExists(src)) {
                    throw Invalid("image must be an asset in this release")
                }
                val aspect = obj["aspect"]?.let { number(it, default = 1f, max = 10f) }
                if (aspect != null && aspect < 0.1f) throw Invalid("bad aspect")
                Node.Image(src, aspect, number(obj["cornerRadius"], default = 0f, max = 48f))
            }
            "lottie" -> {
                val src = string(obj["src"])
                if (src == null || !src.startsWith("lottie/") || !src.endsWith(".json") || ".." in src || !assetExists(src)) {
                    throw Invalid("lottie must be an asset in this release")
                }
                Node.Lottie(src, number(obj["height"], default = 160f, max = 480f), bool(obj["loop"]) ?: true)
            }
            "button" -> {
                val style = if (obj.containsKey("style")) string(obj["style"]) else "primary"
                val act = obj["action"]
                if (style == null || style !in buttonStyles || act == null) throw Invalid("bad button")
                Node.Button(text(obj["label"] as? JsonObject), style, action(act, scheme))
            }
            "badge" -> Node.Badge(text(obj["text"] as? JsonObject), color(obj["color"]))
            "spacer" -> Node.Spacer(number(obj["height"], default = 8f, max = 64f))
            // Built-in names are matched above, so a registered name can never
            // shadow one; anything else must be registered by the app.
            else -> {
                if (type !in customTypes) throw Invalid("unknown component $type")
                Node.Custom(type, JsonObject(obj.filterKeys { it != "type" }))
            }
        }
    }

    // ---- JSON value helpers: what Swift's `as?` reads, minus its Bool/Int bridging ----

    private fun string(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(v: JsonElement?): Boolean? =
        (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    /** A whole JSON number, 3 or 3.0; not "3", not 2.5, not true. */
    internal fun integer(v: JsonElement?): Long? {
        val p = (v as? JsonPrimitive)?.takeIf { !it.isString } ?: return null // true and null parse as neither
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong() else null
    }

    // ---- Selection ----

    /**
     * Which cards a feed tab shows now: one per id, scheduled now, not
     * dismissed, targeted ones only if [eligible] names them, highest priority
     * first (then id), at most [MAX_CARDS_PER_FEED].
     */
    fun cards(all: List<Card>, tab: String, now: Instant, dismissed: Set<String>, eligible: Set<String>): List<Card> =
        all.distinctBy { it.id }
            .filter { c ->
                tab in c.tabs && c.id !in dismissed && (!c.targeted || c.id in eligible) &&
                    (c.start?.let { !now.isBefore(it) } ?: true) && (c.end?.let { !now.isAfter(it) } ?: true)
            }
            .sortedWith(compareByDescending<Card> { it.priority }.thenBy { it.id })
            .take(MAX_CARDS_PER_FEED)

    /**
     * The next moment [cards] would change for [tab] on the clock alone: the
     * soonest start still ahead, or just after the soonest end still ahead
     * (an end is inclusive). Null when no card on the tab starts or ends later.
     */
    fun nextChange(all: List<Card>, tab: String, now: Instant): Instant? =
        all.asSequence()
            .filter { tab in it.tabs }
            .flatMap { sequenceOf(it.start, it.end?.plusMillis(1)) }
            .filterNotNull()
            .filter { it.isAfter(now) }
            .minOrNull()

    /** Where a feed shows its cards: before the post at an index, or after the last post. */
    data class Placement(val beforePost: Map<Int, List<Card>> = emptyMap(), val atEnd: List<Card> = emptyList())

    /**
     * Places [cards] (in [cards] order) "after `position` posts", counted in
     * posts, never rows, and never replacing or reordering a post. A card past
     * the loaded posts waits while [hasMore] - parking it at the end and moving
     * it when the next page arrives would jump mid-scroll; only a feed with
     * nothing more to load shows it last. 
     */
    fun placement(cards: List<Card>, postCount: Int, hasMore: Boolean): Placement {
        val before = linkedMapOf<Int, MutableList<Card>>()
        val end = mutableListOf<Card>()
        for (card in cards) {
            if (card.position > postCount && hasMore) continue
            val index = minOf(card.position, postCount)
            if (index < postCount) before.getOrPut(index) { mutableListOf() } += card else end += card
        }
        return Placement(before, end)
    }

    /**
     * `{first_name}` is the only placeholder text may carry; it becomes the
     * signed-in user's first name, or is dropped (with its leading ", ") if
     * there is none - "Happy Diwali, {first_name}!" -> "Happy Diwali!". Edits only around the placeholder, so the rest of
     * the text (a French space before "!") stays exactly as written.
     */
    fun personalize(text: String, firstName: String?): String {
        if (PLACEHOLDER !in text) return text
        val name = firstName?.trim(::isBlankChar)
        if (!name.isNullOrEmpty()) return text.replace(PLACEHOLDER, name)
        return text
            .replace(Regex("""^\{first_name\}[,!:;.?]?[ \t]*"""), "") // "{first_name}, welcome" -> "welcome"
            .replace(Regex(""",[ \t]*\{first_name\}"""), "") // "Happy Diwali, {first_name}!" -> "Happy Diwali!"
            .replace(Regex("""[ \t]*\{first_name\}"""), "") // "Hi {first_name} there" -> "Hi there"
            .trim(::isBlankChar)
    }

    private const val PLACEHOLDER = "{first_name}"

    // Swift's CharacterSet.whitespaces: spaces and tabs, not line breaks.
    private fun isBlankChar(c: Char) = c.isWhitespace() && c != '\n' && c != '\r'
}
