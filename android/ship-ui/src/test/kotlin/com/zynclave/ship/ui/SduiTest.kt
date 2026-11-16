package com.zynclave.ship.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Server-driven cards and popups are all-or-nothing: anything unknown,
 * oversized or malformed drops the whole definition.
 */
class SduiTest {
    private val SCHEME = "shipsample"


    private val assets = setOf("images/sdui/banner.png", "lottie/confetti.json")

    private val defaultBody = """
        {"type": "vstack", "children": [
          {"type": "image", "src": "images/sdui/banner.png", "aspect": 2},
          {"type": "text", "key": "promo_title", "fallback": "Diwali", "style": "title"},
          {"type": "button", "label": {"fallback": "Open"}, "action": {"type": "deeplink", "url": "shipsample://rewards"}}
        ]}
    """

    /** The iOS fixture card, with top-level fields replaced by [overrides] (raw JSON values). */
    private fun card(overrides: Map<String, String> = emptyMap(), body: String? = null): String {
        val fields = linkedMapOf(
            "schema" to "1",
            "id" to "\"diwali-coins\"",
            "audience" to "\"all\"",
            "placement" to """{"tabs": ["discover"], "position": 3}""",
            "body" to (body ?: defaultBody),
        )
        overrides.forEach { (k, v) -> fields[k] = v }
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\": ${it.value}" }
    }

    private fun parse(data: String, custom: Set<String> = emptySet()) = Sdui.parseCard(data, SCHEME, custom, assets::contains)

    private fun rejects(data: String, why: String = data) {
        try {
            parse(data)
            fail("should drop the card: $why")
        } catch (_: Sdui.Invalid) {
        }
    }

    private fun bodyOf(node: String) = card(body = node)

    @Test
    fun `valid card parses`() {
        val c = parse(card())
        assertEquals("diwali-coins", c.id)
        assertEquals(3, c.position)
        assertEquals(setOf("discover"), c.tabs)
        assertTrue(c.dismissible)
        assertNull(c.tap)
        val stack = c.body as Sdui.Node.Stack
        assertEquals(Sdui.Node.Axis.Vertical, stack.axis)
        assertEquals(8f, stack.spacing)
        assertEquals(3, stack.children.size)
        assertEquals(Sdui.Node.Image("images/sdui/banner.png", 2f, 0f), stack.children[0])
        assertEquals(Sdui.Node.Text(Sdui.TextValue("promo_title", "Diwali"), "title", null, null), stack.children[1])
        assertEquals(
            Sdui.Node.Button(Sdui.TextValue(null, "Open"), "primary", Sdui.Action.Deeplink("shipsample://rewards")),
            stack.children[2],
        )
    }

    @Test
    fun `every component parses`() {
        val c = parse(
            bodyOf(
                """
                {"type": "hstack", "spacing": 4, "padding": 12, "cornerRadius": 16, "background": {"token": "surfaceVariant"},
                 "children": [
                  {"type": "lottie", "src": "lottie/confetti.json", "height": 120, "loop": false},
                  {"type": "badge", "text": {"key": "new"}, "color": {"token": "accent"}},
                  {"type": "spacer"},
                  {"type": "room", "id": "room_42", "showJoin": false}
                ]}
                """,
            ),
            custom = setOf("room"),
        )
        val stack = c.body as Sdui.Node.Stack
        assertEquals(Sdui.Node.Axis.Horizontal, stack.axis)
        assertEquals("surfaceVariant", stack.background)
        assertEquals(
            listOf(
                Sdui.Node.Lottie("lottie/confetti.json", 120f, false),
                Sdui.Node.Badge(Sdui.TextValue("new", null), "accent"),
                Sdui.Node.Spacer(8f),
                Sdui.Node.Custom("room", JsonObject(mapOf("id" to JsonPrimitive("room_42"), "showJoin" to JsonPrimitive(false)))),
            ),
            stack.children,
        )
    }

    @Test
    fun `unknown component drops the whole card`() {
        rejects(bodyOf("""{"type": "vstack", "children": [{"type": "text", "fallback": "ok"}, {"type": "video", "src": "x"}]}"""))
    }

    @Test
    fun `audience is all or targeted`() {
        assertFalse(parse(card()).targeted)
        assertTrue(parse(card(mapOf("audience" to "\"targeted\""))).targeted)
        rejects(card(mapOf("audience" to "\"everyone\"")))
        rejects(card(mapOf("audience" to "null")))
        rejects(card().replace("\"audience\": \"all\",", ""), "no audience")
    }

    @Test
    fun `newer schema or renderer is skipped`() {
        rejects(card(mapOf("schema" to "2")))
        rejects(card(mapOf("schema" to "0")))
        rejects(card(mapOf("schema" to "\"1\"")))
        rejects(card(mapOf("minRenderer" to "99")))
        parse(card(mapOf("minRenderer" to "1")))
        parse(card(mapOf("schema" to "1.0")))
    }

    @Test
    fun `actions are restricted`() {
        fun withAction(action: String) = card(mapOf("tap" to action))
        assertEquals(Sdui.Action.Web("https://example.com/diwali"), parse(withAction("""{"type": "web", "url": "https://example.com/diwali"}""")).tap)
        assertEquals(Sdui.Action.Dismiss, parse(withAction("""{"type": "dismiss"}""")).tap)
        for (bad in listOf(
            """{"type": "web", "url": "http://example.com"}""",
            """{"type": "deeplink", "url": "https://evil.example"}""",
            """{"type": "web", "url": "javascript:alert(1)"}""",
            """{"type": "share"}""",
            """{"type": "deeplink", "url": "SHIPSAMPLE://rewards"}""",
            """{"type": "deeplink", "url": "shipsample:///rewards"}""",
            """{"type": "deeplink", "url": "shipsample://user@:80/x"}""",
            """{"type": "web", "url": "https://example.com/a b"}""",
            """"shipsample://rewards"""",
        )) {
            rejects(withAction(bad), bad)
        }
        assertTrue(Sdui.linkWithHost("shipsample://rewards?tab=coins", "shipsample"))
        assertTrue(Sdui.linkWithHost("https://[::1]:8443/x", "https"))
    }

    @Test
    fun `images and lottie only from the release`() {
        rejects(bodyOf("""{"type": "image", "src": "https://cdn.example/x.png"}"""))
        rejects(bodyOf("""{"type": "image", "src": "images/missing.png"}"""))
        rejects(bodyOf("""{"type": "image", "src": "images/../config/flags.json"}"""))
        rejects(bodyOf("""{"type": "image", "src": "images/sdui/banner.png", "aspect": 0.05}"""))
        rejects(bodyOf("""{"type": "lottie", "src": "lottie/missing.json"}"""))
        rejects(bodyOf("""{"type": "lottie", "src": "images/sdui/banner.png"}"""))
    }

    @Test
    fun `colors must be tokens`() {
        parse(bodyOf("""{"type": "text", "fallback": "x", "color": {"token": "primary"}}"""))
        rejects(bodyOf("""{"type": "text", "fallback": "x", "color": "#FF0000"}"""))
        rejects(bodyOf("""{"type": "text", "fallback": "x", "color": {"token": "neonPink"}}"""))
    }

    @Test
    fun `size limits`() {
        val many = List(45) { """{"type": "spacer"}""" }.joinToString(",")
        rejects(bodyOf("""{"type": "vstack", "children": [$many]}"""), "45 nodes")
        val forty = List(39) { """{"type": "spacer"}""" }.joinToString(",")
        parse(bodyOf("""{"type": "vstack", "children": [$forty]}"""))

        var deep = """{"type": "text", "fallback": "x"}"""
        repeat(5) { deep = """{"type": "vstack", "children": [$deep]}""" }
        parse(bodyOf(deep)) // depth 6
        rejects(bodyOf("""{"type": "vstack", "children": [$deep]}"""), "depth 7")

        rejects(bodyOf("""{"type": "text", "fallback": "${"a".repeat(501)}"}"""))
        // Counted in code points like `ship validate`: 500 emoji (1000 UTF-16 units) still fit.
        parse(bodyOf("""{"type": "text", "fallback": "${"🪔".repeat(500)}"}"""))
    }

    @Test
    fun `numbers are numbers in range, never null or booleans`() {
        parse(bodyOf("""{"type": "spacer", "height": 64}"""))
        for (bad in listOf("65", "-1", "null", "true", "\"8\"")) {
            rejects(bodyOf("""{"type": "spacer", "height": $bad}"""), "height $bad")
        }
        rejects(bodyOf("""{"type": "text", "fallback": "x", "maxLines": 0}"""))
        rejects(bodyOf("""{"type": "text", "fallback": "x", "maxLines": 21}"""))
    }

    @Test
    fun `values the validator only warns about are ignored, not fatal`() {
        val c = parse(
            card(
                mapOf("dismissible" to "\"no\"", "placement" to """{"tabs": ["discover"], "position": 3, "priority": "high"}""", "extra" to "1"),
                body = """{"type": "text", "fallback": "x", "maxLines": "3", "key": 5, "note": "ignored"}""",
            ),
        )
        assertTrue("non-bool dismissible keeps the default", c.dismissible)
        assertEquals(0, c.priority)
        assertEquals(Sdui.Node.Text(Sdui.TextValue(null, "x"), "body", null, null), c.body)
    }

    @Test
    fun `bad text and styles drop the card`() {
        rejects(bodyOf("""{"type": "text", "key": 5}"""), "text with no usable key or fallback")
        rejects(bodyOf("""{"type": "text", "fallback": "x", "style": "huge"}"""))
        rejects(bodyOf("""{"type": "text", "fallback": "x", "style": 5}"""))
        rejects(bodyOf("""{"type": "button", "label": {"fallback": "Go"}}"""), "button without action")
        rejects(bodyOf("""{"type": "button", "label": {"fallback": "Go"}, "style": "ghost", "action": {"type": "dismiss"}}"""))
        rejects(bodyOf("""{"type": "badge", "text": "New"}"""))
        rejects(bodyOf("""{"type": "vstack", "children": []}"""))
    }

    @Test
    fun `bad ids, placement and dates`() {
        rejects(card(mapOf("id" to "\"Has Spaces\"")))
        rejects(card(mapOf("placement" to """{"tabs": ["explore"], "position": 3}""")))
        rejects(card(mapOf("placement" to """{"tabs": [], "position": 3}""")))
        rejects(card(mapOf("placement" to """{"tabs": ["discover"], "position": -1}""")))
        rejects(card(mapOf("placement" to """{"tabs": ["discover"], "position": 2.5}""")))
        rejects(card(mapOf("placement" to """{"tabs": ["discover"]}""")))
        assertEquals(Instant.parse("2026-11-07T18:30:00Z"), parse(card(mapOf("start" to "\"2026-11-08T00:00:00+05:30\""))).start)
        for (bad in listOf("\"2026-11-08\"", "\"2026-11-08T00:00:00.000Z\"", "\"2026-11-08T00:00:00+0530\"", "\"2026-13-01T00:00:00Z\"", "0")) {
            rejects(card(mapOf("end" to bad)), "end $bad")
        }
        rejects("[1]")
        rejects("not json")
        rejects(card() + "{}", "two values")
    }

    @Test
    fun `selection - schedule, dismiss, priority and cap`() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
        fun make(id: String, priority: Int, start: Long? = null, end: Long? = null): Sdui.Card {
            val o = mutableMapOf("id" to "\"$id\"", "placement" to """{"tabs": ["discover"], "position": 1, "priority": $priority}""")
            start?.let { o["start"] = "\"${iso.format(now.plusSeconds(it))}\"" }
            end?.let { o["end"] = "\"${iso.format(now.plusSeconds(it))}\"" }
            return parse(card(o))
        }
        val all = listOf(
            make("a", 1), make("b", 5), make("c", 3),
            make("future", 9, start = 3600), make("expired", 9, end = -3600), make("gone", 9),
        )
        assertEquals(listOf("b", "c"), Sdui.cards(all, "discover", now, dismissed = setOf("gone"), eligible = emptySet()).map { it.id })
        assertTrue(Sdui.cards(all, "following", now, emptySet(), emptySet()).isEmpty())
        // Ties go to the lower id; the edges of the schedule are inclusive.
        val tie = listOf(make("z", 1), make("m", 1, start = 0, end = 0))
        assertEquals(listOf("m", "z"), Sdui.cards(tie, "discover", now, emptySet(), emptySet()).map { it.id })
    }

    @Test
    fun `targeted card shows only when eligible, and duplicate ids never repeat`() {
        val now = Instant.now()
        val open = parse(card(mapOf("id" to "\"for-all\"")))
        val targeted = parse(card(mapOf("id" to "\"for-some\"", "audience" to "\"targeted\"")))
        assertEquals(listOf("for-all"), Sdui.cards(listOf(open, targeted), "discover", now, emptySet(), emptySet()).map { it.id })
        assertEquals(
            setOf("for-all", "for-some"),
            Sdui.cards(listOf(open, targeted), "discover", now, emptySet(), setOf("for-some")).map { it.id }.toSet(),
        )
        assertTrue(Sdui.cards(listOf(targeted), "discover", now, setOf("for-some"), setOf("for-some")).isEmpty())
        assertEquals(1, Sdui.cards(listOf(open, open.copy(priority = 9)), "discover", now, emptySet(), emptySet()).size)
    }

    @Test
    fun `placement counts posts, not rows, and waits for more pages`() {
        val c = parse(card()) // position 3
        val six = Sdui.placement(listOf(c), postCount = 6, hasMore = true)
        assertEquals(mapOf(3 to listOf(c)), six.beforePost)
        assertTrue(six.atEnd.isEmpty())

        assertEquals("more pages coming: wait, don't park it and jump later", Sdui.Placement(), Sdui.placement(listOf(c), 1, hasMore = true))
        assertEquals("feed exhausted and shorter: goes last", listOf(c), Sdui.placement(listOf(c), 1, hasMore = false).atEnd)
        assertEquals("exactly at the end of a loaded page", listOf(c), Sdui.placement(listOf(c), 3, hasMore = true).atEnd)

        val first = parse(card(mapOf("id" to "\"a\"", "placement" to """{"tabs": ["discover"], "position": 0, "priority": 5}""")))
        val second = parse(card(mapOf("id" to "\"b\"", "placement" to """{"tabs": ["discover"], "position": 0}""")))
        assertEquals("same position keeps priority order", listOf(first, second), Sdui.placement(listOf(first, second), 4, true).beforePost[0])
    }

    @Test
    fun `the next change is the soonest start or end still ahead on that tab`() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        fun make(id: String, tabs: String, start: String? = null, end: String? = null): Sdui.Card {
            val o = mutableMapOf("id" to "\"$id\"", "placement" to """{"tabs": [$tabs], "position": 1}""")
            start?.let { o["start"] = "\"$it\"" }
            end?.let { o["end"] = "\"$it\"" }
            return parse(card(o))
        }
        val all = listOf(
            make("ends", "\"discover\"", end = "2026-11-08T13:00:00Z"),
            make("starts", "\"discover\"", start = "2026-11-08T14:00:00Z"),
            make("past", "\"discover\"", start = "2026-11-01T00:00:00Z", end = "2026-11-02T00:00:00Z"),
            make("other-tab", "\"following\"", start = "2026-11-08T12:30:00Z"),
        )
        assertEquals("just after the inclusive end", Instant.parse("2026-11-08T13:00:00.001Z"), Sdui.nextChange(all, "discover", now))
        assertEquals(Instant.parse("2026-11-08T12:30:00Z"), Sdui.nextChange(all, "following", now))
        assertNull("nothing ahead", Sdui.nextChange(all, "discover", Instant.parse("2026-11-09T00:00:00Z")))
        assertNull(Sdui.nextChange(listOf(parse(card())), "discover", now))
    }


    private fun popup(id: String = "ota-hello", extra: String = "", body: String = """{"type": "text", "fallback": "Hello"}""") =
        """{"schema": 1, "id": "$id", "audience": "all"$extra, "body": $body}"""

    @Test
    fun `a popup parses with the same strictness as a card`() {
        val p = Sdui.parsePopup(
            popup(extra = """, "priority": 3, "closeButton": false""", body = """{"type": "lottie", "src": "lottie/confetti.json", "height": 200}"""),
            SCHEME,
            emptySet(),
            assets::contains,
        )
        assertEquals("ota-hello", p.id)
        assertEquals(3, p.priority)
        assertFalse(p.closeButton)
        assertEquals(Sdui.Node.Lottie("lottie/confetti.json", 200f, true), p.body)
        assertTrue("close button by default", Sdui.parsePopup(popup(), SCHEME, emptySet(), assets::contains).closeButton)

        for ((json, why) in listOf(
            popup(body = """{"type": "marquee"}""") to "unknown component",
            popup(body = """{"type": "lottie", "src": "lottie/missing.json"}""") to "lottie not in the release",
            popup().replace("\"all\"", "\"everyone\"") to "bad audience",
            popup().replace("\"schema\": 1", "\"schema\": 2") to "newer schema",
            popup(id = "Bad Id") to "bad id",
            popup(extra = """, "end": "tomorrow"""") to "bad date",
            """{"schema": 1, "id": "x", "audience": "all"}""" to "no body",
        )) {
            try {
                Sdui.parsePopup(json, SCHEME, emptySet(), assets::contains)
                fail("should drop the popup: $why")
            } catch (_: Sdui.Invalid) {
            }
        }
        // Card-only fields are ignored on a popup, as the validator only warns about them.
        Sdui.parsePopup(popup(extra = """, "placement": 5, "priority": "high""""), SCHEME, emptySet(), assets::contains)
    }

    @Test
    fun `popups - once per id, in schedule, best first, targeted only when eligible`() {
        val now = Instant.parse("2026-11-08T12:00:00Z")
        fun make(id: String, priority: Int = 0, extra: String = "") =
            Sdui.parsePopup(popup(id = id, extra = """, "priority": $priority$extra"""), SCHEME, emptySet(), assets::contains)
        val all = listOf(
            make("low", 1), make("b-high", 5), make("a-high", 5),
            make("later", 9, """, "start": "2026-11-09T00:00:00Z""""),
            make("over", 9, """, "end": "2026-11-07T00:00:00Z""""),
            make("seen", 9),
            Sdui.parsePopup(popup(id = "for-some", extra = """, "priority": 8""").replace("\"all\"", "\"targeted\""), SCHEME, emptySet(), assets::contains),
        )
        assertEquals(listOf("a-high", "b-high", "low"), Sdui.eligiblePopups(all, now, shown = setOf("seen"), eligible = emptySet()).map { it.id })
        assertEquals("for-some", Sdui.eligiblePopups(all, now, setOf("seen"), eligible = setOf("for-some")).first().id)
    }

    @Test
    fun `first name is filled in, or dropped tidily`() {
        val cases = listOf(
            Triple("Happy Diwali, {first_name}!", "Sooraj", "Happy Diwali, Sooraj!"),
            Triple("Happy Diwali, {first_name}!", null, "Happy Diwali!"),
            Triple("Hi {first_name}", "  ", "Hi"),
            Triple("{first_name}, your gift is here", null, "your gift is here"),
            Triple("No placeholder", "A", "No placeholder"),
            Triple("{first_name} welcome back!", null, "welcome back!"),
            Triple("Hi {first_name} there", null, "Hi there"),
            Triple("Hi {first_name}!", null, "Hi!"),
            Triple("Hey {first_name}, claim your reward", null, "Hey, claim your reward"),
            Triple("Bonjour {first_name} ! Ça va ?", null, "Bonjour ! Ça va ?"),
            Triple("Wait , {first_name}", null, "Wait"),
            Triple("{first_name}! Welcome back", null, "Welcome back"),
            Triple("{first_name}: your gift", null, "your gift"),
        )
        for ((text, name, want) in cases) assertEquals("$text / $name", want, Sdui.personalize(text, name))
    }

    // ---- Custom nodes ----

    private fun parsePopupWith(data: String, custom: Set<String>) = Sdui.parsePopup(data, SCHEME, custom, assets::contains)

    @Test
    fun unregisteredCustomTypeDropsDefinition() {
        val room = """{"type": "room", "id": "abc"}"""
        rejects(bodyOf(room))
        rejects(bodyOf("""{"type": "vstack", "children": [{"type": "text", "fallback": "ok"}, $room]}"""))
        try {
            parsePopupWith(popup(body = room), emptySet())
            fail("should drop the popup")
        } catch (_: Sdui.Invalid) {
        }
    }

    @Test
    fun registeredCustomTypeParses() {
        val body = """{"type": "room", "id": "abc", "showJoin": false, "n": 2.5, "tags": ["a", null], "meta": {"k": "v"}}"""
        val node = parse(bodyOf(body), custom = setOf("room")).body
        assertEquals(
            Sdui.Node.Custom(
                "room",
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("abc"),
                        "showJoin" to JsonPrimitive(false),
                        "n" to JsonPrimitive(2.5),
                        "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonNull)),
                        "meta" to JsonObject(mapOf("k" to JsonPrimitive("v"))),
                    ),
                ),
            ),
            node,
        )
        assertEquals("room", (node as Sdui.Node.Custom).type)
        assertFalse(node.fields.containsKey("type"))
        assertTrue(parsePopupWith(popup(body = """{"type": "room", "id": "abc"}"""), setOf("room")).body is Sdui.Node.Custom)
    }

    @Test
    fun customTypeCannotShadowABuiltIn() {
        // "text" without a key or fallback is invalid even when registered as custom.
        rejects(bodyOf("""{"type": "text"}"""))
        try {
            parse(bodyOf("""{"type": "text"}"""), custom = setOf("text"))
            fail("a registered name must not bypass the built-in text rules")
        } catch (_: Sdui.Invalid) {
        }
        assertTrue(parse(bodyOf("""{"type": "text", "fallback": "x"}"""), custom = setOf("text")).body is Sdui.Node.Text)
    }

    @Test
    fun customNodesCountTowardTheNodeAndDepthLimits() {
        val many = List(45) { """{"type": "room", "id": "a"}""" }.joinToString(",")
        try {
            parse(bodyOf("""{"type": "vstack", "children": [$many]}"""), custom = setOf("room"))
            fail("45 custom nodes exceed the limit")
        } catch (_: Sdui.Invalid) {
        }
        var deep = """{"type": "room", "id": "a"}"""
        repeat(6) { deep = """{"type": "vstack", "children": [$deep]}""" }
        try {
            parse(bodyOf(deep), custom = setOf("room"))
            fail("a custom node at depth 7 exceeds the limit")
        } catch (_: Sdui.Invalid) {
        }
    }

    @Test
    fun deeplinkWrongSchemeIsInvalid() {
        fun withUrl(url: String) = card(mapOf("tap" to """{"type": "deeplink", "url": "$url"}"""))
        try {
            Sdui.parseCard(withUrl("otherapp://x"), "shipsample", emptySet(), assets::contains)
            fail("a deeplink in another app's scheme must drop the card")
        } catch (_: Sdui.Invalid) {
        }
        val c = Sdui.parseCard(withUrl("shipsample://x"), "shipsample", emptySet(), assets::contains)
        assertEquals(Sdui.Action.Deeplink("shipsample://x"), c.tap)
        // The scheme is the host app's, not a fixed one.
        try {
            Sdui.parseCard(withUrl("shipsample://x"), "myapp", emptySet(), assets::contains)
            fail("shipsample:// is not valid for scheme myapp")
        } catch (_: Sdui.Invalid) {
        }
    }
}
