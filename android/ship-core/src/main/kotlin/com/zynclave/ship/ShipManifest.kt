package com.zynclave.ship

import com.google.crypto.tink.subtle.Ed25519Verify
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

/*
 * Zynclave Ship manifests: the signed envelope, the manifest inside it, and
 * the checks a device runs before trusting either. Kotlin counterpart of
 * ios/Sources/ZynclaveShip/Manifest.swift.
 */

/** The signed envelope the Ship API serves verbatim. */
@Serializable
internal data class ShipEnvelope(
    /** Base64 of the raw manifest JSON. Signatures cover these exact bytes, never a re-encoding. */
    val payload: String,
    val signatures: List<Signature>,
) {
    @Serializable
    data class Signature(val kid: String, val sig: String)
}

/** One content-addressed file in a release. */
@Serializable
data class ShipAsset(val name: String, val sha256: String, val size: Long)

/** The signed description of one release (schema 1). */
@Serializable
data class ShipManifest(
    val schema: Int,
    @SerialName("release_id") val releaseId: String,
    val app: String,
    val platform: String,
    val runtime: String,
    val channel: String,
    val seq: Long,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("revert_to_embedded") val revertToEmbedded: Boolean = false,
    val assets: List<ShipAsset> = emptyList(),
)

/** A pinned verification key, as served by `/v1/apps/{app}/keys`. */
class PinnedKey private constructor(val kid: String, private val verifier: Ed25519Verify) {
    fun verifies(signature: ByteArray, message: ByteArray): Boolean =
        try {
            verifier.verify(signature, message)
            true
        } catch (_: GeneralSecurityException) {
            false
        }

    companion object {
        /** [base64] is the raw 32-byte Ed25519 public key; null if it isn't one. */
        fun of(kid: String, base64: String): PinnedKey? {
            val raw = decodeBase64(base64) ?: return null
            if (raw.size != Ed25519Verify.PUBLIC_KEY_LEN) return null
            return PinnedKey(kid, Ed25519Verify(raw))
        }
    }
}

/** What the asking device expects; a validly signed manifest for anything else is rejected. */
data class ShipExpectation(
    val app: String,
    val runtime: String,
    val channel: String,
    val platform: String = "android",
    /** Seq of the release this device last applied (0 if none). Lower is rejected. */
    val minSeq: Long = 0,
)

sealed class ShipException(message: String) : Exception(message) {
    class Malformed(detail: String) : ShipException("malformed: $detail")
    class BadSignature : ShipException("bad signature")
    class WrongTarget : ShipException("manifest is for another app, platform, channel or runtime")
    class Stale : ShipException("older than the applied release")
    class UnsafeAssetName(val name: String) : ShipException("unsafe asset name: $name")
    class AssetMismatch(val name: String) : ShipException("asset does not match its hash: $name")
    class Http(val status: Int) : ShipException("HTTP $status")
    class TooLarge : ShipException("response larger than allowed")
}

object ManifestVerifier {
    /** Prefix of every signed message, so a Ship signature can't be replayed as anything else. */
    private val signingDomain = "zynclave-ship/manifest/v1\n".toByteArray()

    /** Unknown keys are ignored and a null `assets`/`revert_to_embedded` means absent, as on iOS. */
    internal val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** The exact bytes the server's Ed25519 key signs: domain + lowercase hex SHA-256 of the payload. */
    internal fun signingInput(payload: ByteArray): ByteArray = signingDomain + sha256Hex(payload).toByteArray()

    /** Verifies the envelope against [pinned], then the manifest against [expect]. */
    fun verify(raw: ByteArray, pinned: List<PinnedKey>, expect: ShipExpectation): ShipManifest {
        val env = try {
            json.decodeFromString<ShipEnvelope>(raw.decodeToString())
        } catch (e: SerializationException) {
            throw ShipException.Malformed("envelope: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ShipException.Malformed("envelope: ${e.message}")
        }
        val payload = decodeBase64(env.payload) ?: throw ShipException.Malformed("payload is not base64")
        val message = signingInput(payload)
        val signed = env.signatures.any { sig ->
            val bytes = decodeBase64(sig.sig) ?: return@any false
            pinned.any { it.kid == sig.kid && it.verifies(bytes, message) }
        }
        if (!signed) throw ShipException.BadSignature()

        val m = try {
            json.decodeFromString<ShipManifest>(payload.decodeToString())
        } catch (e: SerializationException) {
            throw ShipException.Malformed("payload: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw ShipException.Malformed("payload: ${e.message}")
        }
        validate(m)
        val runtimeOk = m.runtime == expect.runtime || (m.runtime == "*" && m.revertToEmbedded)
        if (m.app != expect.app || m.platform != expect.platform || m.channel != expect.channel || !runtimeOk) {
            throw ShipException.WrongTarget()
        }
        if (m.seq < expect.minSeq) throw ShipException.Stale()
        return m
    }

    internal fun validate(m: ShipManifest) {
        if (m.schema != 1) throw ShipException.Malformed("schema ${m.schema}")
        if (m.seq <= 0 || m.releaseId.isEmpty() || m.issuedAt.isEmpty()) {
            throw ShipException.Malformed("seq/release_id/issued_at")
        }
        if (m.runtime.isEmpty() || m.channel.isEmpty() || m.app.isEmpty()) {
            throw ShipException.Malformed("app/runtime/channel")
        }
        if (m.platform != "ios" && m.platform != "android") throw ShipException.Malformed("platform ${m.platform}")
        if (m.runtime == "*" && !m.revertToEmbedded) throw ShipException.Malformed("runtime * on non-revert")
        if (m.revertToEmbedded && m.assets.isNotEmpty()) throw ShipException.Malformed("revert with assets")
        val names = HashSet<String>()
        for (a in m.assets) {
            checkAssetName(a.name)
            if (!names.add(a.name)) throw ShipException.Malformed("duplicate asset ${a.name}")
            val hexOk = a.sha256.length == 64 && a.sha256.all { it in '0'..'9' || it in 'a'..'f' }
            if (a.size < 0 || !hexOk) throw ShipException.Malformed("asset ${a.name}")
        }
    }

    /**
     * Asset names become file paths on the device, so a signed-but-hostile
     * name must never escape the release directory.
     */
    internal fun checkAssetName(name: String) {
        val parts = name.split("/")
        val ok = name.isNotEmpty() && name.toByteArray().size <= 255 && !name.startsWith("/") &&
            !name.contains('\\') && !name.contains('\u0000') && name != ShipClient.STATE_FILE &&
            parts.all { it.isNotEmpty() && it != "." && it != ".." }
        if (!ok) throw ShipException.UnsafeAssetName(name)
    }
}

internal fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

/** Standard base64 (what Go's encoding/json writes for []byte), or null. */
internal fun decodeBase64(s: String): ByteArray? =
    try {
        Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
        null
    }
