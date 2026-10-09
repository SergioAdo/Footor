package app.footor.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.footor.AppContainer
import app.footor.core.live.LiveState
import app.footor.core.live.MatchUpdate
import app.footor.core.model.CardKind
import app.footor.core.model.GoalKind
import app.footor.core.model.Lineup
import app.footor.core.model.LineupPlayer
import app.footor.core.model.Match
import app.footor.core.model.MatchDetails
import app.footor.core.model.MatchEvent
import app.footor.core.model.MatchStatus
import app.footor.core.model.StatLine
import app.footor.core.model.TeamSide
import app.footor.ui.components.BackTopBar
import app.footor.ui.components.CenteredMessage
import app.footor.ui.components.LiveContent
import app.footor.ui.components.LoadableContent
import app.footor.ui.components.RemoteImage
import app.footor.ui.components.appContainer
import app.footor.ui.components.kickoffTime
import app.footor.ui.components.rememberLoader
import app.footor.ui.components.statusLabel
import app.footor.ui.standings.standingsTable
import app.footor.ui.theme.LiveRed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class MatchViewModel(container: AppContainer, matchId: Long) : ViewModel() {
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LiveState<MatchDetails>> = container.tracker
        .flatMapLatest { it.match(matchId, refreshRequests).onStart { emit(LiveState()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveState())

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }
}

private val TABS = listOf("Summary", "Stats", "Lineups", "Table")

@Composable
fun MatchScreen(
    matchId: Long,
    onBack: () -> Unit,
    onTeamClick: (Long) -> Unit,
    onPlayerClick: (Long, Int) -> Unit,
    onCompetitionClick: (Long, Int) -> Unit,
) {
    val container = appContainer()
    val vm: MatchViewModel = viewModel(key = "match/$matchId") { MatchViewModel(container, matchId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Announce what just changed: goals, VAR reversals, red cards.
    LaunchedEffect(state.updates) {
        state.updates.mapNotNull(::describe).forEach { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = { BackTopBar(state.data?.match?.competition?.name ?: "Match", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LiveContent(state, onRetry = vm::refresh) { details ->
                val match = details.match
                val season = match.competition.season ?: match.kickoff.atZone(ZoneId.systemDefault()).year
                Column(Modifier.fillMaxSize()) {
                    MatchHeader(
                        match = match,
                        lastUpdated = state.lastUpdated,
                        onTeamClick = onTeamClick,
                        onCompetitionClick = { onCompetitionClick(match.competition.id, season) },
                    )
                    TabRow(selectedTabIndex = tab) {
                        TABS.forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
                    }
                    when (tab) {
                        0 -> EventsTab(details)
                        1 -> StatsTab(details.statistics)
                        2 -> LineupsTab(details.lineups) { id -> onPlayerClick(id, season) }
                        else -> TableTab(match, season, onTeamClick)
                    }
                }
            }
        }
    }
}

private fun describe(update: MatchUpdate): String? {
    val m = update.match
    return when (update) {
        is MatchUpdate.GoalScored -> {
            val team = if (update.side == TeamSide.HOME) m.home.name else m.away.name
            "GOAL! $team · ${m.home.name} ${update.score} ${m.away.name}"
        }
        is MatchUpdate.GoalCancelled -> {
            val team = if (update.side == TeamSide.HOME) m.home.name else m.away.name
            "Goal for $team cancelled${if (update.byVar) " by VAR" else ""} · ${update.score}"
        }
        is MatchUpdate.RedCard -> "Red card${update.card.player?.let { ": ${it.name}" } ?: ""}"
        is MatchUpdate.StatusChanged -> when (update.to) {
            MatchStatus.HALF_TIME -> "Half-time"
            MatchStatus.FINISHED, MatchStatus.FINISHED_AFTER_EXTRA_TIME, MatchStatus.FINISHED_AFTER_PENALTIES -> "Full-time"
            else -> null
        }
    }
}

private val dateTimeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
private fun MatchHeader(match: Match, lastUpdated: Instant?, onTeamClick: (Long) -> Unit, onCompetitionClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            listOfNotNull(match.competition.name, match.competition.round).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onCompetitionClick).padding(4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeaderTeam(match.home.name, match.home.logoUrl, Modifier.weight(1f)) { onTeamClick(match.home.id) }
            Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val score = match.score.current
                Text(
                    if (score == null) match.kickoffTime() else "${score.home} - ${score.away}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (match.status == MatchStatus.SCHEDULED) "Kick-off" else match.statusLabel(),
                    color = if (match.status.isLive) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (match.status.isLive) FontWeight.Bold else FontWeight.Normal,
                )
                val pens = match.score.penalties
                if (pens != null) Text("Pens ${pens.home} - ${pens.away}", style = MaterialTheme.typography.labelMedium)
                val ht = match.score.halfTime
                if (ht != null && match.status != MatchStatus.FIRST_HALF) {
                    Text("HT ${ht.home} - ${ht.away}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HeaderTeam(match.away.name, match.away.logoUrl, Modifier.weight(1f)) { onTeamClick(match.away.id) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(dateTimeFormat.format(match.kickoff.atZone(ZoneId.systemDefault())), match.venue, match.referee?.let { "Ref: $it" })
                .joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (match.status.isLive && lastUpdated != null) {
            Text(
                "Updated ${DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).format(lastUpdated.atZone(ZoneId.systemDefault()))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeaderTeam(name: String, logo: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RemoteImage(logo, 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(name, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------- Summary

@Composable
private fun EventsTab(details: MatchDetails) {
    if (details.events.isEmpty()) {
        CenteredMessage(if (details.match.status == MatchStatus.SCHEDULED) "The match has not started yet" else "No events yet")
        return
    }
    // Newest first while live, chronological once finished.
    val events = if (details.match.status.isLive) details.events.asReversed() else details.events
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(events) { event -> EventRow(event) }
    }
}

@Composable
private fun EventRow(event: MatchEvent) {
    val (icon, title, subtitle) = eventText(event)
    val home = event.side != TeamSide.AWAY
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (home) Arrangement.Start else Arrangement.End,
    ) {
        if (home) {
            Minute(event)
            Text(icon, Modifier.width(32.dp), textAlign = TextAlign.Center)
            EventLabel(title, subtitle, TextAlign.Start)
        } else {
            EventLabel(title, subtitle, TextAlign.End)
            Text(icon, Modifier.width(32.dp), textAlign = TextAlign.Center)
            Minute(event)
        }
    }
}

@Composable
private fun Minute(event: MatchEvent) {
    Text(event.minute.toString(), Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun EventLabel(title: String, subtitle: String?, align: TextAlign) {
    Column(horizontalAlignment = if (align == TextAlign.End) Alignment.End else Alignment.Start) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, textAlign = align)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
    }
}

private fun eventText(event: MatchEvent): Triple<String, String, String?> = when (event) {
    is MatchEvent.Goal -> Triple(
        "⚽",
        event.scorer?.name ?: "Goal",
        when (event.kind) {
            GoalKind.PENALTY -> "Penalty"
            GoalKind.OWN_GOAL -> "Own goal"
            GoalKind.NORMAL -> event.assist?.let { "Assist: ${it.name}" }
        },
    )
    is MatchEvent.MissedPenalty -> Triple("✖", event.player?.name ?: "Penalty", "Missed penalty")
    is MatchEvent.Card -> Triple(
        when (event.kind) {
            CardKind.YELLOW -> "🟨"
            CardKind.SECOND_YELLOW -> "🟨🟥"
            CardKind.RED -> "🟥"
        },
        event.player?.name ?: "Card",
        if (event.kind == CardKind.SECOND_YELLOW) "Second yellow" else null,
    )
    is MatchEvent.Substitution -> Triple("⇄", event.playerIn?.name ?: "Substitution", event.playerOut?.let { "Off: ${it.name}" })
    is MatchEvent.Var -> Triple("VAR", event.decision, event.player?.name)
}

// ---------------------------------------------------------------------------------------------- Stats

@Composable
private fun StatsTab(stats: List<StatLine>) {
    if (stats.isEmpty()) {
        CenteredMessage("No statistics available yet")
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        items(stats) { StatRow(it) }
    }
}

private fun formatStat(value: Double?, percentage: Boolean): String {
    if (value == null) return "0"
    val text = if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(value)
    return if (percentage) "$text%" else text
}

@Composable
private fun StatRow(stat: StatLine) {
    val percentage = stat.type?.isPercentage == true
    val home = stat.home ?: 0.0
    val away = stat.away ?: 0.0
    val total = home + away
    val homeShare = if (total > 0) (home / total).toFloat() else 0.5f
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatStat(stat.home, percentage), Modifier.width(56.dp), fontWeight = if (home > away) FontWeight.Bold else FontWeight.Normal)
            Text(stat.label, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Text(formatStat(stat.away, percentage), Modifier.width(56.dp), textAlign = TextAlign.End, fontWeight = if (away > home) FontWeight.Bold else FontWeight.Normal)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
            if (homeShare > 0f) Box(Modifier.weight(homeShare.coerceAtLeast(0.001f)).height(6.dp).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(2.dp))
            if (homeShare < 1f) Box(Modifier.weight((1f - homeShare).coerceAtLeast(0.001f)).height(6.dp).background(MaterialTheme.colorScheme.tertiary))
        }
    }
}

// ---------------------------------------------------------------------------------------------- Lineups

@Composable
private fun LineupsTab(lineups: List<Lineup>, onPlayerClick: (Long) -> Unit) {
    if (lineups.isEmpty()) {
        CenteredMessage("Lineups are usually published about an hour before kick-off")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        lineups.forEach { lineup ->
            item(key = "h${lineup.team.id}") {
                Column {
                    Row(Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteImage(lineup.team.logoUrl, 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(lineup.team.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        lineup.formation?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    }
                    lineup.coach?.let {
                        Text("Coach: $it", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(lineup.startingXI, key = { "s${lineup.team.id}-${it.player.id}-${it.player.name}" }) { PlayerLine(it, onPlayerClick) }
            if (lineup.substitutes.isNotEmpty()) {
                item(key = "subs${lineup.team.id}") {
                    Text("Substitutes", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.labelLarge)
                }
                items(lineup.substitutes, key = { "b${lineup.team.id}-${it.player.id}-${it.player.name}" }) { PlayerLine(it, onPlayerClick) }
            }
            item(key = "d${lineup.team.id}") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun PlayerLine(entry: LineupPlayer, onPlayerClick: (Long) -> Unit) {
    val id = entry.player.id
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (id != null) it.clickable { onPlayerClick(id) } else it }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.number?.toString() ?: "", Modifier.width(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.player.name, Modifier.weight(1f))
        Text(positionName(entry.position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun positionName(code: String?) = when (code) {
    "G" -> "Goalkeeper"
    "D" -> "Defender"
    "M" -> "Midfielder"
    "F" -> "Forward"
    else -> code.orEmpty()
}

// ---------------------------------------------------------------------------------------------- Table

@Composable
private fun TableTab(match: Match, season: Int, onTeamClick: (Long) -> Unit) {
    val competitionId = match.competition.id
    val loader = rememberLoader("standings/$competitionId/$season") { it.standings(competitionId, season) }
    LoadableContent(loader) { standings ->
        LazyColumn(Modifier.fillMaxSize()) {
            standingsTable(standings, highlightTeamIds = setOf(match.home.id, match.away.id), onTeamClick = onTeamClick)
        }
    }
}
