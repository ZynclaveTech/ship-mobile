package com.zynclave.shipsample

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zynclave.ship.PinnedKey
import com.zynclave.ship.ShipClient
import com.zynclave.ship.ShipConfig
import com.zynclave.ship.ShipContent
import com.zynclave.ship.SyncResult
import com.zynclave.ship.ui.ShipCards
import com.zynclave.ship.ui.ShipPopups
import com.zynclave.ship.ui.ShipUiOptions
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File

/** The release's public key. Replace it with the key your Ship app gives you. */
const val SAMPLE_PUBLIC_KEY = "2iX4H1QZbH2lPtz8hvFK1bCXWHXAUV8N0mGvR+Hn52c="

/** Created once per process, so Ship's popup state is never duplicated. */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ship = Ship(this)
    }

    companion object {
        lateinit var ship: Ship
            private set
    }
}

/** The screens, switched by [Ship.screen]. */
enum class Screen { Home, Details, Debug }

/**
 * Owns the Ship client and the UI helpers, and syncs on request. Observable
 * fields are Compose state, so screens update by themselves.
 */
class Ship(context: Context) {
    val client: ShipClient?
    val cards: ShipCards?
    val popups: ShipPopups?
    private val options: ShipUiOptions?

    val channel = if (BuildConfig.DEBUG) "testflight" else "production"
    val runtime = "android-" + BuildConfig.VERSION_NAME
    val isConfigured: Boolean get() = client != null

    /** Where the app is. Set by buttons and by `shipsample://details` links. */
    var screen by mutableStateOf(Screen.Home)

    /** Moves after each sync, so views re-read the applied content. */
    var revision by mutableIntStateOf(0)
        private set
    var status by mutableStateOf("")
        private set

    /** True while a sync runs, so "Check now" can be disabled. */
    var syncing by mutableStateOf(false)
        private set

    val content: ShipContent? get() = client?.content

    /** The language Ship uses for text, so the greeting matches popups. */
    val language: String get() = options?.language?.invoke() ?: "en"

    init {
        val key = when {
            SAMPLE_PUBLIC_KEY == "REPLACE_WITH_SHIP_SAMPLE_PUBLIC_KEY" -> null.also {
                status = "Ship is not configured: see README"
            }
            else -> PinnedKey.of("ship-sample-1", SAMPLE_PUBLIC_KEY).also {
                if (it == null) status = "Ship public key is invalid: see README"
            }
        }
        if (key == null) {
            client = null; cards = null; popups = null; options = null
        } else {
            val config = ShipConfig(
                cdn = "https://ota.zynclave.com/".toHttpUrl(),
                app = "ship-sample",
                channel = channel,
                runtime = runtime,
                pinned = listOf(key),
                bucket = ShipConfig.stableBucket(context),
            )
            val client = ShipClient(config, File(context.filesDir, "ship"))
            val options = ShipUiOptions(
                deeplinkScheme = "shipsample",
                openDeeplink = ::open,
                // Popups bundled in the app show even before the first sync.
                embeddedPopupsAssetDir = "ship-embedded",
            )
            this.client = client
            this.options = options
            cards = ShipCards(context, client, options)
            popups = ShipPopups(context, client, options)
        }
    }

    /** Turns `shipsample://details` into navigation. Used by the intent filter and by Ship's `openDeeplink`. */
    fun open(uri: Uri) {
        if (uri.scheme == "shipsample" && uri.host == "details") screen = Screen.Details
    }

    /**
     * Fetches the latest release, then lets cards and popups re-check. A
     * second call while one is running does nothing; a cancelled sync leaves
     * no trace.
     */
    suspend fun sync() {
        val client = client ?: return
        if (syncing) return
        syncing = true
        try {
            status = when (client.sync()) {
                is SyncResult.Applied -> "Updated"
                is SyncResult.UpToDate -> "Up to date"
                SyncResult.NoUpdate -> "No update for this device"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "Sync failed: ${e.message ?: e::class.java.simpleName}"
        } finally {
            syncing = false
        }
        // ShipClient has no change signal, so tell the UI helpers ourselves.
        revision++
        cards?.refresh()
        popups?.armSoon()
    }

    /** Debug screen only: forget which popups were shown, then look again. */
    fun resetPopups() {
        val popups = popups ?: return
        popups.resetShownForDebug()
        popups.armSoon()
        status = "Popup memory reset"
    }
}
