package com.zynclave.ship.ui

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** At least half on screen for a full second, once per feed load. */
class SduiImpressionTrackerTest {
    private val t0 = 1_000_000L

    @Test
    fun `counts after one second at least half visible`() {
        val tracker = SduiImpressionTracker()
        assertEquals(emptyList<String>(), tracker.update(mapOf("a" to 0.6f), t0))
        assertEquals(emptyList<String>(), tracker.update(mapOf("a" to 0.9f), t0 + 990))
        assertEquals(listOf("a"), tracker.update(mapOf("a" to 0.5f), t0 + 1_000))
    }

    @Test
    fun `counts once per feed load`() {
        val tracker = SduiImpressionTracker()
        tracker.update(mapOf("a" to 1f), t0)
        assertEquals(listOf("a"), tracker.update(mapOf("a" to 1f), t0 + 1_000))
        assertEquals("already counted", emptyList<String>(), tracker.update(mapOf("a" to 1f), t0 + 5_000))
        tracker.update(emptyMap(), t0 + 6_000)
        tracker.update(mapOf("a" to 1f), t0 + 7_000)
        assertEquals("scrolled back: still once", emptyList<String>(), tracker.update(mapOf("a" to 1f), t0 + 9_000))

        val nextLoad = SduiImpressionTracker() // a new feed load: every card may count again
        nextLoad.update(mapOf("a" to 1f), t0 + 10_000)
        assertEquals(listOf("a"), nextLoad.update(mapOf("a" to 1f), t0 + 11_000))
    }

    @Test
    fun `the dwell restarts when it drops below half, and off-screen cards stop the clock`() {
        val tracker = SduiImpressionTracker()
        tracker.update(mapOf("a" to 0.8f), t0)
        tracker.update(mapOf("a" to 0.4f), t0 + 600)
        assertEquals("restarted at 1.2 s", emptyList<String>(), tracker.update(mapOf("a" to 0.8f), t0 + 1_200))
        assertEquals(listOf("a"), tracker.update(mapOf("a" to 0.8f), t0 + 2_200))

        val off = SduiImpressionTracker()
        off.update(mapOf("a" to 1f), t0)
        off.update(emptyMap(), t0 + 500)
        assertEquals(emptyList<String>(), off.update(mapOf("a" to 1f), t0 + 1_100))
    }

    @Test
    fun `several cards are tracked apart and reported in order`() {
        val tracker = SduiImpressionTracker()
        tracker.update(mapOf("b" to 1f, "a" to 1f), t0)
        assertEquals(listOf("a", "b"), tracker.update(mapOf("b" to 1f, "a" to 1f, "c" to 1f), t0 + 1_000))
        assertEquals(listOf("c"), tracker.update(mapOf("c" to 1f), t0 + 2_000))
    }

    @Test
    fun `a new feed load is a finished refresh, not a post removed in place`() {
        val loads = SduiFeedLoads()
        assertEquals(0, loads.observe(false))
        assertEquals("recomposition with the same state", 0, loads.observe(false))
        assertEquals("refreshing", 0, loads.observe(true))
        assertEquals(0, loads.observe(true))
        assertEquals("the refresh finished: a new load", 1, loads.observe(false))
        assertEquals("a post hidden in place: no refresh, same load", 1, loads.observe(false))
        loads.observe(true)
        assertEquals(2, loads.observe(false))
    }

    @Test
    fun `the next deadline is when the earliest pending card qualifies`() {
        val tracker = SduiImpressionTracker()
        assertNull(tracker.nextDeadline())
        tracker.update(mapOf("a" to 1f), t0)
        assertEquals(t0 + 1_000, tracker.nextDeadline())
        tracker.update(mapOf("a" to 1f, "b" to 1f), t0 + 400)
        assertEquals("a is still the earliest", t0 + 1_000, tracker.nextDeadline())
        tracker.update(mapOf("b" to 1f), t0 + 500)
        assertEquals(t0 + 1_400, tracker.nextDeadline())
    }
}

