package com.zynclave.ship

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Envelopes signed by the Ship server's own signing code (`testdata/signing`,
 * shared with the iOS tests): what the server writes is what this verifies.
 */
class SigningVectorTest {
    private val dir = File(requireNotNull(System.getProperty("ship.testdata")) { "ship.testdata is not set" }, "signing")
    private val kid = File(dir, "kid.txt").readText().trim()
    private val pinned = listOf(requireNotNull(PinnedKey.of(kid, File(dir, "public-key.b64").readText().trim())))

    private fun envelope(platform: String) = File(dir, "envelope-$platform.json").readBytes()

    private fun expect(platform: String) = ShipExpectation(
        app = "ship-sample", runtime = "$platform-1.0.0", channel = "testflight", platform = platform,
    )

    @Test
    fun `the server's envelopes verify`() {
        assertEquals("test-vector-1", kid)
        for (platform in listOf("ios", "android")) {
            val m = ManifestVerifier.verify(envelope(platform), pinned, expect(platform))
            assertEquals("ship-sample", m.app)
            assertEquals(platform, m.platform)
            assertEquals(listOf("strings/en.json"), m.assets.map { it.name })
            val asset = File(dir, "asset-strings-en.json").readBytes()
            assertEquals(sha256Hex(asset), m.assets.single().sha256)
            assertEquals(asset.size.toLong(), m.assets.single().size)
        }
    }

    @Test
    fun `one flipped payload byte is a bad signature`() {
        for (platform in listOf("ios", "android")) {
            val env = ManifestVerifier.json.parseToJsonElement(envelope(platform).decodeToString()).jsonObject
            val payload = Base64.getDecoder().decode(env.getValue("payload").jsonPrimitive.content)
            payload[payload.size / 2] = (payload[payload.size / 2].toInt() xor 0x01).toByte()
            val tampered = JsonObject(env + ("payload" to JsonPrimitive(Base64.getEncoder().encodeToString(payload))))
            assertThrows(ShipException.BadSignature::class.java) {
                ManifestVerifier.verify(tampered.toString().toByteArray(), pinned, expect(platform))
            }
        }
    }
}
