package com.zynclave.ship

import com.google.crypto.tink.subtle.Ed25519Sign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a device trusts: signature, target, seq and asset names. */
class ManifestTest {
    private val signer = TestSigner()
    private val pinned = listOf(signer.pinned)

    @Test
    fun `verifies a signed envelope`() {
        val m = ManifestVerifier.verify(signer.envelope(testManifest(seq = 7)), pinned, testExpectation())
        assertEquals(7L, m.seq)
        assertEquals("rel-7", m.releaseId)
    }

    @Test
    fun `rejects a tampered payload`() {
        val raw = String(signer.envelope(testManifest()))
        val tampered = raw.replace("\"payload\":\"ey", "\"payload\":\"ez")
        assertThrows(ShipException.BadSignature::class.java) {
            ManifestVerifier.verify(tampered.toByteArray(), pinned, testExpectation())
        }
    }

    @Test
    fun `rejects unknown keys and a wrong kid`() {
        val raw = signer.envelope(testManifest(seq = 5))
        val other = Ed25519Sign.KeyPair.newKeyPair()
        assertThrows(ShipException.BadSignature::class.java) {
            ManifestVerifier.verify(raw, listOf(PinnedKey.of("k1", b64(other.publicKey))!!), testExpectation())
        }
        assertThrows("right key, wrong kid", ShipException.BadSignature::class.java) {
            val k2 = PinnedKey.of("k2", b64(Ed25519Sign.KeyPair.newKeyPair().publicKey))!!
            ManifestVerifier.verify(raw, listOf(k2), testExpectation())
        }
    }

    @Test
    fun emptyPinnedKeysRejectsEverything() {
        val raw = signer.envelope(testManifest())
        assertThrows(ShipException.BadSignature::class.java) {
            ManifestVerifier.verify(raw, emptyList(), testExpectation())
        }
    }

    @Test
    fun `rejects a valid signature for another target`() {
        assertThrows(ShipException.WrongTarget::class.java) {
            ManifestVerifier.verify(signer.envelope(testManifest(platform = "ios")), pinned, testExpectation())
        }
        assertThrows(ShipException.WrongTarget::class.java) {
            ManifestVerifier.verify(signer.envelope(testManifest(channel = "staging")), pinned, testExpectation())
        }
        assertThrows(ShipException.WrongTarget::class.java) {
            ManifestVerifier.verify(signer.envelope(testManifest()), pinned, testExpectation(runtime = "android-9.9.9"))
        }
    }

    @Test
    fun `rejects a downgrade and accepts the same seq`() {
        val raw = signer.envelope(testManifest(seq = 5))
        assertThrows(ShipException.Stale::class.java) {
            ManifestVerifier.verify(raw, pinned, testExpectation().copy(minSeq = 6))
        }
        assertEquals(5L, ManifestVerifier.verify(raw, pinned, testExpectation().copy(minSeq = 5)).seq)
    }

    @Test
    fun `runtime star is only for a revert, and a revert carries no assets`() {
        val revert = testManifest(runtime = "*", revert = true, assets = emptyList())
        assertTrue(ManifestVerifier.verify(signer.envelope(revert), pinned, testExpectation()).revertToEmbedded)
        assertThrows(ShipException.Malformed::class.java) {
            ManifestVerifier.verify(signer.envelope(testManifest(runtime = "*")), pinned, testExpectation())
        }
        assertThrows(ShipException.Malformed::class.java) {
            ManifestVerifier.verify(signer.envelope(testManifest(revert = true)), pinned, testExpectation())
        }
    }

    @Test
    fun `asset names can never leave the release directory`() {
        for (bad in listOf("../x", "a/../../x", "/etc/passwd", "a//b", "a/./b", "a\\b", "", ".ship-state.json",
            "a\u0000b", "x".repeat(256))) {
            assertThrows("'$bad'", ShipException.UnsafeAssetName::class.java) { ManifestVerifier.checkAssetName(bad) }
        }
        ManifestVerifier.checkAssetName("images/banner.png")
        ManifestVerifier.checkAssetName(".hidden/ok.json")
    }
}
