package com.zynclave.ship

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShipFlagsTest {
    @get:Rule val temp = TemporaryFolder()

    private val orange = 0xFFFF7A00L

    @Test
    fun flagsParseTypedValues() {
        val p = ShipFlags.parse("""{"a": true, "n": 3, "d": 1.5, "s": "x", "z": 0}""")
        assertEquals(true, ShipFlags.boolean("a", p))
        assertEquals(3, ShipFlags.int("n", p))
        assertEquals(1.5, ShipFlags.double("d", p)!!, 0.0)
        assertEquals("x", ShipFlags.string("s", p))
    }

    @Test
    fun wrongTypeMeansNoOverride() {
        val p = ShipFlags.parse("""{"a": true, "n": 3, "d": 1.5, "s": "x", "z": 0, "q": "true", "m": "5"}""")
        assertNull(ShipFlags.boolean("z", p))   // number posing as bool
        assertNull(ShipFlags.boolean("q", p))   // string posing as bool
        assertNull(ShipFlags.int("a", p))       // bool posing as int
        assertNull(ShipFlags.int("d", p))       // fractional
        assertNull(ShipFlags.int("m", p))       // string posing as int
        assertNull(ShipFlags.double("a", p))
        assertNull(ShipFlags.string("n", p))
        assertNull(ShipFlags.boolean("missing", p))
    }

    @Test
    fun malformedFlagsFileIsEmpty() {
        assertTrue(ShipFlags.parse("[1,2]").isEmpty())
        assertTrue(ShipFlags.parse("not json").isEmpty())
    }

    @Test
    fun themeParsesPlainAndLightDarkPairs() {
        val t = ShipTheme.parse("""{"primary": "#FF7A00", "accent": {"light": "#102030", "dark": "405060"}}""")
        assertEquals(orange, ShipTheme.color("primary", dark = false, theme = t))
        assertEquals(orange, ShipTheme.color("primary", dark = true, theme = t))
        assertEquals(0xFF102030L, ShipTheme.color("accent", dark = false, theme = t))
        assertEquals(0xFF405060L, ShipTheme.color("accent", dark = true, theme = t))
    }

    @Test
    fun badHexAndUnlistedTokensAreRejected() {
        val t = ShipTheme.parse(
            """{"primary": "#GG0000", "primary200": "#FFF", "accent": {"light": "#102030"}, "background": "#000000"}""",
        )
        assertTrue(t.isEmpty())
        for (bad in listOf("\"red\"", "\"#FF7A0\"", "\"#+12345\"", "\"-12345\"", "\"#12 345\"", "\"#FF7A00FF\"", "16742912", "true", "null",
            """{"light": "#FF7A00", "dark": 5}""")) {
            assertTrue(bad, ShipTheme.parse("""{"primary": $bad}""").isEmpty())
        }
        assertTrue(ShipTheme.parse("[]").isEmpty())
    }

    @Test
    fun tokenOutsideAllowlistIgnored() = runTest {
        val signer = TestSigner()
        val net = FakeCdn(signer)
        net.release(seq = 1, "config/theme.json" to """{"primary": "#FF7A00", "brand500": "#102030", "background": "#000000"}""")
        val c = ShipClient(testConfig(signer), temp.root, net, Dispatchers.Unconfined)
        c.sync()
        assertEquals(orange, ShipTheme.color("primary", dark = false, content = c.content))
        assertNull("not in the default tokens", ShipTheme.color("brand500", dark = false, content = c.content))
        assertNull("never allowed", ShipTheme.color("background", dark = false, content = c.content))
        // The same release read with another allowlist: the cached parse must not leak the first filter.
        assertEquals(0xFF102030L, ShipTheme.color("brand500", dark = false, content = c.content, allowed = setOf("brand500")))
        assertNull(ShipTheme.color("primary", dark = false, content = c.content, allowed = setOf("brand500")))
        assertEquals(orange, ShipTheme.color("primary", dark = false, content = c.content))
        assertEquals(
            setOf("primary", "accent", "surface", "surfaceVariant", "onSurface", "onSurfaceVariant", "onPrimary"),
            ShipTheme.DEFAULT_TOKENS,
        )
    }

    @Test
    fun lookupsReadAppliedReleaseAndRevertMeansNone() = runTest {
        val signer = TestSigner()
        val net = FakeCdn(signer)
        net.release(seq = 1, "config/flags.json" to """{"beta": true}""", "config/theme.json" to """{"primary": "#FF7A00"}""")
        val c = ShipClient(testConfig(signer), temp.root, net, Dispatchers.Unconfined)
        c.sync()
        assertEquals(true, ShipFlags.boolean("beta", c.content))
        assertNotNull(ShipTheme.color("primary", dark = false, content = c.content))

        net.manifest = ShipResponse(
            signer.envelope(testManifest(seq = 2, runtime = "*", revert = true, assets = emptyList())), 200,
        )
        c.sync()
        assertNull(ShipFlags.boolean("beta", c.content))
        assertNull(ShipTheme.color("primary", dark = false, content = c.content))
        assertNull(ShipFlags.boolean("beta", null))
    }
}
