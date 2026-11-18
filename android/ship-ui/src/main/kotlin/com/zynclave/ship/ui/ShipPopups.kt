package com.zynclave.ship.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zynclave.ship.ShipClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-driven popups: a Ship release's `sdui/popups/<id>.json`, plus the
 * app's own embedded ones, shown once as a modal over the app.
 *
 * When one may appear (all must hold):
 *  - not the first launch after install (an app update is not a first launch)
 *  - not shown before on this device (once per popup id, ever)
 *  - at most one popup per app session
 *  - `options.popupGate()` allows it (the app says "no" during a call, a
 *    live room, a lock screen, or while signed out)
 *  - `options.popupPreflight` accepts it
 *
 * Create it once per launch, call [armSoon] after each Ship sync, and place
 * [ShipPopupHost] over the app. The host also re-checks each time the app
 * comes to the foreground, so embedded popups show without any sync.
 */
public class ShipPopups internal constructor(
    context: Context,
    private val client: ShipClient,
    private val options: ShipUiOptions,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val armDelayMs: Long,
    private val installWasUpdated: (Context) -> Boolean,
) {
    public constructor(context: Context, client: ShipClient, options: ShipUiOptions) : this(
        context, client, options,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        io = Dispatchers.IO,
        armDelayMs = ARM_DELAY_MS,
        installWasUpdated = ::installWasUpdated,
    )

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val armed = MutableStateFlow<Sdui.Popup?>(null)
    private val gate = MutableStateFlow(options.popupGate())

    /** The popup waiting for a moment to be drawn; [ShipPopupHost] draws it while the gate is open. */
    public val current: StateFlow<Sdui.Popup?> = armed.asStateFlow()

    /** `options.popupGate()` as last read: on [gateChanged] and each time a popup is armed. */
    internal val gateOpen: StateFlow<Boolean> = gate.asStateFlow()

    private val preflighting = AtomicBoolean(false)

    // Process-wide ([Process]): recreating this object (an Activity restart)
    // must not count a launch or allow a second popup this session.

    private var shipCache: Pair<String?, List<Sdui.Popup>>? = null
    private var embedded: ShipEmbedded? = null
    private var embeddedLoaded = false

    internal val analytics get() = ShipAnalytics(options.analytics) { client.content.state?.releaseId }
    internal val runtime get() = ShipRuntime(options, client.content)

    /** Re-evaluated shortly from now, so the first frames belong to the app. Call it after every Ship sync. */
    public fun armSoon() {
        scope.launch {
            delay(armDelayMs)
            arm(Instant.now())
        }
    }

    /**
     * Dismisses the armed popup (the host's own close, e.g. on sign-out) and
     * reports it through `options.onPopupClosed`, on the calling thread. Does
     * nothing when no popup is showing. Safe from any thread.
     */
    public fun close() {
        val popup = armed.getAndUpdate { null } ?: return
        options.onPopupClosed(popup)
    }

    /**
     * Tell the popup host the gate may have changed (a call ended). Hides or
     * shows an already armed popup, and also calls [armSoon], so a popup that
     * was held back while the gate was closed is armed now.
     */
    public fun gateChanged() {
        gate.value = options.popupGate()
        armSoon()
    }

    /**
     * A popup is spent the moment it appears, so the app's `popupPreflight`
     * runs FIRST. A candidate it rejects is skipped without being marked seen
     * (the next [armSoon] tries again) and does not block the ones after it.
     */
    internal suspend fun arm(now: Instant) {
        // Counted up front, before the gate: a process whose gate never opens
        // is still a launch.
        if (Process.firstLaunch == null) withContext(io) { isFirstLaunch() }
        gate.value = options.popupGate()
        if (armed.value != null || Process.shownThisSession || !gate.value) return
        if (!preflighting.compareAndSet(false, true)) return
        try {
            val list = withContext(io) { if (isFirstLaunch()) emptyList() else candidates(now) }
            for (candidate in list) {
                val accepted = try {
                    options.popupPreflight(candidate)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(LOG_TAG, "Popup ${candidate.id} preflight threw; treated as false", e)
                    false
                }
                if (!accepted) {
                    Log.i(LOG_TAG, "Popup ${candidate.id} held back: preflight failed")
                    continue
                }
                // Another arm, or a popup already shown this session, won while
                // the preflight ran: this one is not shown, so whatever the
                // preflight loaded for it is released now.
                if (Process.shownThisSession || !armed.compareAndSet(null, candidate)) {
                    options.onPopupClosed(candidate)
                }
                return
            }
        } finally {
            preflighting.set(false)
        }
    }

    /** Popups that may be shown now, best first (priority, then id). Reads from disk: call off the main thread. */
    internal fun candidates(now: Instant): List<Sdui.Popup> =
        Sdui.eligiblePopups(allPopups(), now, shown(), options.eligibility().popups)

    /** The popup is on screen: it counts as seen from here, whatever the person does next. */
    internal fun markShown(popup: Sdui.Popup) {
        synchronized(Process) {
            if (Process.shownThisSession) return
            Process.shownThisSession = true
            prefs.edit().putStringSet(SHOWN, shown() + popup.id).apply()
        }
        analytics.popupShown(popup.id)
    }

    /** Where a popup's files (Lottie, images) are: Ship's release, or the app's own folder for an embedded one. */
    internal fun files(popup: Sdui.Popup): ReleaseFiles {
        val own = embeddedContent()
        if (own != null && own.popups.any { it.id == popup.id } && shipPopups().none { it.id == popup.id }) return own.files
        return client.content.files()
    }

    private fun shown(): Set<String> = prefs.getStringSet(SHOWN, null).orEmpty().toSet()

    private fun isFirstLaunch(): Boolean = Process.firstLaunch ?: synchronized(Process) {
      Process.firstLaunch ?: run {
        val launches = prefs.getInt(LAUNCHES, 0) + 1
        prefs.edit().putInt(LAUNCHES, launches).apply()
        // Only a new install's first launch. An app that gained popups in an
        // update never counted launches before, so an update would otherwise
        // read as a first launch too and hold the popup back a whole session
        // from people who have used the app.
        (launches <= 1 && !installWasUpdated(app)).also { Process.firstLaunch = it }
      }
    }

    /** Ship's popups, then the app's own; Ship's win a shared id. */
    private fun allPopups(): List<Sdui.Popup> {
        val ship = shipPopups()
        val ids = ship.map { it.id }.toSet()
        return ship + embeddedContent()?.popups.orEmpty().filter { it.id !in ids }
    }

    @Synchronized
    private fun embeddedContent(): ShipEmbedded? {
        if (!embeddedLoaded) {
            embedded = options.embeddedPopupsAssetDir?.let {
                ShipEmbedded.load(app, it, options.deeplinkScheme, options.components.keys)
            }
            embeddedLoaded = true
        }
        return embedded
    }

    /** The applied release's popups, parsed once per release; invalid ones are logged and skipped. Empty after a revert. */
    @Synchronized
    private fun shipPopups(): List<Sdui.Popup> {
        val content = client.content
        val state = content.state ?: return emptyList()
        if (state.revertToEmbedded) return emptyList()
        shipCache?.takeIf { it.first == state.releaseId }?.let { return it.second }
        val assets = state.assets.toSet()
        val out = mutableListOf<Sdui.Popup>()
        // Sorted, so which file wins a duplicate id is deterministic.
        for (name in state.assets.sorted()) {
            if (!name.startsWith(Sdui.POPUPS_DIR) || !name.endsWith(".json")) continue
            val data = try {
                content.file(name)?.readText()
            } catch (_: java.io.IOException) {
                null
            } ?: continue
            try {
                val popup = Sdui.parsePopup(data, options.deeplinkScheme, options.components.keys, assets::contains)
                if (out.any { it.id == popup.id }) {
                    Log.w(LOG_TAG, "Popup $name skipped: duplicate id ${popup.id}")
                    continue
                }
                out += popup
            } catch (e: Sdui.Invalid) {
                Log.w(LOG_TAG, "Popup $name skipped: ${e.reason}")
            }
        }
        shipCache = state.releaseId to out
        return out
    }

    /**
     * DEBUG ONLY - never call it in a release build's normal flow. Forgets
     * which popups this device has shown and how many launches it has
     * counted, and lets this session show a popup again, so a tester can see
     * a popup they already dismissed: call [armSoon] next. The next cold start
     * counts as the first launch after install again (no popup on it).
     */
    public fun resetShownForDebug() {
        synchronized(Process) {
            prefs.edit().remove(SHOWN).remove(LAUNCHES).commit()
            Process.shownThisSession = false
        }
    }

    /** State that lives as long as the process: a new [ShipPopups] (an Activity restart) shares it. */
    internal object Process {
        @Volatile var shownThisSession = false

        // Counted once per process - a process started for background work alone is not a launch.
        @Volatile var firstLaunch: Boolean? = null

        /** Tests only: a fresh process. */
        fun reset() {
            shownThisSession = false
            firstLaunch = null
        }
    }

    internal companion object {
        const val ARM_DELAY_MS = 1_500L
        private const val PREFS = "zynclave_sdui"
        private const val SHOWN = "popups_shown"
        private const val LAUNCHES = "popups_launches"

        /** Whether this install has been updated since it was installed. */
        fun installWasUpdated(context: Context): Boolean = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime > info.firstInstallTime
        }.getOrDefault(false)
    }
}

