package com.zynclave.ship

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShipConfigTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = app.getSharedPreferences("zynclave_ship", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `the bucket is chosen once per install and kept`() {
        val first = ShipConfig.stableBucket(app)
        assertTrue(first in 0..99)
        assertEquals(first, prefs.getInt("bucket", -1))
        repeat(5) { assertEquals(first, ShipConfig.stableBucket(app)) }
    }

    @Test
    fun `a stored bucket is read back, and one out of range is replaced`() {
        prefs.edit().putInt("bucket", 42).commit()
        assertEquals(42, ShipConfig.stableBucket(app))
        prefs.edit().putInt("bucket", 140).commit()
        val fresh = ShipConfig.stableBucket(app)
        assertTrue(fresh in 0..99)
        assertEquals(fresh, prefs.getInt("bucket", -1))
    }
}
