package com.zynclave.ship.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** What a `web` action does: the host's [ShipUiOptions.openWeb] if set, else the system browser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SduiActionsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://example.com/a"

    @Test
    fun `openWeb receives the url and the default opener is not used`() {
        val opened = mutableListOf<Uri>()
        val options = ShipUiOptions(deeplinkScheme = TEST_SCHEME, openDeeplink = {}, openWeb = { opened += it })
        SduiActions.perform(app, options, Sdui.Action.Web(url)) {}
        assertEquals(listOf(Uri.parse(url)), opened)
        assertNull(shadowOf(app as Application).nextStartedActivity)
    }

    @Test
    fun `without openWeb the browser intent is started, as before`() {
        val options = ShipUiOptions(deeplinkScheme = TEST_SCHEME, openDeeplink = {})
        assertNull(options.openWeb)
        SduiActions.perform(app, options, Sdui.Action.Web(url)) {}
        val intent = shadowOf(app as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(url), intent.data)
        assertEquals(true, intent.categories.contains(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `openWeb does not touch deeplink or dismiss`() {
        var deeplinks = 0
        var dismissed = 0
        var web = 0
        val options = ShipUiOptions(deeplinkScheme = TEST_SCHEME, openDeeplink = { deeplinks++ }, openWeb = { web++ })
        SduiActions.perform(app, options, Sdui.Action.Deeplink("shipsample://x")) { dismissed++ }
        SduiActions.perform(app, options, Sdui.Action.Dismiss) { dismissed++ }
        assertEquals(Triple(1, 1, 0), Triple(deeplinks, dismissed, web))
    }
}
