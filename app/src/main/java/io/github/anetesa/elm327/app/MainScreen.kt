package io.github.anetesa.elm327.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.anetesa.elm327.AdapterProfiles

@Composable
fun MainScreen(state: UiState, model: EmulatorViewModel) {
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("ELM327 Emulator", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "Turns this phone into an OBD-II adapter with a virtual car behind it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Picker("Adapter", state.profile.title, AdapterProfiles.all.map { it.title }, enabled = !state.running) { i ->
                    model.select(AdapterProfiles.all[i])
                }
            }
            item {
                Picker("Car", state.car.title, CarChoice.entries.map { it.title }, enabled = !state.running) { i ->
                    model.select(CarChoice.entries[i])
                }
            }
            items(TransportKind.entries) { kind -> TransportRow(kind, state, model) }
            item {
                if (state.running) {
                    OutlinedButton(model::stop, Modifier.fillMaxWidth()) { Text("Stop") }
                } else {
                    Button(model::start, Modifier.fillMaxWidth(), enabled = state.enabled.isNotEmpty()) { Text("Start") }
                }
            }
            item { Connection(state, model) }
            item { Text("Log", style = MaterialTheme.typography.titleMedium) }
            items(state.log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

@Composable
private fun TransportRow(kind: TransportKind, state: UiState, model: EmulatorViewModel) {
    val status = state.statuses[kind]
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium)
                val (text, color) = when (status) {
                    null, ServerStatus.Off -> (if (kind in state.enabled) "ready" else "off") to MaterialTheme.colorScheme.onSurfaceVariant
                    is ServerStatus.Listening -> "waiting — ${status.detail}" to MaterialTheme.colorScheme.primary
                    is ServerStatus.Connected -> "connected: ${status.client}" to Color(0xFF2E7D32)
                    is ServerStatus.Failed -> status.message to MaterialTheme.colorScheme.error
                }
                Text(text, style = MaterialTheme.typography.bodySmall, color = color)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = kind in state.enabled, onCheckedChange = { model.toggle(kind) }, enabled = !state.running)
        }
    }
}

/** What to set up on the phone that runs the scanner app. */
@Composable
private fun Connection(state: UiState, model: EmulatorViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How to connect", style = MaterialTheme.typography.titleMedium)
            Text("Bluetooth name: ${state.bluetoothName ?: "unknown"}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Classic: pair the phones in the system Bluetooth settings first. " +
                    "BLE: scan from the scanner app — no pairing. " +
                    "Scanner apps list adapters whose names contain OBD or ELM.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(model::renameBluetooth) { Text("Rename Bluetooth to \"OBDII Emulator\"") }
            Text(
                "Wi‑Fi: turn on this phone's hotspot (or join the same network) and use port ${TcpServer.DEFAULT_PORT} with one of:",
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.addresses.isEmpty()) Text("no network yet", style = MaterialTheme.typography.bodySmall)
            state.addresses.forEach { Text(it, fontFamily = FontFamily.Monospace) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(label: String, value: String, options: List<String>, enabled: Boolean, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    open = false
                    onPick(i)
                })
            }
        }
    }
}
