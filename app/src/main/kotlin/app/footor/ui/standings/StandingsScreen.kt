package app.footor.ui.standings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.footor.core.model.StandingRow
import app.footor.core.model.Standings
import app.footor.ui.components.BackTopBar
import app.footor.ui.components.LoadableContent
import app.footor.ui.components.RemoteImage
import app.footor.ui.components.rememberLoader

@Composable
fun StandingsScreen(competitionId: Long, season: Int, onBack: () -> Unit, onTeamClick: (Long) -> Unit) {
    val loader = rememberLoader("standings/$competitionId/$season") { it.standings(competitionId, season) }
    Scaffold(topBar = { BackTopBar("Table", onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LoadableContent(loader) { standings ->
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${standings.competition.name} $season/${(season + 1) % 100}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    standingsTable(standings, highlightTeamIds = emptySet(), onTeamClick = onTeamClick)
                }
            }
        }
    }
}

/** League table rows, usable inside any LazyColumn. */
fun LazyListScope.standingsTable(standings: Standings, highlightTeamIds: Set<Long>, onTeamClick: (Long) -> Unit) {
    standings.groups.forEach { group ->
        val name = group.name
        if (standings.groups.size > 1 && name != null) {
            item { Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)) }
        }
        item { HeaderRow() }
        items(group.rows, key = { "${group.name}-${it.team.id}" }) { row ->
            StandingRowView(row, highlighted = row.team.id in highlightTeamIds, onClick = { onTeamClick(row.team.id) })
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Cell("#", 28)
        Text("Team", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        listOf("P", "W", "D", "L", "GD", "Pts").forEach { Cell(it, if (it == "Pts" || it == "GD") 36 else 26, header = true) }
    }
    HorizontalDivider()
}

@Composable
private fun StandingRowView(row: StandingRow, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .height(40.dp)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Coloured strip for promotion/relegation zones, as described by the provider.
        Box(Modifier.width(4.dp).fillMaxHeight().background(zoneColor(row.description)))
        Spacer(Modifier.width(8.dp))
        Cell(row.rank.toString(), 28)
        RemoteImage(row.team.logoUrl, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(row.team.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Cell(row.played.toString(), 26)
        Cell(row.won.toString(), 26)
        Cell(row.drawn.toString(), 26)
        Cell(row.lost.toString(), 26)
        Cell((if (row.goalDifference > 0) "+" else "") + row.goalDifference, 36)
        Cell(row.points.toString(), 36, bold = true)
    }
}

@Composable
private fun zoneColor(description: String?): Color {
    val d = description?.lowercase() ?: return Color.Transparent
    return when {
        "relegation" in d -> Color(0xFFE53935)
        "champions league" in d || "promotion" in d -> MaterialTheme.colorScheme.primary
        "europa" in d || "conference" in d || "play" in d -> Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.outline
    }
}

@Composable
private fun Cell(text: String, widthDp: Int, bold: Boolean = false, header: Boolean = false) {
    Text(
        text,
        modifier = Modifier.width(widthDp.dp),
        textAlign = TextAlign.Center,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
    )
}