/** The events themselves, and where cards and popups send them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShipAnalyticsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val sink = RecordingSink()
    private val events get() = sink.events

    @Before
    fun clean() {
        app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private val options = ShipUiOptions(deeplinkScheme = TEST_SCHEME, openDeeplink = {}, analytics = sink)

    private fun cards(releaseId: String? = null) = ShipCards(app, testClient(temp.newFolder(), releaseId, if (releaseId == null) emptyMap() else mapOf("a.txt" to text("x"))), options)

    @Test
    fun `event names and parameters are the ones the dashboards read`() {
        val a = ShipAnalytics(sink) { null }
        a.impression("diwali")
        a.tap("diwali", ShipAnalytics.TapTarget.Component)
        a.dismiss("diwali")
        a.popupShown("launch")
        a.popupAction("launch", ShipAnalytics.PopupAction.Close)
        val base = mapOf("definition_id" to "diwali", "release_id" to "none")
        assertEquals(
            listOf(
                "sdui_impression" to base,
                "sdui_tap" to base + ("target" to "component"),
                "sdui_dismiss" to base,
                "popup_shown" to mapOf("definition_id" to "launch", "release_id" to "none"),
                "popup_action" to mapOf("definition_id" to "launch", "release_id" to "none", "action" to "close"),
            ),
            events,
        )
    }

    @Test
    fun `no sink, no events and no crash`() {
        ShipAnalytics(null) { "r" }.impression("x")
    }

    @Test
    fun `an impression is logged once a card has been on screen for a second, at its deadline`() {
        var now = 0L
        val impressions = SduiImpressions({ now }) { ShipAnalytics(sink) { null }.impression(it) }
        impressions.report("diwali", top = 100f, bottom = 300f, height = 200f)
        assertEquals(1_000L, impressions.deadline.value)
        now = 1_000
        impressions.tick()
        impressions.report("diwali", top = 90f, bottom = 290f, height = 200f)
        assertEquals(listOf("sdui_impression"), sink.names)
        assertNull("counted: nothing pending", impressions.deadline.value)
    }

    @Test
    fun `visibility excludes what the floating header and the bottom bar cover`() {
        val viewport = 100f to 900f // the list minus its content padding
        assertEquals(1f, visibleFraction(200f, 400f, 200f, viewport))
        assertEquals("half under the header", 0.5f, visibleFraction(0f, 200f, 200f, viewport))
        assertEquals("entirely under the header", 0f, visibleFraction(0f, 100f, 100f, viewport))
        assertEquals("a quarter under the bottom bar", 0.75f, visibleFraction(750f, 950f, 200f, viewport))
        assertEquals("already clipped by the list", 0.5f, visibleFraction(500f, 600f, 200f, viewport))
        assertEquals("no viewport: the clipped bounds", 0.5f, visibleFraction(0f, 100f, 200f, null))
        assertEquals(0f, visibleFraction(0f, 0f, 0f, viewport))
    }

    @Test
    fun `a card under the header is not counted, and a viewport change re-evaluates a card that sits still`() {
        var now = 0L
        val impressions = SduiImpressions({ now }) { ShipAnalytics(sink) { null }.impression(it) }
        impressions.viewport = 200f to 1_000f // a 200 px header over the top of the list
        impressions.report("hidden", top = 0f, bottom = 180f, height = 180f)
        assertNull("nothing pending: it is under the header", impressions.deadline.value)
        now = 5_000
        impressions.tick()
        assertEquals(emptyList<String>(), sink.names)

        impressions.viewport = 0f to 1_000f // the header hides away: the card is now seen, without moving
        assertEquals(6_000L, impressions.deadline.value)
        now = 6_000
        impressions.tick()
        assertEquals(listOf("sdui_impression"), sink.names)
    }

    @Test
    fun `a new feed load lets every card count again`() {
        var now = 0L
        val cards = ShipCards(app, testClient(temp.newFolder()), options, clock = { now })
        cards.impressions.report("a", 0f, 100f, 100f)
        now = 1_000
        cards.impressions.tick()
        assertEquals(1, sink.names.size)

        cards.observeRefresh(true)
        cards.observeRefresh(false)
        now = 2_000
        cards.impressions.report("a", 0f, 100f, 100f)
        now = 3_000
        cards.impressions.tick()
        assertEquals(listOf("sdui_impression", "sdui_impression"), sink.names)
    }

    private fun card(body: String, tap: String? = null) = Sdui.parseCard(
        """{"schema": 1, "id": "promo", "audience": "all", "placement": {"tabs": ["discover"], "position": 0},
            ${tap?.let { "\"tap\": $it," } ?: ""} "body": $body}""",
        TEST_SCHEME, emptySet(),
    ) { false }

    @Test
    fun `a feed card reports how much of it is on screen and counts after a second`() {
        var now = 0L
        val cards = ShipCards(app, testClient(temp.newFolder(), "r1", mapOf("a.txt" to text("x"))), options, clock = { now })
        compose.setContent { MaterialTheme { ShipCard(card("""{"type": "text", "fallback": "Diwali"}"""), cards) } }
        compose.waitForIdle()
        assertEquals("fully on screen: the second has started", 1_000L, cards.impressions.deadline.value)
        now = 1_000
        compose.runOnIdle { cards.impressions.tick() }
        assertEquals(listOf("sdui_impression"), sink.names)
        assertEquals("r1", events.single().second["release_id"])
    }

    @Test
    fun `card tap, component tap and a single dismiss are logged`() {
        compose.setContent {
            MaterialTheme {
                ShipCard(
                    card(
                        """{"type": "vstack", "children": [
                             {"type": "text", "fallback": "Tap me"},
                             {"type": "button", "label": {"fallback": "Not now"}, "action": {"type": "dismiss"}}
                           ]}""",
                        tap = """{"type": "deeplink", "url": "shipsample://rewards/daily"}""",
                    ),
                    cards(),
                )
            }
        }
        // The text itself, not the merged card node (whose centre is the button).
        compose.onNodeWithText("Tap me", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithContentDescription("Close").performClick() // already dismissed: not counted again
        assertEquals(listOf("sdui_tap", "sdui_tap", "sdui_dismiss"), sink.names)
        assertEquals(listOf("card", "component"), events.take(2).map { it.second["target"] })
    }

    @Test
    fun `a popup logs shown, then close or component`() {
        val popup = Sdui.parsePopup(
            """{"schema": 1, "id": "launch", "audience": "all", "body": {"type": "vstack", "children": [
                 {"type": "text", "fallback": "Hello"},
                 {"type": "button", "label": {"fallback": "See it"}, "action": {"type": "deeplink", "url": "shipsample://rewards/daily"}}
               ]}}""",
            TEST_SCHEME, emptySet(),
        ) { false }
        val popups = ShipPopups(app, testClient(temp.newFolder()), options)
        compose.setContent { MaterialTheme { ShipPopupView(popup, popups) } }
        compose.waitUntil(5_000) { "popup_shown" in sink.names }
        compose.onNodeWithText("See it").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(5f, 5f)) }
        assertEquals(listOf("popup_shown", "popup_action", "popup_action", "popup_action"), sink.names)
        assertEquals(listOf("component", "close", "close"), events.drop(1).map { it.second["action"] })
    }
}
