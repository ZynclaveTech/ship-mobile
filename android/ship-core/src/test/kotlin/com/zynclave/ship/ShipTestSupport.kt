package com.zynclave.ship

import com.google.crypto.tink.subtle.Ed25519Sign
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.Base64

const val TEST_APP = "myapp"
const val TEST_CHANNEL = "prod"
const val TEST_RUNTIME = "android-1.0.0"

/** A throwaway signer: the key pair is generated per test, never a real release key. */
class TestSigner {
    private val keys = Ed25519Sign.KeyPair.newKeyPair()
    val pinned: PinnedKey = PinnedKey.of("k1", b64(keys.publicKey))!!

    fun envelope(m: JsonObject, kid: String = "k1"): ByteArray {
        val payload = Json.encodeToString(JsonObject.serializer(), m).toByteArray()
        val sig = Ed25519Sign(keys.privateKey).sign(ManifestVerifier.signingInput(payload))
        return """{"payload":"${b64(payload)}","signatures":[{"kid":"$kid","sig":"${b64(sig)}"}]}""".toByteArray()
    }
}

fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

fun testManifest(
    seq: Long = 1,
    platform: String = "android",
    runtime: String = TEST_RUNTIME,
    channel: String = TEST_CHANNEL,
    revert: Boolean = false,
    assets: List<ShipAsset> = listOf(ShipAsset("a.txt", "0".repeat(64), 1)),
): JsonObject = buildJsonObject {
    put("schema", 1)
    put("release_id", "rel-$seq")
    put("app", TEST_APP)
    put("platform", platform)
    put("runtime", runtime)
    put("channel", channel)
    put("seq", seq)
    put("issued_at", "2026-01-01T00:00:00Z")
    if (revert) put("revert_to_embedded", true)
    put("assets", buildJsonArray {
        assets.forEach { a ->
            add(buildJsonObject {
                put("name", a.name); put("sha256", a.sha256); put("size", a.size)
            })
        }
    })
}

fun testExpectation(runtime: String = TEST_RUNTIME) =
    ShipExpectation(app = TEST_APP, runtime = runtime, channel = TEST_CHANNEL)

/** The CDN: one manifest response plus content-addressed blobs. */
class FakeCdn(private val signer: TestSigner) : ShipTransport {
    var manifest = ShipResponse(ByteArray(0), 204)
    val blobs = HashMap<String, ByteArray>()
    val requested = ArrayList<HttpUrl>()
    private val names = HashMap<String, String>()

    fun release(seq: Long, vararg files: Pair<String, String>) {
        val assets = files.map { (name, text) ->
            val bytes = text.toByteArray()
            val sum = sha256Hex(bytes)
            blobs[sum] = bytes
            names[name] = sum
            ShipAsset(name, sum, bytes.size.toLong())
        }
        manifest = ShipResponse(signer.envelope(testManifest(seq = seq, assets = assets)), 200)
    }

    fun corrupt(name: String) {
        val sum = names.getValue(name)
        blobs[sum] = blobs.getValue(sum).reversedArray()
    }

    override suspend fun get(url: HttpUrl, limit: Long): ShipResponse {
        requested += url
        val r = if (url.encodedPath == "/v1/manifest") manifest
        else blobs[url.pathSegments.last()]?.let { ShipResponse(it, 200) } ?: ShipResponse(ByteArray(0), 404)
        if (r.body.size > limit) throw ShipException.TooLarge()
        return r
    }
}

fun testConfig(signer: TestSigner, runtime: String = TEST_RUNTIME) = ShipConfig(
    cdn = "https://cdn.test/".toHttpUrl(), app = TEST_APP, channel = TEST_CHANNEL, runtime = runtime,
    pinned = listOf(signer.pinned), bucket = 42,
)

fun File.releaseCount(): Int = File(this, "releases").list()!!.size
