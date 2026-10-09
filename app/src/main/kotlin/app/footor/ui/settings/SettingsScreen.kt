package app.footor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.footor.ui.components.BackTopBar
import app.footor.ui.components.appContainer

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val container = appContainer()
    var key by rememberSaveable { mutableStateOf(container.apiKey.orEmpty()) }
    var saved by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Data provider", style = MaterialTheme.typography.titleMedium)
            Text(
                "Footor does not run its own servers. Live data comes from API-Football using your own free API key " +
                    "(100 requests/day on the free plan, from dashboard.api-football.com). " +
                    "Without a key the app shows fictional demo data.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    saved = false
                },
                label = { Text("API-Football key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    container.updateApiKey(key)
                    saved = true
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    key = ""
                    container.updateApiKey(null)
                    saved = true
                }) { Text("Use demo data") }
            }
            if (saved) Text("Saved. Screens reload with the new provider.", color = MaterialTheme.colorScheme.primary)

            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text(
                "No accounts, analytics, ads or Google services. The only network requests go to the data provider " +
                    "and to its image CDN for logos and photos. Your key is stored only on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
