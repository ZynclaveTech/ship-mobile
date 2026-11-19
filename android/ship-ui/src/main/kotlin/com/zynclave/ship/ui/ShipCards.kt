package com.zynclave.ship.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zynclave.ship.ShipClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

internal const val LOG_TAG = "com.zynclave.ship"

/**
 * The server-driven cards of the applied Ship release, for the host's feed.
 * The host decides where a card goes (the card's `position` counts posts, not
 * rows; see [Sdui.placement]) and draws each with [ShipCard].
 *
 * Create it once per launch. Reading cards touches the disk: call [cards]
 * off the main thread, or use [rememberShipCards], which does.
 */
public class ShipCards internal constructor(
    context: Context,
    private val client: ShipClient,
    private val options: ShipUiOptions,
    clock: () -> Long,
) {
    public constructor(context: Context, client: ShipClient, options: ShipUiOptions) :
        this(context, client, options, System::currentTimeMillis)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val changed = MutableStateFlow(0)
    private var parsed: Pair<String, List<Sdui.Card>>? = null

    /** The dismissed set this instance last acted on, to tell a change made through another instance. */
    @Volatile private var lastDismissed: Set<String> = emptySet()

    // Held strongly: SharedPreferences keeps its listeners weakly.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != DISMISSED && key != null) return@OnSharedPreferenceChangeListener
        val now = dismissed()
        val moved = synchronized(this) { (now != lastDismissed).also { if (it) lastDismissed = now } }
        if (moved) changed.value++
    }

    init {
        lastDismissed = dismissed()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /** Moves each time what [cards] returns may have changed: a card dismissed, or [refresh] called. */
    public val changes: StateFlow<Int> = changed.asStateFlow()

    internal val analytics get() = ShipAnalytics(options.analytics) { client.content.state?.releaseId }
    internal val runtime get() = ShipRuntime(options, client.content)

    /** Impression tracking for the cards on screen; see [ShipImpressionsEffect]. */
    internal val impressions: SduiImpressions = SduiImpressions(clock) { analytics.impression(it) }

    private val feedLoads = SduiFeedLoads()

    /**
     * The window range (top, bottom, in px) of the list that nothing covers:
     * the list's bounds minus a floating header or bottom bar that draws over
     * it. A card counts as seen only for the part of it inside this range.
     * Set it from the feed's host (e.g. from `onGloballyPositioned`); setting a
     * new value re-evaluates every card on screen, even one that sits still.
     * Null (the default) uses the list's clipped bounds alone. Main thread.
     */
    public var viewport: Pair<Float, Float>?
        get() = impressions.viewport
        set(value) {
            impressions.viewport = value
        }

    /**
     * The cards to show on [tab] (`discover`, `following`): valid, in
     * schedule, not dismissed, targeted ones only if `options.eligibility`
     * names them, best first, at most [Sdui.MAX_CARDS_PER_FEED]. Empty after a
     * revert to the embedded content.
     */
    public fun cards(tab: String, now: Instant = Instant.now()): List<Sdui.Card> =
        Sdui.cards(all(), tab, now, dismissed(), eligible = options.eligibility().cards)

    /** When [cards] for [tab] changes on the clock alone (a card starts or ends), or null. */
    internal fun nextChange(tab: String, now: Instant = Instant.now()): Instant? = Sdui.nextChange(all(), tab, now)

    /**
     * Dismisses a card for good, and reports it to analytics once however
     * many times it is tapped.
     */
    public fun dismiss(id: String) {
        synchronized(this) {
            val current = dismissed()
            if (id in current) return
            lastDismissed = current + id
            prefs.edit().putStringSet(DISMISSED, current + id).apply()
        }
        analytics.dismiss(id)
        changed.value++
    }

    /** Call after a Ship sync applied a release, or when `options.eligibility` changed: [changes] moves, so a feed recomputes. */
    public fun refresh() {
        changed.value++
    }

    /**
     * Tell it whether the feed is refreshing, on every recomposition. Each
     * time a refresh finishes is a new feed load, and every card may be
     * counted as seen again; a post removed in place is not one.
     */
    public fun observeRefresh(isRefreshing: Boolean) {
        val generation = feedLoads.observe(isRefreshing)
        if (generation != lastGeneration) {
            lastGeneration = generation
            impressions.reset()
        }
    }

    private var lastGeneration = 0

    private fun dismissed(): Set<String> = prefs.getStringSet(DISMISSED, null).orEmpty().toSet()

    /** Parsed once per release. Invalid cards are logged and skipped. */
    internal fun all(): List<Sdui.Card> {
        val content = client.content
        val state = content.state ?: return emptyList()
        if (state.revertToEmbedded) return emptyList()
        synchronized(this) {
            parsed?.takeIf { it.first == state.releaseId }?.let { return it.second }
            val assets = state.assets.toSet()
            val types = options.components.keys
            val out = mutableListOf<Sdui.Card>()
            // Sorted, so which file wins a duplicate id is deterministic.
            for (name in state.assets.sorted()) {
                if (!name.startsWith(Sdui.CARDS_DIR) || !name.endsWith(".json")) continue
                val data = try {
                    content.file(name)?.readText()
                } catch (_: java.io.IOException) {
                    null
                } ?: continue
                try {
                    val card = Sdui.parseCard(data, options.deeplinkScheme, types, assets::contains)
                    if (out.any { it.id == card.id }) {
                        Log.w(LOG_TAG, "Card $name skipped: duplicate id ${card.id}")
                        continue
                    }
                    out += card
                } catch (e: Sdui.Invalid) {
                    Log.w(LOG_TAG, "Card $name skipped: ${e.reason}")
                }
            }
            parsed = state.releaseId to out
            return out
        }
    }

    private companion object {
        const val PREFS = "zynclave_sdui"
        const val DISMISSED = "dismissed"
    }
}

