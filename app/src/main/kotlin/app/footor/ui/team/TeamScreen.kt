package app.footor.ui.team

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.footor.core.model.Match
import app.footor.core.model.SquadPlayer
import app.footor.core.model.TeamProfile
import app.footor.ui.components.BackTopBar
import app.footor.ui.components.LoadableContent
import app.footor.ui.components.RemoteImage
import app.footor.ui.components.rememberLoader
import app.footor.ui.matches.MatchRow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.ZoneId

private data class TeamPage(val profile: TeamProfile, val squad: List<SquadPlayer>, val matches: List<Match>)

private val POSITION_ORDER = listOf("Goalkeeper", "Defender", "Midfielder", "Attacker")

@Composable
fun TeamScreen(teamId: Long, onBack: () -> Unit, onMatchClick: (Long) -> Unit, onPlayerClick: (Long, Int) -> Unit) {
    val loader = rememberLoader("team/$teamId") { source ->
        coroutineScope {
            val profile = async { source.team(teamId) }
            val squad = async { source.squad(teamId) }
            val matches = async { source.teamMatches(teamId, last = 5, next = 5) }
            TeamPage(profile.await(), squad.await(), matches.await())
        }
    }
    Scaffold(topBar = { BackTopBar("Team", onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LoadableContent(loader) { page ->
                // Season of the most recent match, which is what player statistics are keyed on.
                val season = page.matches.lastOrNull { !it.status.isLive && it.status.isFinished }?.competition?.season
                    ?: page.matches.firstOrNull()?.competition?.season
                    ?: java.time.LocalDate.now(ZoneId.systemDefault()).year
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Header(page.profile) }
                    if (page.matches.isNotEmpty()) {
                        item { Section("Matches") }
                        items(page.matches, key = { "m${it.id}" }) { match ->
                            MatchRow(match, onClick = { onMatchClick(match.id) })
                            HorizontalDivider(Modifier.padding(start = 72.dp))
                        }
                    }
                    val byPosition = page.squad.groupBy { it.position ?: "Other" }
                        .toSortedMap(compareBy<String> { POSITION_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }.thenBy { it })
                    byPosition.forEach { (position, players) ->
                        item(key = "p$position") { Section(position + "s") }
                        items(players.sortedBy { it.number ?: 99 }, key = { "s${it.player.id}-${it.player.name}" }) { player ->
                            SquadRow(player) { id -> onPlayerClick(id, season) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(profile: TeamProfile) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(profile.team.logoUrl, 64.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(profile.team.name, style = MaterialTheme.typography.headlineSmall)
            val facts = listOfNotNull(
                profile.country,
                profile.founded?.let { "Founded $it" },
            )
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            val venue = listOfNotNull(profile.venueName, profile.venueCity, profile.venueCapacity?.let { "%,d seats".format(it) })
            if (venue.isNotEmpty()) {
                Text(venue.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SquadRow(player: SquadPlayer, onClick: (Long) -> Unit) {
    val id = player.player.id
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (id != null) it.clickable { onClick(id) } else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(player.number?.toString() ?: "", Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        RemoteImage(player.photoUrl, 32.dp, circle = true)
        Spacer(Modifier.width(12.dp))
        Text(player.player.name, Modifier.weight(1f))
        player.age?.let { Text("$it y", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
