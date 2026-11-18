package com.zynclave.ship.ui

import android.graphics.Bitmap
import com.zynclave.ship.AppliedState
import com.zynclave.ship.ShipClient
import com.zynclave.ship.ShipConfig
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

const val TEST_SCHEME = "shipsample"
const val TEST_APP = "sampleapp"

val testConfig = ShipConfig(
    cdn = "https://cdn.test/".toHttpUrl(), app = TEST_APP, channel = "prod", runtime = "android-1",
    pinned = emptyList(), bucket = 0,
)

/**
 * A [ShipClient] whose release is already applied: the files and state are
 * laid out on disk as the client writes them, so no CDN is involved.
 * [files] maps a release path to its bytes; [releaseId] null means nothing applied.
 */
fun testClient(
    root: File,
    releaseId: String? = null,
    files: Map<String, ByteArray> = emptyMap(),
    revert: Boolean = false,
): ShipClient {
    if (releaseId != null) {
        val dir = File(root, "releases/${UUID.randomUUID()}")
        files.forEach { (name, bytes) -> File(dir, name).apply { parentFile!!.mkdirs() }.writeBytes(bytes) }
        val state = AppliedState(
            app = testConfig.app, channel = testConfig.channel, runtime = testConfig.runtime,
            releaseId = releaseId, seq = 1, revertToEmbedded = revert, assets = files.keys.toList(),
        )
        File(dir, ".ship-state.json").writeText(Json.encodeToString(AppliedState.serializer(), state))
        File(root, "current").writeText(dir.name)
    }
    return ShipClient(testConfig, root)
}

fun text(s: String) = s.toByteArray()

fun image(width: Int, height: Int, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream().also { bmp.compress(format, 90, it) }.toByteArray()
}

/** A release directory laid out on disk, as [ReleaseFiles] for the renderer. */
internal fun releaseFiles(dir: File, id: String, vararg files: Pair<String, ByteArray>): ReleaseFiles {
    files.forEach { (name, bytes) -> File(dir, name).apply { parentFile!!.mkdirs() }.writeBytes(bytes) }
    val names = files.map { it.first }.toSet()
    return ReleaseFiles(id) { if (it in names) File(dir, it) else null }
}

class RecordingSink : ShipAnalyticsSink {
    val events = mutableListOf<Pair<String, Map<String, String>>>()
    override fun log(name: String, params: Map<String, String>) {
        events += name to params
    }
    val names get() = events.map { it.first }
}