/**
 * The cards a feed tab ([tab] "discover" or "following"; null for any other
 * list) shows now. Recomputed when [ShipCards.changes] moves (a card
 * dismissed, [ShipCards.refresh]) and at the moment a card on this tab starts
 * or ends - time moves on without a new release. The release's card files
 * are read off the main thread; until they are known, no cards.
 */
@Composable
public fun rememberShipCards(cards: ShipCards, tab: String?): List<Sdui.Card> {
    if (tab == null) return emptyList()
    val changes by cards.changes.collectAsState()
    var clock by remember { mutableIntStateOf(0) }
    val shown by produceState(emptyList<Sdui.Card>(), cards, tab, changes, clock) {
        value = withContext(Dispatchers.IO) { cards.cards(tab) }
    }
    val wake by produceState<Instant?>(null, cards, tab, changes, clock) {
        value = withContext(Dispatchers.IO) { cards.nextChange(tab) }
    }
    LaunchedEffect(wake) {
        waitUntil(wake ?: return@LaunchedEffect)
        clock++
    }
    return shown
}

/**
 * Suspends until [clock] reads at least [instant]. One delay is not enough:
 * delay runs on the monotonic clock while [instant] is wall-clock time, and
 * millisecond rounding can wake it just short - and the caller's effect is
 * keyed on [instant], so an early wake would never be retried. Each wait is
 * rounded up, and it waits again until the clock is really there.
 */
internal suspend fun waitUntil(instant: Instant, clock: () -> Instant = Instant::now) {
    while (true) {
        val left = Duration.between(clock(), instant)
        if (left.isNegative || left.isZero) return
        delay(left.toMillis() + 1)
    }
}

/** Counts the cards in the feed as seen once each has been half on screen for a second. Place it beside the feed. */
@Composable
public fun ShipImpressionsEffect(cards: ShipCards) {
    val deadline by cards.impressions.deadline.collectAsState()
    LaunchedEffect(deadline) {
        val at = deadline ?: return@LaunchedEffect
        while (System.currentTimeMillis() < at) delay(at - System.currentTimeMillis() + 1)
        cards.impressions.tick()
    }
}

/** A server-driven card: its body, a tap action and a close button. */
@Composable
public fun ShipCard(card: Sdui.Card, cards: ShipCards, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runtime = remember(cards) { cards.runtime }
    val dismiss = remember(card.id, cards) { { cards.dismiss(card.id) } }
    // Where the card is on screen, for impressions: its window bounds clipped by
    // the list, and its height; the feed's host measures that against the part
    // of the list nothing covers.
    val impressions = cards.impressions
    DisposableEffect(card.id, impressions) { onDispose { impressions.gone(card.id) } }
    CompositionLocalProvider(LocalShipRuntime provides runtime) {
        Box(
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInWindow()
                    impressions.report(card.id, bounds.top, bounds.bottom, coords.size.height.toFloat())
                },
        ) {
            val tap = card.tap
            Box(
                if (tap != null) {
                    Modifier.clickable {
                        cards.analytics.tap(card.id, ShipAnalytics.TapTarget.Card)
                        SduiActions.perform(context, runtime.options, tap, dismiss)
                    }
                } else {
                    Modifier
                },
            ) {
                // A button or app tile inside the card counts as a component tap first.
                CompositionLocalProvider(
                    LocalShipComponentWillAct provides { cards.analytics.tap(card.id, ShipAnalytics.TapTarget.Component) },
                ) {
                    SduiNode(card.body, dismiss)
                }
            }
            if (card.dismissible) {
                IconButton(onClick = dismiss, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = runtime.options.appString("common_close") ?: "Close",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}
