package com.zynclave.shipsample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zynclave.ship.ShipFlags
import com.zynclave.ship.ui.ShipCard
import com.zynclave.ship.ui.rememberShipCards

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(ship: Ship) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ship Sample") },
                actions = { TextButton(onClick = { ship.screen = Screen.Debug }) { Text("Debug") } },
            )
        },
    ) { padding ->
        // Ship's cards sit among your own rows, wherever you choose.
        val cards = ship.cards?.let { rememberShipCards(it, "discover") }.orEmpty()
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp)) {
            item { Header(ship) }
            item { Text("Plain row 1", Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
            items(cards, key = { it.id }) { card -> ShipCard(card, ship.cards!!, Modifier.padding(vertical = 8.dp)) }
            item { Text("Plain row 2", Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
        }
    }
}

@Composable
private fun Header(ship: Ship) {
    // Re-read after each sync: the content object stays, the release it holds moves on.
    val greeting = remember(ship.revision) { ship.content?.string("sample_greeting", ship.language) ?: "Hello" }
    val banner = remember(ship.revision) { ShipFlags.boolean("show_banner", ship.content) == true }
    Column {
        Text(greeting, style = MaterialTheme.typography.headlineSmall)
        if (banner) Text("Banner is on", color = MaterialTheme.colorScheme.primary)
        if (!ship.isConfigured) Text(ship.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
