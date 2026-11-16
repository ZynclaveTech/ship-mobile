package com.zynclave.ship

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * OTA strings: an applied release's `strings/<language>.json` replaces
 * bundled text by resource name, for every way the app reads a string - but
 * never with different placeholders, never over styled text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShipStringsTest {
    @get:Rule val temp = TemporaryFolder()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val content = ShipContent()

    private fun applied(id: String, vararg names: String) =
        AppliedState(TEST_APP, TEST_CHANNEL, TEST_RUNTIME, id, 1, false, names.toList())

    @Before
    fun applyRelease() {
        val dir = temp.newFolder("rel")
        File(dir, "strings").mkdirs()
        File(dir, "strings/en.json").writeText(
            """
            {
              "welcome_name": "Hey there, %1${'$'}s",
              "code_sent_to": "Code sent to %1${'$'}d",
              "too_long": "Max length is %1${'$'}s."
            }
            """.trimIndent(),
        )
        content.load(dir, applied("rel-1", "strings/en.json"))
    }

    private fun english(): Context {
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return app.createConfigurationContext(config)
    }

    @Test
    fun `replaces bundled text through getString, format arguments included`() {
        val res = ShipResources(english().resources) { content }
        assertEquals("Hey there, Ann", res.getString(R.string.welcome_name, "Ann"))
        assertEquals("Max length is 1 min.", res.getString(R.string.too_long, "1 min"))
    }

    @Test
    fun placeholderMismatchFallsBackToBuiltInString() {
        val res = ShipResources(english().resources) { content }
        // OTA %1$d where the code passes a String would throw at format time.
        assertEquals("Enter the code sent to a@b.c", res.getString(R.string.code_sent_to, "a@b.c"))
    }

    @Test
    fun `no release reads the bundle`() {
        assertEquals("Welcome, Ann", ShipResources(english().resources) { null }.getString(R.string.welcome_name, "Ann"))
        assertEquals("Welcome, Ann", ShipResources(english().resources) { ShipContent() }.getString(R.string.welcome_name, "Ann"))
    }

    @Test
    fun `other languages read the bundle`() {
        val french = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocale(Locale.FRENCH) },
        )
        assertEquals("Welcome, Ann", ShipResources(french.resources) { content }.getString(R.string.welcome_name, "Ann"))
    }

    @Test
    fun `wrap hands out a context whose resources read OTA strings`() {
        val wrapped = ShipStrings.wrap(english()) { content }
        assertEquals("Hey there, Ann", wrapped.getString(R.string.welcome_name, "Ann"))
        assertEquals("Welcome, Ann", ShipStrings.wrap(english()) { null }.getString(R.string.welcome_name, "Ann"))
    }

    @Test
    fun `a configuration context made from the wrapper keeps the override`() {
        val wrapped = ShipContext(english()) { content }
        assertEquals("Hey there, Ann", wrapped.getString(R.string.welcome_name, "Ann"))
        val derived = wrapped.createConfigurationContext(Configuration(wrapped.resources.configuration))
        assertEquals("Hey there, Ann", derived.getString(R.string.welcome_name, "Ann"))
    }

    @Test
    fun `a configuration change pushed onto the wrapper moves its lookups with it`() {
        // The wrapper owns its own ResourcesImpl, so the framework does not
        // update it: the host pushes new configurations onto it.
        val res = ShipResources(english().resources) { content }
        assertEquals("Hey there, Ann", res.getString(R.string.welcome_name, "Ann"))
        val french = Configuration(res.configuration).apply { setLocale(Locale.FRENCH) }
        @Suppress("DEPRECATION")
        res.updateConfiguration(french, res.displayMetrics)
        assertEquals("no French OTA strings: the bundle", "Welcome, Ann", res.getString(R.string.welcome_name, "Ann"))
    }

    @Test
    fun `styled bundled text is never replaced`() {
        val styled = SpannableString("Welcome, %1\$s").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, 7, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        assertNull(ShipStrings.override(content, "welcome_name", "en", styled))
        assertEquals("Hey there, %1\$s", ShipStrings.override(content, "welcome_name", "en", "Welcome, %1\$s"))
    }

    @Test
    fun `placeholders compare by which argument is read and how`() {
        fun same(ota: String, bundled: String) = assertTrue("$ota vs $bundled", ShipStrings.formatsLike(ota, bundled))
        fun differ(ota: String, bundled: String) = assertFalse("$ota vs $bundled", ShipStrings.formatsLike(ota, bundled))

        same("%2\$d from %1\$s", "%1\$s sent %2\$d") // reordered for grammar: fine
        same("%s sent %d", "%1\$s sent %2\$d")         // ordinary indices are 1, 2, ...
        same("%.2f km", "%f km")                       // precision is layout
        same("%,d coins", "%d coins")                  // flags are layout
        same("%s and again %<s", "%1\$s and %1\$s")      // < reuses the previous argument
        same("100%% done%n", "all done")               // %% and %n read nothing
        differ("%s", "%d")
        differ("%1\$s", "%1\$s %2\$s")                 // an argument dropped or added
        differ("%1\$s %3\$s", "%1\$s %2\$s")             // an index the code never passes
        differ("%tY", "%s")
    }

    @Test
    fun `text that String format would refuse never replaces a formatted string`() {
        val bundled = "Welcome, %1\$s"
        for (ota in listOf("%1\$s gets 50%", "%1\$s %q", "%<s", "%0\$s")) {
            assertNull(ota, ShipStrings.formatArgs(ota))
            assertFalse(ota, ShipStrings.formatsLike(ota, bundled))
        }
        assertFalse(ShipStrings.formatsLike("50% off, %1\$s", bundled))
        assertFalse(ShipStrings.formatsLike("%.2d coins", "%d coins"))
        assertFalse(ShipStrings.formatsLike("%#d coins", "%d coins"))
        assertFalse(ShipStrings.formatsLike("%-s", "%s"))
        // A bundled text with a stray % is never formatted, so any plain text may replace it.
        assertTrue(ShipStrings.formatsLike("Tout à 100 %", "Everything 100%"))
    }

    @Test
    fun `Indonesian reads strings id json, though Android reports in`() {
        val dir = temp.newFolder("rel-id")
        File(dir, "strings").mkdirs()
        File(dir, "strings/id.json").writeText("""{"welcome_name": "Halo, %1${'$'}s"}""")
        val indonesian = ShipContent().apply { load(dir, applied("rel-id", "strings/id.json")) }
        val config = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag("id-ID")) }
        val res = app.createConfigurationContext(config).resources
        assertEquals("Halo, Ann", ShipResources(res) { indonesian }.getString(R.string.welcome_name, "Ann"))
        assertEquals("id", ShipStrings.releaseLanguage("in"))
        assertEquals("he", ShipStrings.releaseLanguage("iw"))
        assertEquals("fr", ShipStrings.releaseLanguage("fr"))
    }
}
