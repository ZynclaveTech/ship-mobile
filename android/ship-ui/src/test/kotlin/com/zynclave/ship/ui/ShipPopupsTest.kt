package com.zynclave.ship.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** The popup rules on a device: first launch, once per id, one per session, the hooks, and the view. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShipPopupsTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = TestScope()
    private val io = StandardTestDispatcher(scope.testScheduler)
    private val sink = RecordingSink()
    private val now = Instant.parse("2026-11-08T12:00:00Z")

    @Before
    fun fresh() {
        app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).edit().clear().commit()
        ShipPopups.Process.reset()
    }

    private fun popup(id: String, priority: Int = 0, body: String = """{"type": "text", "fallback": "Hello $id"}""") =
        "sdui/popups/$id.json" to text("""{"schema": 1, "id": "$id", "audience": "all", "priority": $priority, "body": $body}""")

    private fun options(
        embedded: Boolean = false,
        gate: () -> Boolean = { true },
        preflight: suspend (Sdui.Popup) -> Boolean = { true },
        closed: (Sdui.Popup) -> Unit = {},
        eligible: Set<String> = emptySet(),
        components: Map<String, @androidx.compose.runtime.Composable (Sdui.Node.Custom) -> Unit> = emptyMap(),
        open: (android.net.Uri) -> Unit = {},
    ) = ShipUiOptions(
        deeplinkScheme = TEST_SCHEME,
        openDeeplink = open,
        popupGate = gate,
        popupPreflight = preflight,
        onPopupClosed = closed,
        eligibility = { ShipEligible(emptySet(), eligible) },
        analytics = sink,
        components = components,
        embeddedPopupsAssetDir = if (embedded) "ship-embedded" else null,
    )

    private fun popups(
        client: com.zynclave.ship.ShipClient,
        options: ShipUiOptions = options(),
        updated: Boolean = false,
    ) = ShipPopups(app, client, options, scope, io, armDelayMs = 1_500, installWasUpdated = { updated })

    /** A process after the first launch: the launch counter already says 1. */
    private fun secondLaunch() {
        app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).edit().putInt("popups_launches", 1).commit()
    }

    private fun arm(p: ShipPopups) {
        val job = CoroutineScope(StandardTestDispatcher(scope.testScheduler)).launch { p.arm(now) }
        scope.testScheduler.advanceUntilIdle()
        check(job.isCompleted) { "arm did not finish" }
    }

    @Test
    fun `nothing on the first launch after install, then the next process shows it`() {
        val root = temp.newFolder()
        val client = testClient(root, "r1", mapOf(popup("hello")))
        val first = popups(client)
        arm(first)
        assertNull(first.current.value)
        // The launch is counted once per process: a later check in it still says first launch.
        arm(first)
        assertNull(first.current.value)

        ShipPopups.Process.reset() // the next process
        val next = popups(client)
        arm(next)
        assertEquals("hello", next.current.value?.id)
    }

    @Test
    fun `secondInstanceInSameProcessKeepsSessionRules`() {
        val client = testClient(temp.newFolder(), "r1", mapOf(popup("a", 5), popup("b", 1)))
        val prefs = app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE)
        secondLaunch()
        val a = popups(client)
        arm(a)
        a.markShown(a.current.value!!)
        a.close()
        assertEquals(2, prefs.getInt("popups_launches", 0))

        val b = popups(client) // an Activity restart: a new instance, the same process
        arm(b)
        assertEquals("no second launch counted", 2, prefs.getInt("popups_launches", 0))
        assertNull("one popup per session, across instances", b.current.value)
    }

    @Test
    fun `a process whose gate never opens still counts its launch`() {
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("a"))), options(gate = { false }))
        arm(p)
        assertEquals(1, app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getInt("popups_launches", 0))
        arm(p)
        assertEquals("once per process", 1, app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getInt("popups_launches", 0))
    }

    @Test
    fun `throwingPreflightIsTreatedAsFalse`() {
        secondLaunch()
        val client = testClient(temp.newFolder(), "r1", mapOf(popup("first", 5), popup("second", 1)))
        val p = popups(client, options(preflight = { if (it.id == "first") error("boom") else true }))
        arm(p)
        assertEquals("the throwing popup is skipped, the next is tried", "second", p.current.value?.id)
        assertNull(app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getStringSet("popups_shown", null))
    }

    @Test
    fun `an update is not a first launch, even with no launches counted`() {
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("hello"))), updated = true)
        arm(p)
        assertEquals("hello", p.current.value?.id)
    }

    @Test
    fun `best first, once per id, one per session`() {
        secondLaunch()
        val client = testClient(temp.newFolder(), "r1", mapOf(popup("low", 1), popup("high", 5)))
        val p = popups(client)
        arm(p)
        assertEquals("high", p.current.value?.id)

        p.markShown(p.current.value!!)
        p.close()
        arm(p)
        assertNull("one popup per session", p.current.value)
        assertEquals(listOf("popup_shown"), sink.names)
        assertEquals(setOf("high"), app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getStringSet("popups_shown", null))

        ShipPopups.Process.reset()
        val next = popups(client) // the next process: "high" is spent
        arm(next)
        assertEquals("low", next.current.value?.id)
    }

    @Test
    fun `targeted popups show only when the backend listed them`() {
        secondLaunch()
        val targeted = "sdui/popups/vip.json" to text("""{"schema": 1, "id": "vip", "audience": "targeted", "body": {"type": "spacer"}}""")
        val client = testClient(temp.newFolder(), "r1", mapOf(targeted))
        val closed = popups(client)
        arm(closed)
        assertNull(closed.current.value)
        val listed = popups(client, options(eligible = setOf("vip")))
        arm(listed)
        assertEquals("vip", listed.current.value?.id)
    }

    @Test
    fun `a popup is armed with the release's custom component types, and not without them`() {
        secondLaunch()
        val custom = popup("custom", body = """{"type": "room", "id": "r1"}""")
        val client = testClient(temp.newFolder(), "r1", mapOf(custom, popup("plain")))
        val without = popups(client)
        arm(without)
        assertEquals("plain", without.current.value?.id)

        val withRoom = popups(client, options(components = mapOf("room" to { })))
        arm(withRoom)
        assertEquals("custom", withRoom.current.value?.id) // customTypes = components.keys; same priority sorts by id
    }

    @Test
    fun `a built-in popup shows with no release, from the app's own files`() {
        secondLaunch()
        val p = popups(testClient(temp.newFolder()), options(embedded = true))
        arm(p)
        // priority 9 "shared" beats 1 "built-in"; the broken one was skipped at load.
        assertEquals("shared", p.current.value?.id)
        val ids = p.candidates(now)
        assertEquals(listOf("shared", "built-in"), ids.map { it.id })
        assertTrue("a built-in popup's animation is a real file", p.files(ids.last()).file("lottie/spin.json")!!.exists())
    }

    @Test
    fun `Ship's popup wins an id it shares with a built-in one`() {
        secondLaunch()
        val client = testClient(
            temp.newFolder(), "r1",
            mapOf(popup("shared", priority = 5, body = """{"type": "text", "fallback": "from ship"}""")),
        )
        val p = popups(client, options(embedded = true))
        arm(p)
        val armed = p.current.value!!
        assertEquals("shared", armed.id)
        assertEquals("the one from the release, though the built-in has a higher priority", 5, armed.priority)
        assertEquals((armed.body as Sdui.Node.Text).value.fallback, "from ship")
        assertEquals("its files are the release's", "r1", p.files(armed).releaseId)
    }

    @Test
    fun `revertHidesShipButKeepsEmbedded`() {
        secondLaunch()
        val client = testClient(
            temp.newFolder(), "revert-1",
            mapOf(popup("from-ship", priority = 99), popup("shared", priority = 98)),
            revert = true,
        )
        val p = popups(client, options(embedded = true))
        assertEquals(
            "Ship's popups are gone, the app's own remain, shared id included",
            listOf("shared", "built-in"), p.candidates(now).map { it.id },
        )
        assertEquals("embedded", p.files(p.candidates(now).first()).releaseId)
        val without = popups(client)
        assertEquals(emptyList<Sdui.Popup>(), without.candidates(now))
    }

    @Test
    fun `a rejecting preflight skips the popup without spending it, and the next one is tried`() {
        secondLaunch()
        val asked = mutableListOf<String>()
        val client = testClient(temp.newFolder(), "r1", mapOf(popup("first", 5), popup("second", 1)))
        var ok = false
        val p = popups(client, options(preflight = { asked += it.id; ok || it.id == "second" }))
        arm(p)
        assertEquals("the rejected popup does not block the next", "second", p.current.value?.id)
        assertEquals(listOf("first", "second"), asked)
        assertEquals("nothing is marked shown until it is on screen", null, app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getStringSet("popups_shown", null))

        p.close()
        ok = true
        asked.clear()
        // Not marked shown, so the next check offers "first" again (second was never shown either).
        arm(p)
        assertEquals("first", p.current.value?.id)
    }

    @Test
    fun `the popup is not presented until the preflight answers`() {
        secondLaunch()
        val answer = CompletableDeferred<Boolean>()
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("slow"))), options(preflight = { answer.await() }))
        val job = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(scope.testScheduler)).launch { p.arm(now) }
        scope.testScheduler.advanceUntilIdle()
        assertNull("still waiting for the app", p.current.value)
        answer.complete(true)
        scope.testScheduler.advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals("slow", p.current.value?.id)
    }

    @Test
    fun `onPopupClosed runs from close only when a popup was showing`() {
        secondLaunch()
        val closed = mutableListOf<String>()
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("a"))), options(closed = { closed += it.id }))
        p.close()
        assertEquals("nothing was showing", emptyList<String>(), closed)
        arm(p)
        p.close()
        p.close()
        assertEquals(listOf("a"), closed)
        assertNull(p.current.value)
    }

    @Test
    fun `a closed gate holds the popup back, and gateChanged arms it once the gate opens`() {
        secondLaunch()
        var open = false
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("a"))), options(gate = { open }))
        arm(p)
        assertNull("gate closed: nothing armed", p.current.value)
        assertFalse(p.gateOpen.value)

        open = true
        p.gateChanged()
        assertTrue("re-read at once, so an armed popup shows without waiting", p.gateOpen.value)
        assertNull("armed after the delay, not before", p.current.value)
        scope.testScheduler.advanceTimeBy(1_499)
        scope.testScheduler.runCurrent()
        assertNull(p.current.value)
        scope.testScheduler.advanceTimeBy(2)
        scope.testScheduler.advanceUntilIdle()
        assertEquals("a", p.current.value?.id)

        open = false
        p.gateChanged()
        assertFalse("an armed popup is hidden while the gate is closed", p.gateOpen.value)
        assertEquals("but still armed, not lost", "a", p.current.value?.id)
    }

    @Test
    fun `the host draws the armed popup only while the gate is open, and counts it shown`() {
        secondLaunch()
        var open = true
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("a"))), options(gate = { open }))
        arm(p)
        open = false
        p.gateChanged()
        compose.setContent { MaterialTheme { ShipPopupHost(p) } }
        compose.onAllNodesWithText("Hello a").assertCountEquals(0)
        open = true
        p.gateChanged()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Hello a").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hello a").assertIsDisplayed()
        compose.waitUntil(5_000) {
            app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getStringSet("popups_shown", null).orEmpty() == setOf("a")
        }
        assertEquals(listOf("popup_shown"), sink.names)
    }

    @Test
    fun `shown on screen counts as seen, and close, scrim and buttons all close it`() {
        secondLaunch()
        val body = """{"type": "vstack", "children": [
             {"type": "text", "fallback": "Diwali is here"},
             {"type": "button", "label": {"fallback": "See rewards"}, "action": {"type": "deeplink", "url": "shipsample://rewards/daily"}}
           ]}"""
        val opened = mutableListOf<android.net.Uri>()
        val closed = mutableListOf<String>()
        val p = popups(
            testClient(temp.newFolder(), "r1", mapOf(popup("hi", body = body))),
            options(closed = { closed += it.id }, open = { opened += it }),
        )
        compose.setContent { MaterialTheme { ShipPopupHost(p) } }
        fun arm() { arm(p); compose.waitForIdle() }

        arm()
        compose.onNodeWithText("Diwali is here").assertIsDisplayed()
        compose.waitUntil(5_000) {
            app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE).getStringSet("popups_shown", null).orEmpty() == setOf("hi")
        }

        compose.onNodeWithText("See rewards").performClick()
        assertNull("a button closes the popup before acting", p.current.value)
        assertEquals(listOf("shipsample://rewards/daily"), opened.map { it.toString() })
        assertEquals(listOf("hi"), closed)
    }

    @Test
    fun `a tap on the card is not a tap outside, the close button and the scrim close it`() {
        secondLaunch()
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("hi"), popup("again", priority = -1))))
        compose.setContent { MaterialTheme { ShipPopupHost(p) } }
        arm(p)
        compose.waitForIdle()
        compose.onNodeWithText("Hello hi").performClick()
        assertEquals("a tap on the card is not a tap outside", "hi", p.current.value?.id)

        compose.onNodeWithContentDescription("Close").performClick()
        assertNull(p.current.value)

        // The next one in the same session was not offered: one popup per session.
        arm(p)
        assertNull(p.current.value)
    }

    @Test
    fun `a tap outside the card closes it`() {
        secondLaunch()
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("hi"))))
        compose.setContent { MaterialTheme { ShipPopupHost(p) } }
        arm(p)
        compose.waitForIdle()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(5f, 5f)) }
        assertNull(p.current.value)
        assertEquals(listOf("popup_shown", "popup_action"), sink.names)
        assertEquals("close", sink.events.last().second["action"])
    }

    @Test
    fun `a custom component that calls the hook closes the popup and logs a component action`() {
        secondLaunch()
        val opened = mutableListOf<String>()
        val tile: @androidx.compose.runtime.Composable (Sdui.Node.Custom) -> Unit = { node ->
            val willAct = LocalShipComponentWillAct.current
            androidx.compose.material3.Text(
                "Open room",
                androidx.compose.ui.Modifier.clickable {
                    willAct?.invoke()
                    opened += node.fields.getValue("room").toString()
                },
            )
        }
        val closed = mutableListOf<String>()
        val p = popups(
            testClient(temp.newFolder(), "r1", mapOf(popup("hi", body = """{"type": "tile", "room": "r1"}"""))),
            options(components = mapOf("tile" to tile), closed = { closed += it.id }),
        )
        compose.setContent { MaterialTheme { ShipPopupHost(p) } }
        arm(p)
        compose.waitForIdle()
        compose.onNodeWithText("Open room").performClick()
        assertNull("the hook closed the popup", p.current.value)
        assertEquals(listOf("hi"), closed)
        assertEquals(listOf("\"r1\""), opened)
        val actions = sink.events.filter { it.first == "popup_action" }
        assertEquals(1, actions.size)
        assertEquals("component", actions.single().second["action"])
        assertEquals("hi", actions.single().second["definition_id"])
    }

    @Test
    fun `a popup that passes preflight but loses the race is released through onPopupClosed`() {
        secondLaunch()
        val closed = mutableListOf<String>()
        val p = popups(
            testClient(temp.newFolder(), "r1", mapOf(popup("a"))),
            options(
                // Another popup was shown this session while the preflight ran.
                preflight = { ShipPopups.Process.shownThisSession = true; true },
                closed = { closed += it.id },
            ),
        )
        arm(p)
        assertNull("not armed", p.current.value)
        assertEquals("what the preflight loaded is released", listOf("a"), closed)
    }

    @Test
    fun `resetShownForDebug forgets the shown popups and the launch count`() {
        secondLaunch()
        val prefs = app.getSharedPreferences("zynclave_sdui", Context.MODE_PRIVATE)
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(popup("a"))))
        arm(p)
        p.markShown(p.current.value!!)
        p.close()
        arm(p)
        assertNull("spent, and one per session", p.current.value)

        p.resetShownForDebug()
        assertNull(prefs.getStringSet("popups_shown", null))
        assertEquals("the next cold start is a first launch again", 0, prefs.getInt("popups_launches", 0))
        arm(p)
        assertEquals("shown again in this session", "a", p.current.value?.id)
    }

    @Test
    fun `no close button when the popup says so`() {
        secondLaunch()
        val file = "sdui/popups/x.json" to text("""{"schema": 1, "id": "x", "audience": "all", "closeButton": false, "body": {"type": "spacer"}}""")
        val p = popups(testClient(temp.newFolder(), "r1", mapOf(file)))
        compose.setContent { MaterialTheme { ShipPopupHost(p) } }
        arm(p)
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Close").assertCountEquals(0)
    }
}
