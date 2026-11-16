package com.zynclave.ship

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** How a release is applied: all or nothing, survives a restart, dropped after an app update. */
class ShipClientTest {
    @get:Rule val temp = TemporaryFolder()

    private val signer = TestSigner()

    private fun client(net: ShipTransport, runtime: String = TEST_RUNTIME, root: File = temp.root) =
        ShipClient(testConfig(signer, runtime), root, net, Dispatchers.Unconfined)

    @Test
    fun `applies a release and serves its files and strings`() = runTest {
        val net = FakeCdn(signer)
        net.release(seq = 1, "strings/en.json" to """{"hello":"Hi OTA"}""", "images/a.png" to "PNG")
        val client = client(net)

        assertTrue(client.sync() is SyncResult.Applied)
        assertEquals("Hi OTA", client.content.string("hello", "en"))
        assertNull(client.content.string("hello", "fr"))
        assertEquals("PNG", client.content.file("images/a.png")!!.readText())
        assertNull("not in the release", client.content.file("images/b.png"))
        assertEquals(SyncResult.UpToDate(1), client.sync())

        val url = net.requested.first()
        assertEquals("/v1/manifest", url.encodedPath)
        assertEquals("android", url.queryParameter("platform"))
        assertEquals(TEST_RUNTIME, url.queryParameter("runtime"))
        assertEquals("42", url.queryParameter("bucket"))
    }

    @Test
    fun `nothing for this device keeps what is applied`() = runTest {
        val net = FakeCdn(signer)
        net.release(seq = 1, "a.txt" to "one")
        val client = client(net)
        client.sync()
        net.manifest = ShipResponse(ByteArray(0), 204)
        assertEquals(SyncResult.NoUpdate, client.sync())
        assertEquals("one", client.content.file("a.txt")!!.readText())
    }

    @Test
    fun `a bad blob leaves the old release exactly as it was`() = runTest {
        val net = FakeCdn(signer)
        net.release(seq = 1, "a.txt" to "one")
        val client = client(net)
        client.sync()

        net.release(seq = 2, "a.txt" to "two", "b.txt" to "bee")
        net.corrupt("b.txt")
        try {
            client.sync(); fail("corrupt blob applied")
        } catch (_: ShipException.AssetMismatch) {}
        assertEquals(1L, client.applied!!.seq)
        assertEquals("one", client.content.file("a.txt")!!.readText())
        assertEquals("the half-written release is gone", 1, temp.root.releaseCount())
    }

    @Test
    fun syncFailureKeepsAppliedContent() = runTest {
        val net = FakeCdn(signer)
        net.release(seq = 1, "strings/en.json" to """{"k":"good"}""")
        val c = client(net)
        c.sync()
        val before = c.applied
        assertEquals(1L, before?.seq)

        val failing = client(ShipTransport { _, _ -> throw java.io.IOException("offline") })
        try {
            failing.sync(); fail("sync should have failed")
        } catch (_: java.io.IOException) {}
        assertEquals(before, failing.applied)
        assertEquals("good", failing.content.string("k", "en"))
    }

    @Test
    fun `the applied release survives a restart and is dropped after an app update`() = runTest {
        val net = FakeCdn(signer)
        net.release(seq = 3, "a.txt" to "three")
        client(net).sync()

        val restarted = client(net)
        assertEquals(3L, restarted.applied!!.seq)
        assertEquals("three", restarted.content.file("a.txt")!!.readText())

        val updated = client(net, runtime = "android-1.1.0")
        assertNull("a release is for one runtime", updated.applied)
        assertNull(updated.content.file("a.txt"))
        assertEquals(0, temp.root.releaseCount())
    }

    @Test
    fun `newer releases replace older ones on disk`() = runTest {
        val net = FakeCdn(signer)
        val client = client(net)
        for (seq in 1L..4L) {
            net.release(seq = seq, "a.txt" to "v$seq")
            client.sync()
        }
        assertEquals("v4", client.content.file("a.txt")!!.readText())
        assertEquals("current and the one it replaced", 2, temp.root.releaseCount())
        client(net)  // the next start keeps only the applied one
        assertEquals(1, temp.root.releaseCount())
    }

    @Test
    fun `a revert drops every OTA file`() = runTest {
        val net = FakeCdn(signer)
        net.release(seq = 1, "a.txt" to "one")
        val client = client(net)
        client.sync()
        net.manifest = ShipResponse(
            signer.envelope(testManifest(seq = 2, runtime = "*", revert = true, assets = emptyList())), 200,
        )
        client.sync()
        assertTrue(client.applied!!.revertToEmbedded)
        assertNull(client.content.file("a.txt"))
    }

    @Test
    fun `refuses an older release and a release over the size cap`() = runTest {
        val net = FakeCdn(signer)
        net.release(seq = 5, "a.txt" to "five")
        val client = client(net)
        client.sync()
        net.release(seq = 4, "a.txt" to "four")
        try {
            client.sync(); fail("older release applied")
        } catch (_: ShipException.Stale) {}

        val small = ShipClient(testConfig(signer).copy(maxReleaseBytes = 3), File(temp.root, "small"), net, Dispatchers.Unconfined)
        net.release(seq = 6, "a.txt" to "too big")
        try {
            small.sync(); fail("oversize release applied")
        } catch (_: ShipException.Malformed) {}
        assertNull(small.applied)
    }

    @Test
    fun `http transport refuses bodies over the limit, but reports errors as their status`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val t = OkHttpShipTransport(OkHttpClient())
            server.enqueue(MockResponse(code = 200, body = "12345"))
            assertArrayEquals("12345".toByteArray(), t.get(server.url("/ok"), 5).body)
            server.enqueue(MockResponse(code = 200, body = "123456"))
            try {
                t.get(server.url("/big"), 5); fail("over the limit")
            } catch (_: ShipException.TooLarge) {}
            // An error page larger than the asset is still an HTTP error, not "too large".
            server.enqueue(MockResponse(code = 404, body = "<html>" + "x".repeat(4096) + "</html>"))
            assertEquals(404, t.get(server.url("/missing"), 5).status)
        } finally {
            server.close()
        }
    }
}
