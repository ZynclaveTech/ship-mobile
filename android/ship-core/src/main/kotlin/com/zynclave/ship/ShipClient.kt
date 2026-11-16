package com.zynclave.ship

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import android.content.Context
import kotlin.random.Random
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * Keeps one app/channel's OTA content in sync with the Ship CDN. Kotlin
 * counterpart of ios/Sources/ZynclaveShip/ShipClient.swift, with one deliberate
 * difference in how a release is applied (see ShipClient).
 */

/**
 * What a [ShipTransport] returns: the response [body] and its HTTP [status].
 * Public because a custom [ShipTransport] builds it; a failure status needs no body.
 */
public class ShipResponse(public val body: ByteArray, public val status: Int)

/** Fetches bytes over HTTP. Injected so tests run without the real CDN. */
public fun interface ShipTransport {
    /** Must throw [ShipException.TooLarge] instead of buffering more than [limit] bytes. */
    public suspend fun get(url: HttpUrl, limit: Long): ShipResponse
}

/** The default [ShipTransport]. Give it its own [OkHttpClient], never one that adds the app's auth headers. */
public class OkHttpShipTransport(private val client: OkHttpClient) : ShipTransport {
    override suspend fun get(url: HttpUrl, limit: Long): ShipResponse = runInterruptible(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            // Callers only need the status of a failure; an error page larger
            // than a small asset's limit must still surface as Http, not TooLarge.
            if (!resp.isSuccessful) return@runInterruptible ShipResponse(ByteArray(0), resp.code)
            val body = resp.body
            if (body.contentLength() > limit) throw ShipException.TooLarge()
            val source = body.source()
            val buf = Buffer()
            while (source.read(buf, 8192) != -1L) {
                if (buf.size > limit) throw ShipException.TooLarge()
            }
            ShipResponse(buf.readByteArray(), resp.code)
        }
    }
}

public data class ShipConfig(
    val cdn: HttpUrl,
    val app: String,
    val channel: String,
    /** What the installed binary can run, e.g. "android-2.225.6". A release reaches only builds with the same runtime. */
    val runtime: String,
    val pinned: List<PinnedKey>,
    /** Stable per-install value in 0..99 that decides partial rollouts. */
    val bucket: Int,
    /** Refuse releases whose assets add up to more than this. */
    val maxReleaseBytes: Long = 50L shl 20,
) {
    public companion object {
        private const val PREFS = "zynclave_ship"
        private const val BUCKET_KEY = "bucket"

        /**
         * A random bucket in 0..99, chosen once per install and kept in the
         * app's SharedPreferences (`zynclave_ship`, key `bucket`). Pass it as
         * [bucket]: it decides which devices a partial rollout reaches.
         */
        public fun stableBucket(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getInt(BUCKET_KEY, -1)
            if (stored in 0..99) return stored
            val bucket = Random.nextInt(100)
            prefs.edit().putInt(BUCKET_KEY, bucket).apply()
            return bucket
        }
    }
}

/** What is applied on this device. Stored inside the release directory it describes. */
@Serializable
public data class AppliedState(
    /** What this content was applied for. A different app, channel or runtime (an app update) means it no longer applies. */
    val app: String,
    val channel: String,
    val runtime: String,
    val releaseId: String,
    val seq: Long,
    val revertToEmbedded: Boolean,
    val assets: List<String>,
)

/** What [ShipClient.sync] did. */
public sealed interface SyncResult {
    /** The channel has nothing for this device; whatever is applied stays. */
    public data object NoUpdate : SyncResult

    /** The release this device already has. */
    public data class UpToDate(val seq: Long) : SyncResult

    /** A new release was verified, downloaded and applied. */
    public data class Applied(val state: AppliedState) : SyncResult
}

/** Read-only view of applied content, safe from any thread (string lookups run on the main thread). */
public class ShipContent internal constructor() {
    private class Snapshot(val state: AppliedState?, val dir: File?) {
        val assets: Set<String> = state?.assets?.toSet().orEmpty()
        val strings = ConcurrentHashMap<String, Map<String, String>>()
    }

    @Volatile private var snapshot = Snapshot(null, null)

    /** What is applied, or null when nothing is. */
    public val state: AppliedState? get() = snapshot.state

    /** The file of an applied asset, or null when no release (or a revert) is applied. */
    public fun file(asset: String): File? {
        val s = snapshot
        val dir = s.dir ?: return null
        return if (asset in s.assets) File(dir, asset) else null
    }

    /** The OTA value for [key] in `strings/<locale>.json` (a flat JSON object of strings), if any. */
    public fun string(key: String, locale: String): String? {
        val s = snapshot
        val table = s.strings.getOrPut(locale) {
            val file = if ("strings/$locale.json" in s.assets) s.dir?.let { File(it, "strings/$locale.json") } else null
            file?.let { f ->
                runCatching { ManifestVerifier.json.decodeFromString<Map<String, String>>(f.readText()) }.getOrNull()
            }.orEmpty()
        }
        return table[key]
    }

    internal fun load(dir: File?, state: AppliedState?) {
        snapshot = Snapshot(state, if (state == null) null else dir)
    }
}

