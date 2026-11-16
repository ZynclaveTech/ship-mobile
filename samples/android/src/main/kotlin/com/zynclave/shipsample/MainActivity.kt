package com.zynclave.shipsample

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zynclave.ship.ShipStrings
import com.zynclave.ship.ui.ShipPopupHost
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Resources now read OTA strings first, e.g. R.string.* in your own screens.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ShipStrings.wrap(newBase) { SampleApp.ship.content })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.data?.let(SampleApp.ship::open)
        setContent {
            MaterialTheme { SampleRoot(SampleApp.ship) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let(SampleApp.ship::open)
    }
}

@Composable
private fun SampleRoot(ship: Ship) {
    val scope = rememberCoroutineScope()
    // Runs at launch, and again each time the app returns to the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { scope.launch { ship.sync() } }
    BackHandler(enabled = ship.screen != Screen.Home) { ship.screen = Screen.Home }

    Box {
        when (ship.screen) {
            Screen.Home -> HomeScreen(ship)
            Screen.Details -> DetailsScreen(onBack = { ship.screen = Screen.Home })
            Screen.Debug -> DebugScreen(ship, onBack = { ship.screen = Screen.Home })
        }
        ship.popups?.let { ShipPopupHost(it) }
    }
}