/**
 * Draws the armed popup over the app while `options.popupGate()` allows it.
 * Place it after the app's content and before anything that must cover it (a
 * lock screen, a privacy shield). Arms a popup a moment after it enters the
 * composition and each time the app comes to the foreground.
 */
@Composable
public fun ShipPopupHost(popups: ShipPopups) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(popups, lifecycle) {
        // Replays the current state on registration, so this also arms once at first composition.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) popups.armSoon() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val current by popups.current.collectAsState()
    val open by popups.gateOpen.collectAsState()
    val popup = current?.takeIf { open } ?: return
    androidx.compose.runtime.key(popup.id) { ShipPopupView(popup, popups) }
}

/** The popup itself: a scrim (tap outside to close), a card holding the tree, and a close button. */
@Composable
internal fun ShipPopupView(popup: Sdui.Popup, popups: ShipPopups) {
    // Which files the popup draws from reads the disk (an embedded popup's
    // folder is unpacked on first use): worked out off the main thread, and
    // nothing in the popup that needs a file draws until it is known.
    val files by produceState<ReleaseFiles?>(null, popup.id, popups) {
        value = withContext(Dispatchers.IO) { popups.files(popup) }
    }
    val runtime = remember(popup.id, popups, files) {
        ShipRuntime(popups.runtime.options, popups.runtime.content, files ?: ReleaseFiles(null) { null })
    }
    LaunchedEffect(popup.id) {
        withContext(Dispatchers.IO) { popups.markShown(popup) }
    }
    // Counted, then closed: a button or tile ("component"), or the close button / a tap outside ("close").
    val closeTapped = remember(popup.id, popups) {
        { popups.analytics.popupAction(popup.id, ShipAnalytics.PopupAction.Close); popups.close() }
    }
    val componentTapped = remember(popup.id, popups) {
        { popups.analytics.popupAction(popup.id, ShipAnalytics.PopupAction.Component); popups.close() }
    }
    val shape = RoundedCornerShape(24.dp)
    CompositionLocalProvider(LocalShipRuntime provides runtime) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The scrim is a sibling BEHIND the card, not its parent: hit testing
            // stops at the topmost sibling that handles the pointer, so a tap on
            // the card (which handles it, below) never reaches the scrim.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = closeTapped),
            )
            Box(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .clip(shape)
                    .background(tokenColor("surface"))
                    // Takes taps on the card itself, so they are not a tap outside. The
                    // scroll container below happens to as well; this says so on purpose.
                    // A plain pointer handler, not clickable: that would merge the card's
                    // contents into one "button" for TalkBack that does nothing.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                // A button closes the popup first, so what it opens is not under it.
                CompositionLocalProvider(LocalShipComponentWillAct provides componentTapped) {
                    Box(Modifier.verticalScroll(rememberScrollState())) {
                        SduiNode(popup.body, onDismiss = popups::close)
                    }
                }
                if (popup.closeButton) {
                    IconButton(onClick = closeTapped, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = runtime.options.appString("common_close") ?: "Close",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