/**
 * One release lives in `releases/<uuid>/` with its [AppliedState] beside the
 * assets; the one-line file `current` names the applied directory.
 *
 * iOS swaps the whole content directory in one rename. Linux `rename(2)`
 * can't replace a non-empty directory, so here the release is written in
 * full first and then `current` is replaced with an atomic file rename: a
 * crash leaves either the old pointer or the new one, never a mix.
 * Directories `current` doesn't name are deleted on the next start.
 *
 * @param root directory owned by this client (created if missing).
 */
public class ShipClient(
    private val config: ShipConfig,
    private val root: File,
    private val transport: ShipTransport = OkHttpShipTransport(OkHttpClient()),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The applied content. The same object for the client's lifetime; what it holds moves on with each sync. */
    public val content: ShipContent = ShipContent()
    private val mutex = Mutex()
    private val releases = File(root, "releases")
    private val pointer = File(root, "current")

    init {
        var (dir, state) = readCurrent()
        if (state != null && (state.app != config.app || state.channel != config.channel || state.runtime != config.runtime)) {
            pointer.delete()
            dir = null
            state = null
        }
        content.load(dir, state)
        deleteReleasesExcept(setOfNotNull(dir?.name))
    }

    /** What is applied, or null when nothing is. */
    public val applied: AppliedState? get() = content.state

    /**
     * Fetches the manifest for this device and applies it if it is new. On
     * any error the applied content is left exactly as it was. Calls run one
     * at a time.
     */
    public suspend fun sync(): SyncResult = mutex.withLock {
        withContext(io) { syncLocked() }
    }

    private suspend fun syncLocked(): SyncResult {
        val url = config.cdn.newBuilder()
            .addPathSegments("v1/manifest")
            .addQueryParameter("app", config.app)
            .addQueryParameter("platform", PLATFORM)
            .addQueryParameter("channel", config.channel)
            .addQueryParameter("runtime", config.runtime)
            .addQueryParameter("bucket", config.bucket.toString())
            .build()
        val resp = transport.get(url, MANIFEST_LIMIT)
        if (resp.status == 204) return SyncResult.NoUpdate
        if (resp.status != 200) throw ShipException.Http(resp.status)

        val have = content.state
        val m = ManifestVerifier.verify(
            resp.body, config.pinned,
            ShipExpectation(app = config.app, runtime = config.runtime, channel = config.channel, platform = PLATFORM,
                minSeq = have?.seq ?: 0),
        )
        if (have != null && m.seq == have.seq) return SyncResult.UpToDate(m.seq)
        if (m.assets.sumOf { it.size } > config.maxReleaseBytes) {
            throw ShipException.Malformed("release larger than ${config.maxReleaseBytes} bytes")
        }

        releases.mkdirs()
        val previous = currentDirName()
        val dir = File(releases, UUID.randomUUID().toString())
        val state = AppliedState(
            app = config.app, channel = config.channel, runtime = config.runtime, releaseId = m.releaseId,
            seq = m.seq, revertToEmbedded = m.revertToEmbedded, assets = m.assets.map { it.name },
        )
        try {
            for (a in m.assets) {
                val blob = config.cdn.newBuilder()
                    .addPathSegment("blobs").addPathSegment(config.app).addPathSegment(a.sha256).build()
                val r = transport.get(blob, a.size)
                if (r.status != 200) throw ShipException.Http(r.status)
                if (r.body.size.toLong() != a.size || sha256Hex(r.body) != a.sha256) {
                    throw ShipException.AssetMismatch(a.name)
                }
                writeDurably(File(dir, a.name), r.body)
            }
            writeDurably(File(dir, STATE_FILE), ManifestVerifier.json.encodeToString(AppliedState.serializer(), state).toByteArray())
            val tmp = File(root, "current.tmp")
            writeDurably(tmp, dir.name.toByteArray())
            Files.move(tmp.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
        content.load(dir, state)
        // Keep the release just replaced: another thread may be reading one of its files right now.
        deleteReleasesExcept(setOfNotNull(dir.name, previous))
        return SyncResult.Applied(state)
    }

    private fun currentDirName(): String? =
        runCatching { pointer.readText().trim() }.getOrNull()?.takeIf { it.matches(DIR_NAME) }

    private fun readCurrent(): Pair<File?, AppliedState?> {
        val name = currentDirName() ?: return null to null
        val dir = File(releases, name)
        val state = runCatching {
            ManifestVerifier.json.decodeFromString(AppliedState.serializer(), File(dir, STATE_FILE).readText())
        }.getOrNull() ?: return null to null
        return dir to state
    }

    private fun deleteReleasesExcept(keep: Set<String>) {
        releases.listFiles()?.forEach { if (it.name !in keep) it.deleteRecursively() }
        File(root, "current.tmp").delete()
    }

    /** Written and fsynced, so the pointer never names a release whose bytes are still in the page cache. */
    private fun writeDurably(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    public companion object {
        /** The `platform` every manifest for this SDK must name. */
        public const val PLATFORM: String = "android"
        internal const val STATE_FILE = ".ship-state.json"
        internal const val MANIFEST_LIMIT = 1L shl 20
        private val DIR_NAME = Regex("^[0-9a-f-]{36}$")
    }
}
