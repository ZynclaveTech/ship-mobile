package com.zynclave.ship.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Analytics for server-driven feed cards and popups: the same event names
 * and parameters on every platform, each carrying the definition id and the
 * Ship release id it came from. Events go to the app's ShipAnalyticsSink;
 * whether to collect them at all (consent) is the app's call there.
 */
internal class ShipAnalytics(private val sink: ShipAnalyticsSink?, private val releaseId: () -> String?) {
    enum class TapTarget(val value: String) {
        /** The card itself (its `tap` action). */
        Card("card"),

        /** A button or app tile inside the card. */
        Component("component"),
    }

    enum class PopupAction(val value: String) {
        /** A button or app tile inside the popup. */
        Component("component"),

        /** Its close button, or a tap outside it. */
        Close("close"),
    }

    fun impression(id: String) = log("sdui_impression", id)

    fun tap(id: String, target: TapTarget) = log("sdui_tap", id, "target" to target.value)

    fun dismiss(id: String) = log("sdui_dismiss", id)

    fun popupShown(id: String) = log("popup_shown", id)

    fun popupAction(id: String, action: PopupAction) = log("popup_action", id, "action" to action.value)

    // Definitions exist only inside a release, so "none" means a bug, not a real value.
    private fun log(name: String, id: String, vararg extra: Pair<String, String>) {
        sink?.log(name, mapOf("definition_id" to id, "release_id" to (releaseId() ?: "none"), *extra))
    }
}

/**
 * When a feed card counts as seen: at least half of it on screen for a full
 * second, once per feed load. Pure, so the rule is testable without a feed;
 * the feed feeds it visible fractions and a clock.
 */
internal class SduiImpressionTracker {
    // When each card currently at least half visible became so.
    private val visibleSince = HashMap<String, Long>()

    // Cards already counted in this feed load.
    private val counted = HashSet<String>()

    /**
     * Feed it every card on screen now (id -> visible fraction); returns the
     * ids that just qualified, in id order. A card missing from [fractions] is
     * off screen, and its second starts over next time.
     */
    fun update(fractions: Map<String, Float>, now: Long): List<String> {
        visibleSince.keys.retainAll { (fractions[it] ?: 0f) >= MIN_FRACTION }
        val qualified = mutableListOf<String>()
        for ((id, fraction) in fractions) {
            if (fraction < MIN_FRACTION || id in counted) continue
            val since = visibleSince.getOrPut(id) { now }
            if (now - since >= MIN_DURATION_MS) qualified += id
        }
        qualified.forEach {
            counted += it
            visibleSince -= it
        }
        return qualified.sorted()
    }

    /** When the earliest pending card would qualify if it stays put; a card that sits still sends no layout events. */
    fun nextDeadline(): Long? = visibleSince.values.minOrNull()?.plus(MIN_DURATION_MS)

    companion object {
        const val MIN_FRACTION = 0.5f
        const val MIN_DURATION_MS = 1_000L
    }
}

/**
 * How much of a card is visible: its window extent [top]..[bottom] (already
 * clipped by the list) within [viewport], over its full [height]. The
 * viewport is the part of the list nothing sits over - a floating header
 * and a bottom bar draw over the list's content padding.
 */
internal fun visibleFraction(top: Float, bottom: Float, height: Float, viewport: Pair<Float, Float>? = null): Float {
    if (height <= 0f) return 0f
    val visibleTop = maxOf(top, viewport?.first ?: top)
    val visibleBottom = minOf(bottom, viewport?.second ?: bottom)
    return ((visibleBottom - visibleTop).coerceAtLeast(0f) / height).coerceIn(0f, 1f)
}

/**
 * One feed load's impressions: cards report where they are on screen, and
 * each that qualifies is logged once. [deadline] is when to look again
 * without a report (the feed waits for it and calls [tick]; see
 * [ShipImpressionsEffect]). Main thread.
 */
internal class SduiImpressions(
    private val clock: () -> Long,
    private val onImpression: (String) -> Unit,
) {
    private class Extent(val top: Float, val bottom: Float, val height: Float)

    private var tracker = SduiImpressionTracker()
    private val extents = HashMap<String, Extent>()
    private val nextCheck = MutableStateFlow<Long?>(null)

    /** The wall-clock time (ms) to call [tick] at, or null when nothing is pending. */
    val deadline: StateFlow<Long?> = nextCheck.asStateFlow()

    /**
     * The window range (top, bottom px) nothing covers. Set by the feed; a
     * change re-evaluates every card, since a card that sits still reports
     * nothing new.
     */
    var viewport: Pair<Float, Float>? = null
        set(value) {
            if (field == value) return
            field = value
            tick()
        }

    /** [id] spans [top]..[bottom] in the window (clipped by the list) and is [height] tall. */
    internal fun report(id: String, top: Float, bottom: Float, height: Float) {
        if (height <= 0f || bottom <= top) extents -= id else extents[id] = Extent(top, bottom, height)
        tick()
    }

    /** [id] left the feed. */
    internal fun gone(id: String) {
        extents -= id
        tick()
    }

    internal fun tick() {
        val fractions = extents
            .mapValues { (_, e) -> visibleFraction(e.top, e.bottom, e.height, viewport) }
            .filterValues { it > 0f }
        tracker.update(fractions, clock()).forEach(onImpression)
        nextCheck.value = tracker.nextDeadline()
    }

    /** A new feed load: every card may count again. */
    internal fun reset() {
        tracker = SduiImpressionTracker()
        tick()
    }
}

/**
 * Which feed load this is: a new one each time a refresh finishes. A post
 * removed, hidden or blocked in place changes the list but is not a load,
 * so impressions already counted stay counted. Safe to call on every
 * recomposition: only the refreshing -> done edge moves it.
 */
internal class SduiFeedLoads {
    private var refreshing = false
    private var generation = 0

    fun observe(isRefreshing: Boolean): Int {
        if (refreshing && !isRefreshing) generation++
        refreshing = isRefreshing
        return generation
    }
}

/** The feed load's impression tracking; null outside a feed (popups count as shown instead). */
internal val LocalSduiImpressions = staticCompositionLocalOf<SduiImpressions?> { null }
