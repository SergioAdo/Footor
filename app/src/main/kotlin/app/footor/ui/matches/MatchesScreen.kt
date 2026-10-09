package app.footor.ui.matches

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.footor.core.model.Match
import app.footor.ui.components.CenteredMessage
import app.footor.ui.components.LiveContent
import app.footor.ui.components.RemoteImage
import app.footor.ui.components.StatusText
import app.footor.ui.components.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MatchesViewModel(container: AppContainer) : ViewModel() {
    private val zone: ZoneId = ZoneId.systemDefault()
    val today: LocalDate = LocalDate.now(zone)
    val dates: List<LocalDate> = (-3L..3L).map { today.plusDays(it) }

    private val _selectedDate = MutableStateFlow(today)
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LiveState<List<Match>>> = combine(container.tracker, _selectedDate) { tracker, date -> tracker to date }
        .flatMapLatest { (tracker, date) -> tracker.scoreboard(date, zone, refreshRequests).onStart { emit(LiveState()) } }
        // Polling stops 5 s after the screen leaves the foreground, which saves quota and battery.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveState())

    fun select(date: LocalDate) {
        _selectedDate.value = date
    }

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(onMatchClick: (Long) -> Unit, onSettingsClick: () -> Unit) {
    val container = appContainer()
    val vm: MatchesViewModel = viewModel { MatchesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selectedDate.collectAsStateWithLifecycle()
    val source by container.source.collectAsStateWithLifecycle()
    var liveOnly by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // Any new emission (data or error) ends the pull-to-refresh spinner.
    LaunchedEffect(state) { refreshing = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Footor")
                        Text(source.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = vm.dates.indexOf(selected), edgePadding = 8.dp) {
                vm.dates.forEach { date ->
                    Tab(
                        selected = date == selected,
                        onClick = { vm.select(date) },
                        text = { Text(dateLabel(date, vm.today), maxLines = 1) },
                    )
                }
            }
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                FilterChip(selected = liveOnly, onClick = { liveOnly = !liveOnly }, label = { Text("Live now") })
            }
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    vm.refresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LiveContent(state, onRetry = vm::refresh) { matches ->
                    val shown = if (liveOnly) matches.filter { it.status.isLive } else matches
                    MatchList(shown, onMatchClick)
                }
            }
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

private fun dateLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> dayFormat.format(date)
}

@Composable
private fun MatchList(matches: List<Match>, onMatchClick: (Long) -> Unit) {
    if (matches.isEmpty()) {
        CenteredMessage("No matches")
        return
    }
    // Competitions with live games first, then by name; inside a competition by kick-off.
    val groups = matches.groupBy { it.competition.id }.values
        .sortedWith(compareBy({ group -> if (group.any { it.status.isLive }) 0 else 1 }, { it.first().competition.name }))
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { group ->
            val competition = group.first().competition
            item(key = "c${competition.id}") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RemoteImage(competition.logoUrl, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOfNotNull(competition.country, competition.name).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            items(group.sortedBy { it.kickoff }, key = { it.id }) { match ->
                MatchRow(match, onClick = { onMatchClick(match.id) })
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
fun MatchRow(match: Match, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusText(match, Modifier.width(48.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TeamLine(match.home.name, match.home.logoUrl, match.score.current?.home, bold = (match.score.current?.let { it.home > it.away } == true))
            TeamLine(match.away.name, match.away.logoUrl, match.score.current?.away, bold = (match.score.current?.let { it.away > it.home } == true))
        }
    }
}

@Composable
private fun TeamLine(name: String, logo: String?, goals: Int?, bold: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RemoteImage(logo, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            goals?.toString() ?: "",
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.End,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
