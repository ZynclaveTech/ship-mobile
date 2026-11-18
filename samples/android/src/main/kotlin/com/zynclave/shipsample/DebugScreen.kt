package com.zynclave.shipsample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(ship: Ship, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Reading revision makes the rows follow each sync.
    val state = ship.revision.let { ship.client?.applied }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow("Release", state?.releaseId ?: "none")
            InfoRow("Seq", state?.seq?.toString() ?: "none")
            InfoRow("Channel", ship.channel)
            InfoRow("Runtime", ship.runtime)
            if (ship.status.isNotEmpty()) InfoRow("Status", ship.status)
            Button(
                onClick = { scope.launch { ship.sync() } },
                enabled = ship.isConfigured && !ship.syncing,
            ) { Text("Check now") }
            OutlinedButton(onClick = ship::resetPopups, enabled = ship.isConfigured) { Text("Reset popup memory") }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title)
        Text(value)
    }
}
