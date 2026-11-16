package com.zynclave.ship.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.zynclave.ship.ShipClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** The renderer: text resolution, colours, layout rules, images, and the card around them. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text measurement, for the layout tests
class ShipViewsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val sink = RecordingSink()
    private val opened = mutableListOf<Uri>()

    @Before
    fun clean() {
        app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun options(
        language: () -> String = { "en" },
        appString: (String) -> String? = { null },
        color: (String, Boolean) -> Color? = { _, _ -> null },
        components: Map<String, @androidx.compose.runtime.Composable (Sdui.Node.Custom) -> Unit> = emptyMap(),
    ) = ShipUiOptions(
        deeplinkScheme = TEST_SCHEME, openDeeplink = { opened += it }, language = language,
        appString = appString, color = color, analytics = sink, components = components,
    )

    private fun cards(client: ShipClient = testClient(temp.newFolder()), options: ShipUiOptions = options()) =
        ShipCards(app, client, options)

    private fun card(body: String, dismissible: Boolean = true) = Sdui.parseCard(
        """{"schema": 1, "id": "promo", "audience": "all", "dismissible": $dismissible,
            "placement": {"tabs": ["discover"], "position": 0}, "body": $body}""",
        TEST_SCHEME, setOf("room"),
    ) { false }

    private fun show(card: Sdui.Card, cards: ShipCards = cards(), width: Int? = null) {
        compose.setContent {
            MaterialTheme {
                if (width != null) Box(Modifier.width(width.dp)) { ShipCard(card, cards) } else ShipCard(card, cards)
            }
        }
    }

    // ----- text -----

    private fun runtime(files: Map<String, ByteArray> = emptyMap(), revert: Boolean = false, options: ShipUiOptions = options()): ShipRuntime {
        val client = testClient(temp.newFolder(), if (files.isEmpty() && !revert) null else "r1", files, revert)
        return ShipRuntime(options, client.content)
    }

    @Test
    fun `text resolves OTA in the app's language, then the app's string, then the fallback`() {
        val strings = mapOf("strings/hi.json" to text("""{"title": "OTA hindi"}"""), "strings/en.json" to text("""{"title": "OTA english", "only_en": "english only"}"""))
        val value = Sdui.TextValue("title", "literal")

        assertEquals("OTA hindi", runtime(strings, options = options(language = { "hi" }, appString = { "app $it" })).text(value))
        // No fr table: the app's string, never the English OTA one.
        assertEquals("app title", runtime(strings, options = options(language = { "fr" }, appString = { "app $it" })).text(value))
        assertEquals("literal", runtime(strings, options = options(language = { "fr" })).text(value))
        assertEquals("a key only English has: not borrowed for French", "literal", runtime(strings, options = options(language = { "fr" })).text(Sdui.TextValue("only_en", "literal")))
        assertEquals("no key: the fallback", "literal", runtime(strings, options = options(appString = { "app" })).text(Sdui.TextValue(null, "literal")))
        assertEquals("", runtime().text(Sdui.TextValue("missing", null)))
    }

    @Test
    fun `OTA strings do not apply after a revert`() {
        val strings = mapOf("strings/en.json" to text("""{"title": "OTA"}"""))
        assertEquals("app", runtime(strings, revert = true, options = options(appString = { "app" })).text(Sdui.TextValue("title", "literal")))
    }

    @Test
    fun `personalize defaults to dropping the first name, and the app can replace it`() {
        val value = Sdui.TextValue(null, "Happy Diwali, {first_name}!")
        assertEquals("Happy Diwali!", runtime().text(value))
        val named = ShipRuntime(
            ShipUiOptions(deeplinkScheme = TEST_SCHEME, openDeeplink = {}, personalize = { Sdui.personalize(it, "Ann") }),
            testClient(temp.newFolder()).content,
        )
        assertEquals("Happy Diwali, Ann!", named.text(value))
    }

    @Test
    fun `the default language is the device's`() {
        val default = ShipUiOptions(deeplinkScheme = TEST_SCHEME, openDeeplink = {})
        assertEquals(java.util.Locale.getDefault().language.ifEmpty { "en" }, default.language())
    }

    @Test
    fun `a colour comes from the app, then the release theme, then the SDK default`() {
        val theme = mapOf("config/theme.json" to text("""{"primary300": "#FF7A00"}"""))
        val withTheme = runtime(theme)
        assertEquals(Color(0xFFFF7A00), withTheme.color("primary", dark = false))
        assertNull("not overridable by a release", withTheme.color("surface", dark = false))
        val app = runtime(theme, options = options(color = { token, dark -> if (token == "primary") Color.Red.takeIf { !dark } else null }))
        assertEquals(Color.Red, app.color("primary", dark = false))
        assertEquals("the app declined for dark: the release applies", Color(0xFFFF7A00), app.color("primary", dark = true))
        assertNull(runtime().color("primary", dark = false))
        assertNull("a revert drops the release theme", runtime(theme, revert = true).color("primary", dark = false))
    }

    // ----- views -----

    @Test
    fun `shows keyed text, falls back for unknown keys, and drops a missing first name`() {
        val c = card(
            """{"type": "vstack", "children": [
                 {"type": "text", "key": "promo_title", "fallback": "unused"},
                 {"type": "text", "key": "no_such_key_anywhere", "fallback": "Happy Diwali, {first_name}!"},
                 {"type": "badge", "text": {"fallback": "NEW"}}
               ]}""",
        )
        show(c, cards(options = options(appString = { if (it == "promo_title") "Diwali coins" else null })))
        compose.onNodeWithText("Diwali coins").assertIsDisplayed()
        compose.onNodeWithText("Happy Diwali!").assertIsDisplayed()
        compose.onNodeWithText("NEW").assertIsDisplayed()
    }

    @Test
    fun `a key only the release has comes from the release's strings in the app's language`() {
        val client = testClient(temp.newFolder(), "str-1", mapOf("strings/en.json" to text("""{"diwali_promo_title": "Diwali coins are here"}""")))
        val c = card(
            """{"type": "vstack", "children": [
                 {"type": "text", "key": "diwali_promo_title", "fallback": "unused"},
                 {"type": "text", "key": "not_in_release_either", "fallback": "Fallback"}
               ]}""",
        )
        show(c, cards(client))
        compose.onNodeWithText("Diwali coins are here").assertIsDisplayed()
        compose.onNodeWithText("Fallback").assertIsDisplayed()
    }

    @Test
    fun `a button opens its deep link through the app, a dismiss button dismisses once and for good`() {
        val c = card(
            """{"type": "vstack", "children": [
                 {"type": "button", "label": {"fallback": "See rewards"}, "action": {"type": "deeplink", "url": "shipsample://rewards"}},
                 {"type": "button", "label": {"fallback": "Not now"}, "action": {"type": "dismiss"}}
               ]}""",
        )
        val cards = cards()
        show(c, cards)
        compose.onNodeWithText("See rewards").performClick()
        assertEquals(listOf("shipsample://rewards"), opened.map { it.toString() })

        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Not now").performClick()
        assertEquals("kept across launches", setOf("promo"), app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getStringSet("dismissed", null))
        assertEquals("every button tap is counted, the dismissal once however many times", listOf("sdui_tap", "sdui_tap", "sdui_dismiss", "sdui_tap"), sink.names)
    }

    @Test
    fun `a component the app registered is drawn from its fields, and an unregistered one draws nothing`() {
        val c = card("""{"type": "room", "id": "r42"}""")
        show(c, cards(options = options(components = mapOf("room" to { node -> Text("room ${(node.fields["id"] as kotlinx.serialization.json.JsonPrimitive).content}") }))))
        compose.onNodeWithText("room r42").assertIsDisplayed()
    }

    @Test
    fun `a row shares its width between flexible children instead of the first taking it all`() {
        val c = card(
            """{"type": "hstack", "children": [
                 {"type": "vstack", "children": [{"type": "text", "fallback": "Left"}]},
                 {"type": "button", "label": {"fallback": "Go"}, "action": {"type": "dismiss"}},
                 {"type": "badge", "text": {"fallback": "NEW"}}
               ]}""",
            dismissible = false,
        )
        show(c, width = 360)
        for (label in listOf("Left", "Go", "NEW")) {
            compose.onNodeWithText(label).assertIsDisplayed()
            val width = compose.onNodeWithText(label).fetchSemanticsNode().size.width
            assertTrue("$label is ${width}px wide", width >= 10)
        }
    }

    @Test
    fun `a long text in a row wraps instead of pushing its siblings out`() {
        val long = "Celebrate with us all week long ".repeat(8).trim()
        val c = card(
            """{"type": "hstack", "children": [
                 {"type": "text", "fallback": "$long"},
                 {"type": "badge", "text": {"fallback": "NEW"}},
                 {"type": "button", "style": "text", "label": {"fallback": "Open"}, "action": {"type": "dismiss"}}
               ]}""",
            dismissible = false,
        )
        show(c, width = 360)
        for (label in listOf("NEW", "Open")) {
            val width = compose.onNodeWithText(label).fetchSemanticsNode().size.width
            assertTrue("$label is ${width}px wide", width >= 10)
        }
    }

    @Test
    fun `a nested row is as flexible as its children`() {
        fun node(json: String) = card(json).body
        assertEquals(RowFlex.Fill, node("""{"type": "hstack", "children": [{"type": "spacer"}, {"type": "vstack", "children": [{"type": "spacer"}]}]}""").rowFlex)
        assertEquals(RowFlex.Shrink, node("""{"type": "hstack", "children": [{"type": "badge", "text": {"fallback": "x"}}, {"type": "text", "fallback": "y"}]}""").rowFlex)
        assertEquals(RowFlex.None, node("""{"type": "hstack", "children": [{"type": "badge", "text": {"fallback": "x"}}]}""").rowFlex)

        val c = card(
            """{"type": "hstack", "children": [
                 {"type": "hstack", "children": [
                   {"type": "vstack", "children": [{"type": "text", "fallback": "Inner"}]},
                   {"type": "button", "label": {"fallback": "Go"}, "action": {"type": "dismiss"}}
                 ]},
                 {"type": "badge", "text": {"fallback": "NEW"}}
               ]}""",
            dismissible = false,
        )
        show(c, width = 360)
        val width = compose.onNodeWithText("NEW").fetchSemanticsNode().size.width
        assertTrue("NEW is ${width}px wide", width >= 10)
    }

    @Test
    fun `the close button exists only on dismissible cards, and is named by the app`() {
        show(card("""{"type": "spacer"}""", dismissible = false))
        compose.onAllNodesWithContentDescription("Close").assertCountEquals(0)
    }

    @Test
    fun `the close button takes its name from the app's string`() {
        show(card("""{"type": "spacer"}"""), cards(options = options(appString = { if (it == "common_close") "Fermer" else null })))
        compose.onAllNodesWithContentDescription("Fermer").assertCountEquals(1)
    }

    // ----- images -----

    @Test
    fun `card images decode once per release, at the size asked, and only the current release is kept`() {
        fun release(id: String, name: String = "banner") = releaseFiles(temp.newFolder(), id, "images/$name.png" to image(800, 400))
        val one = release("img-1")
        val decoded = SduiImages.load(one, "images/banner.png", maxWidth = 200)!!
        assertEquals("800 / 4 = 200", 200, decoded.width)
        assertSame("decoded once", decoded, SduiImages.load(one, "images/banner.png", maxWidth = 200))
        assertSame(decoded, SduiImages.cached(one, "images/banner.png", 200))

        val two = release("img-2")
        assertNull("another release has not decoded it yet", SduiImages.cached(two, "images/banner.png", 200))
        val next = SduiImages.load(two, "images/banner.png", maxWidth = 200)
        assertTrue(next !== decoded)
        assertNull("the old release's image is let go", SduiImages.cached(one, "images/banner.png", 200))

        val wider = SduiImages.load(two, "images/banner.png", maxWidth = 400)!!
        assertEquals("800 / 2 = 400", 400, wider.width)
        assertNull("the narrow decode does not serve a wider screen", SduiImages.cached(two, "images/banner.png", 800))
        assertSame("a wider decode serves a narrower screen", wider, SduiImages.cached(two, "images/banner.png", 200))

        SduiImages.load(release("img-3", name = "other"), "images/other.png", maxWidth = 200)
        assertEquals("only the current release's images are held", 1, SduiImages.size)
    }

    @Test
    fun `a new release's image replaces the old one on screen`() {
        fun release(id: String, width: Int) = releaseFiles(temp.newFolder(), id, "images/hero.png" to image(width, 10))
        val first = release("swap-1", width = 30)
        val second = release("swap-2", width = 40)
        val seen = mutableListOf<Int?>()
        compose.setContent {
            var files by remember { mutableStateOf(first) }
            val width = releaseImage("images/hero.png", files)?.width
            if (seen.lastOrNull() != width) seen += width
            // The release changes once the first image is on screen, as when Ship applies a new one.
            LaunchedEffect(width) { if (width == 30) files = second }
        }
        compose.waitUntil(5_000) { seen.lastOrNull() == 40 }
        assertEquals("never the old image under the new release", listOf(30, null, 40), seen)
    }

    @Test
    fun `a wider window keeps the current image up while the sharper one decodes`() {
        val files = releaseFiles(temp.newFolder(), "widen-1", "images/wide.png" to image(800, 10))
        val seen = mutableListOf<Int?>()
        compose.setContent {
            var window by remember { mutableIntStateOf(200) }
            val width = releaseImage("images/wide.png", files, maxWidth = window)?.width
            if (seen.lastOrNull() != width) seen += width
            LaunchedEffect(width) { if (width == 200) window = 400 }
        }
        compose.waitUntil(5_000) { seen.lastOrNull() == 400 }
        assertEquals("200 px stays up until the 400 px decode replaces it, never nothing", listOf(200, 400), seen)
    }

    @Test
    fun `waiting for a card's moment survives an early wake`() = runTest {
        val target = Instant.parse("2026-11-08T12:00:01Z")
        // The wall clock lags the delay: the first wake still reads 1 ms short.
        val readings = ArrayDeque(listOf(
            Instant.parse("2026-11-08T12:00:00Z"),
            Instant.parse("2026-11-08T12:00:00.999Z"),
            Instant.parse("2026-11-08T12:00:01Z"),
        ))
        waitUntil(target) { readings.removeFirst() }
        assertTrue("checked the clock until it reached the target", readings.isEmpty())
        assertEquals("1001 ms, then 2 ms more (each rounded up)", 1003L, testScheduler.currentTime)

        waitUntil(target) { Instant.parse("2026-11-08T12:00:05Z") } // already past: no wait
        assertEquals(1003L, testScheduler.currentTime)
    }
}
