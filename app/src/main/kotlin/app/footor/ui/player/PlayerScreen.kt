package app.footor.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.footor.core.model.PlayerProfile
import app.footor.core.model.PlayerSeasonStats
import app.footor.ui.components.BackTopBar
import app.footor.ui.components.LoadableContent
import app.footor.ui.components.RemoteImage
import app.footor.ui.components.rememberLoader

@Composable
fun PlayerScreen(playerId: Long, season: Int, onBack: () -> Unit) {
    val loader = rememberLoader("player/$playerId/$season") { it.player(playerId, season) }
    Scaffold(topBar = { BackTopBar("Player", onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LoadableContent(loader) { player ->
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Header(player) }
                    item {
                        Text(
                            "Season $season/${(season + 1) % 100}",
                            Modifier.padding(16.dp, 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(player.seasons) { SeasonCard(it) }
                }
            }
        }
    }
}

@Composable
private fun Header(player: PlayerProfile) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(player.photoUrl, 72.dp, circle = true)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(player.name, style = MaterialTheme.typography.headlineSmall)
            val facts = listOfNotNull(
                player.position,
                player.age?.let { "$it years" },
                player.nationality,
            )
            Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            val body = listOfNotNull(player.height, player.weight, player.birthDate?.let { "Born $it" })
            if (body.isNotEmpty()) {
                Text(body.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (player.injured == true) Text("Injured", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SeasonCard(stats: PlayerSeasonStats) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(stats.competition.logoUrl, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(stats.competition.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(stats.team.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val cells = listOf(
                "Apps" to stats.appearances,
                "Minutes" to stats.minutes,
                "Goals" to stats.goals,
                "Assists" to stats.assists,
                "Rating" to stats.rating?.let { "%.2f".format(it) },
                "Shots (on)" to stats.shotsTotal?.let { "$it (${stats.shotsOnTarget ?: 0})" },
                "Key passes" to stats.keyPasses,
                "Pass acc." to stats.passAccuracy?.let { "$it%" },
                "Tackles" to stats.tackles,
                "Interceptions" to stats.interceptions,
                "Duels won" to stats.duelsWon?.let { "$it/${stats.duelsTotal ?: 0}" },
                "Dribbles" to stats.dribblesSuccess,
                "Yellow" to stats.yellowCards,
                "Red" to stats.redCards,
                "Saves" to stats.saves,
                "Conceded" to stats.goalsConceded,
            ).filter { it.second != null }
            cells.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { (label, value) -> StatCell(label, value.toString(), Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
