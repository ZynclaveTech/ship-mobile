package com.zynclave.ship.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Which cards a feed tab shows: the applied release's, valid, in schedule, not dismissed, eligible. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShipCardsTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val sink = RecordingSink()
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    @Before
    fun clean() {
        app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun file(id: String, extra: String = "", audience: String = "all", position: Int = 0, body: String = """{"type": "spacer"}""") =
        "sdui/cards/$id.json" to text(
            """{"schema": 1, "id": "$id", "audience": "$audience", "placement": {"tabs": ["discover"], "position": $position}$extra, "body": $body}""",
        )

    private fun options(eligible: Set<String> = emptySet()) = ShipUiOptions(
        deeplinkScheme = TEST_SCHEME, openDeeplink = {}, analytics = sink,
        eligibility = { ShipEligible(eligible, emptySet()) },
        components = mapOf("room" to { }),
    )

    private fun cards(files: Map<String, ByteArray>, revert: Boolean = false, options: ShipUiOptions = options()) =
        ShipCards(app, testClient(temp.newFolder(), "r1", files, revert), options)

    @Test
    fun `valid cards in best-first order, invalid and duplicate ones skipped`() {
        val cards = cards(
            mapOf(
                file("b", position = 1),
                file("a", position = 1),
                "sdui/cards/bad.json" to text("""{"schema": 1, "id": "bad", "audience": "all", "placement": {"tabs": ["discover"], "position": 0}, "body": {"type": "video"}}"""),
                "sdui/cards/dup.json" to text("""{"schema": 1, "id": "a", "audience": "all", "placement": {"tabs": ["discover"], "position": 9}, "body": {"type": "spacer"}}"""),
                "sdui/cards/notjson.json" to text("nope"),
            ),
        )
        assertEquals(listOf("a", "b"), cards.cards("discover", now).map { it.id })
        assertEquals("parsed once per release", cards.all(), cards.all())
        assertEquals(emptyList<Sdui.Card>(), cards.cards("following", now))
    }

    @Test
    fun `custom component types come from the app's registered components`() {
        val cards = cards(mapOf(file("with-room", body = """{"type": "room", "id": "x"}""")))
        assertEquals(listOf("with-room"), cards.cards("discover", now).map { it.id })
        val plain = ShipCards(app, testClient(temp.newFolder(), "r1", mapOf(file("with-room", body = """{"type": "room", "id": "x"}"""))), ShipUiOptions(deeplinkScheme = TEST_SCHEME, openDeeplink = {}))
        assertEquals(emptyList<Sdui.Card>(), plain.cards("discover", now))
    }

    @Test
    fun `nothing after a revert to the embedded content, or with no release`() {
        assertEquals(emptyList<Sdui.Card>(), cards(mapOf(file("a")), revert = true).cards("discover", now))
        assertEquals(emptyList<Sdui.Card>(), ShipCards(app, testClient(temp.newFolder()), options()).cards("discover", now))
    }

    @Test
    fun `targeted cards show only when the backend listed them`() {
        val files = mapOf(file("vip", audience = "targeted"), file("all"))
        assertEquals(listOf("all"), cards(files).cards("discover", now).map { it.id })
        assertEquals(setOf("all", "vip"), cards(files, options = options(eligible = setOf("vip"))).cards("discover", now).map { it.id }.toSet())
    }

    @Test
    fun `schedule decides, and the next change is the soonest start or end ahead`() {
        val cards = cards(
            mapOf(
                file("later", extra = """, "start": "2026-11-08T13:00:00Z""""),
                file("ending", extra = """, "end": "2026-11-08T12:30:00Z""""),
            ),
        )
        assertEquals(listOf("ending"), cards.cards("discover", now).map { it.id })
        assertEquals(Instant.parse("2026-11-08T12:30:00.001Z"), cards.nextChange("discover", now))
        assertNull(cards.nextChange("following", now))
    }

    @Test
    fun `a dismissed card stays gone across launches, and is reported once`() {
        val files = mapOf(file("a"), file("b", position = 1))
        val cards = cards(files)
        cards.dismiss("a")
        cards.dismiss("a")
        assertEquals(listOf("b"), cards.cards("discover", now).map { it.id })
        assertEquals(listOf("sdui_dismiss"), sink.names)
        assertEquals(mapOf("definition_id" to "a", "release_id" to "r1"), sink.events.single().second)

        assertEquals("the next process reads it back", listOf("b"), cards(files).cards("discover", now).map { it.id })
        assertEquals(setOf("a"), app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getStringSet("dismissed", null))
    }

    @Test
    fun `rememberShipCards follows dismissals and gives nothing without a tab`() {
        val cards = cards(mapOf(file("a"), file("b", position = 1)))
        var tab by mutableStateOf<String?>("discover")
        var shown: List<Sdui.Card> = emptyList()
        compose.setContent { MaterialTheme { shown = rememberShipCards(cards, tab) } }
        compose.waitUntil(5_000) { shown.map { it.id } == listOf("a", "b") }
        cards.dismiss("a")
        compose.waitUntil(5_000) { shown.map { it.id } == listOf("b") }
        compose.runOnIdle { tab = null }
        compose.waitForIdle()
        compose.waitUntil(5_000) { shown.isEmpty() }
    }

    @Test
    fun `a dismissal in one instance moves the other, which stops listing the card`() {
        val client = testClient(temp.newFolder(), "r1", mapOf(file("a"), file("b", position = 1)))
        val a = ShipCards(app, client, options())
        val b = ShipCards(app, client, options())
        assertEquals(listOf("a", "b"), b.cards("discover", now).map { it.id })
        val before = b.changes.value

        a.dismiss("a")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("one bump on the other instance", before + 1, b.changes.value)
        assertEquals(listOf("b"), b.cards("discover", now).map { it.id })
        assertEquals("the dismissing instance bumps once, not twice", 1, a.changes.value)
    }

    @Test
    fun `a change to other preferences does not move an instance`() {
        val cards = cards(mapOf(file("a")))
        app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).edit().putString("other", "x").commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, cards.changes.value)
    }
}
