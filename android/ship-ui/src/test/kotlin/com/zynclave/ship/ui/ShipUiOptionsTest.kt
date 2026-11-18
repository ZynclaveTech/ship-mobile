package com.zynclave.ship.ui

import org.junit.Assert.assertThrows
import org.junit.Test

class ShipUiOptionsTest {
    private fun options(scheme: String) = ShipUiOptions(deeplinkScheme = scheme, openDeeplink = {})

    @Test
    fun `a valid RFC 3986 scheme is accepted`() {
        for (ok in listOf("shipsample", "myapp", "my-app", "com.example.app", "x+y", "a1", "MyApp")) options(ok)
    }

    @Test
    fun `an empty, malformed or web scheme is a programming error`() {
        for (bad in listOf("", "1app", "-app", "my app", "my_app", "myapp://", "myapp:", "http", "https", "HTTPS", "é")) {
            assertThrows("'$bad'", IllegalArgumentException::class.java) { options(bad) }
        }
    }
}
