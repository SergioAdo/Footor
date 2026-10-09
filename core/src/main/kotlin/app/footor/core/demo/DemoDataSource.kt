package app.footor.core.demo

import app.footor.core.model.CardKind
import app.footor.core.model.Competition
import app.footor.core.model.GoalKind
import app.footor.core.model.Lineup
import app.footor.core.model.LineupPlayer
import app.footor.core.model.Match
import app.footor.core.model.MatchClock
import app.footor.core.model.MatchDetails
import app.footor.core.model.MatchEvent
import app.footor.core.model.MatchStatus
import app.footor.core.model.PlayerProfile
import app.footor.core.model.PlayerRef
import app.footor.core.model.PlayerSeasonStats
import app.footor.core.model.Score
import app.footor.core.model.ScorePair
import app.footor.core.model.SquadPlayer
import app.footor.core.model.StandingRow
import app.footor.core.model.Standings
import app.footor.core.model.StandingsGroup
import app.footor.core.model.StatLine
import app.footor.core.model.StatType
import app.footor.core.model.Team
import app.footor.core.model.TeamProfile
import app.footor.core.model.TeamSide
import app.footor.core.source.FootballDataSource
import app.footor.core.source.NotFoundException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Offline provider with fictional teams. It plays one match in real time from the moment it is created
 * (a goal, a VAR-cancelled goal, cards, substitutions) so the whole live pipeline can be exercised
 * without an API key.
 */
class DemoDataSource(
    private val now: () -> Instant = Instant::now,
    /** Real seconds per match minute; lower it to watch a whole demo match quickly. */
    private val secondsPerMinute: Long = 60,
) : FootballDataSource {

    override val name = "Demo (fictional data)"

    private val league = Competition(1, "Demo League", "Footland", season = 2026, round = "Matchday 9")

    private val riverside = Team(101, "Riverside FC")
    private val northbridge = Team(102, "Northbridge United")
    private val harbour = Team(103, "Harbour City")
    private val ashford = Team(104, "Ashford Athletic")
    private val teams = listOf(riverside, northbridge, harbour, ashford)

    // The live demo match kicks off 20 match-minutes before the source is created.
    private val liveKickoff: Instant = now().minusSeconds(20 * secondsPerMinute)

    private fun player(team: Team, n: Int, name: String) = PlayerRef(team.id * 100 + n, name)

    private val riversidePlayers = listOf(
        "A. Okafor", "L. Brandt", "M. Silva", "J. Kowalski", "T. Moreau", "R. Haddad",
        "K. Lindqvist", "D. Ferreira", "S. Novak", "E. Mensah", "P. Castillo",
    ).mapIndexed { i, n -> player(riverside, i + 1, n) }

    private val northbridgePlayers = listOf(
        "G. Petrov", "H. Yamada", "B. O'Neill", "F. Rossi", "C. Dubois", "N. Abara",
        "V. Jensen", "I. Costa", "O. Sato", "W. Adeyemi", "Z. Horvat",
    ).mapIndexed { i, n -> player(northbridge, i + 1, n) }

    /** Everything that happens in the live demo match, revealed as the clock passes each minute. */
    private val script: List<MatchEvent> = listOf(
        MatchEvent.Goal(MatchClock(12), TeamSide.HOME, riverside.id, riversidePlayers[9], riversidePlayers[6], GoalKind.NORMAL),
        MatchEvent.Card(MatchClock(27), TeamSide.AWAY, northbridge.id, northbridgePlayers[5], CardKind.YELLOW),
        MatchEvent.Goal(MatchClock(38), TeamSide.AWAY, northbridge.id, northbridgePlayers[9], null, GoalKind.PENALTY),
        MatchEvent.Substitution(MatchClock(58), TeamSide.HOME, riverside.id, PlayerRef(riverside.id * 100 + 12, "U. Bakker"), riversidePlayers[7]),
        MatchEvent.Goal(MatchClock(67), TeamSide.HOME, riverside.id, riversidePlayers[10], null, GoalKind.NORMAL),
        MatchEvent.Var(MatchClock(69), TeamSide.HOME, riverside.id, riversidePlayers[10], "Goal cancelled - offside"),
        MatchEvent.Goal(MatchClock(81), TeamSide.HOME, riverside.id, riversidePlayers[8], riversidePlayers[9], GoalKind.NORMAL),
        MatchEvent.Card(MatchClock(90, 2), TeamSide.AWAY, northbridge.id, northbridgePlayers[3], CardKind.RED),
    )

    private data class LiveProgress(val status: MatchStatus, val clock: MatchClock)

    private fun liveProgress(): LiveProgress {
        val minutes = Duration.between(liveKickoff, now()).seconds / secondsPerMinute
        return when {
            minutes < 0 -> LiveProgress(MatchStatus.SCHEDULED, MatchClock(null))
            minutes < 47 -> LiveProgress(MatchStatus.FIRST_HALF, firstHalfClock(minutes.toInt() + 1))
            minutes < 62 -> LiveProgress(MatchStatus.HALF_TIME, MatchClock(45))
            minutes < 111 -> LiveProgress(MatchStatus.SECOND_HALF, secondHalfClock(minutes.toInt() - 62 + 46))
            else -> LiveProgress(MatchStatus.FINISHED, MatchClock(90))
        }
    }

    private fun firstHalfClock(minute: Int) = if (minute > 45) MatchClock(45, minute - 45) else MatchClock(minute)
    private fun secondHalfClock(minute: Int) = if (minute > 90) MatchClock(90, minute - 90) else MatchClock(minute)

    private fun liveEvents(clock: MatchClock, status: MatchStatus): List<MatchEvent> {
        if (status == MatchStatus.SCHEDULED) return emptyList()
        if (status.isFinished) return script
        return script.filter { it.minute.progress <= clock.progress }
    }

    private fun scoreFrom(events: List<MatchEvent>): ScorePair {
        val cancelled = events.filterIsInstance<MatchEvent.Var>().filter { it.cancelsGoal }
        val goals = events.filterIsInstance<MatchEvent.Goal>().filterNot { goal ->
            cancelled.any { it.teamId == goal.teamId && it.player == goal.scorer }
        }
        return ScorePair(goals.count { it.side == TeamSide.HOME }, goals.count { it.side == TeamSide.AWAY })
    }

    private fun liveMatch(): Match {
        val progress = liveProgress()
        val events = liveEvents(progress.clock, progress.status)
        val score = if (progress.status == MatchStatus.SCHEDULED) null else scoreFrom(events)
        return Match(
            id = LIVE_MATCH_ID,
            kickoff = liveKickoff,
            status = progress.status,
            clock = progress.clock,
            competition = league,
            home = riverside,
            away = northbridge,
            score = Score(
                current = score,
                halfTime = if (progress.status.isFinished || progress.status == MatchStatus.HALF_TIME || progress.status == MatchStatus.SECOND_HALF) {
                    scoreFrom(script.filter { it.minute.elapsed!! <= 45 })
                } else null,
            ),
            venue = "Riverside Park, Riverside",
            referee = "A. Referee",
        )
    }

    private fun staticMatch(id: Long, home: Team, away: Team, kickoff: Instant): Match {
        val finished = kickoff.plus(Duration.ofMinutes(110)) < now()
        return Match(
            id = id,
            kickoff = kickoff,
            status = if (finished) MatchStatus.FINISHED else MatchStatus.SCHEDULED,
            clock = MatchClock(if (finished) 90 else null),
            competition = league,
            home = home,
            away = away,
            score = if (finished) Score(ScorePair((id % 3).toInt(), (id % 2).toInt()), ScorePair((id % 2).toInt(), 0)) else Score(null),
            venue = "${home.name} Stadium",
        )
    }

    private fun matchesFor(date: LocalDate, zone: ZoneId): List<Match> {
        val today = LocalDate.now(zone)
        val noon = date.atTime(12, 0).atZone(zone).toInstant()
        val dayIndex = date.toEpochDay()
        val others = listOf(
            staticMatch(dayIndex * 10 + 2, harbour, ashford, noon.plus(Duration.ofHours(3))),
            staticMatch(dayIndex * 10 + 3, ashford, riverside, noon.plus(Duration.ofHours(8))),
        )
        return if (date == today) listOf(liveMatch()) + others else others
    }

    override suspend fun matchesOn(date: LocalDate, zone: ZoneId) = matchesFor(date, zone)

    override suspend fun liveMatches() = listOf(liveMatch()).filter { it.status.isLive }

    override suspend fun match(matchId: Long): Match = when (matchId) {
        LIVE_MATCH_ID -> liveMatch()
        else -> matchesFor(LocalDate.ofEpochDay(matchId / 10), ZoneId.systemDefault()).firstOrNull { it.id == matchId }
            ?: throw NotFoundException("Demo match $matchId not found")
    }

    override suspend fun matchDetails(matchId: Long): MatchDetails {
        val match = match(matchId)
        if (matchId != LIVE_MATCH_ID) return MatchDetails(match, emptyList(), emptyList(), emptyList())
        val events = liveEvents(match.clock, match.status)
        val minute = (match.clock.elapsed ?: 0).coerceAtLeast(1).toDouble()
        val factor = minute / 90.0
        return MatchDetails(
            match = match,
            events = events,
            statistics = if (match.status == MatchStatus.SCHEDULED) emptyList() else listOf(
                StatLine(StatType.POSSESSION, StatType.POSSESSION.label, 54.0, 46.0),
                StatLine(StatType.EXPECTED_GOALS, StatType.EXPECTED_GOALS.label, round2(2.1 * factor), round2(1.2 * factor)),
                StatLine(StatType.TOTAL_SHOTS, StatType.TOTAL_SHOTS.label, (15 * factor).toInt().toDouble(), (9 * factor).toInt().toDouble()),
                StatLine(StatType.SHOTS_ON_TARGET, StatType.SHOTS_ON_TARGET.label, (6 * factor).toInt().toDouble(), (3 * factor).toInt().toDouble()),
                StatLine(StatType.CORNERS, StatType.CORNERS.label, (7 * factor).toInt().toDouble(), (4 * factor).toInt().toDouble()),
                StatLine(StatType.FOULS, StatType.FOULS.label, (10 * factor).toInt().toDouble(), (13 * factor).toInt().toDouble()),
                StatLine(StatType.PASS_ACCURACY, StatType.PASS_ACCURACY.label, 87.0, 81.0),
            ),
            lineups = listOf(
                demoLineup(riverside, riversidePlayers, "4-3-3", "M. Coach"),
                demoLineup(northbridge, northbridgePlayers, "4-2-3-1", "N. Manager"),
            ),
        )
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    private fun demoLineup(team: Team, players: List<PlayerRef>, formation: String, coach: String): Lineup {
        val positions = listOf("G", "D", "D", "D", "D", "M", "M", "M", "F", "F", "F")
        return Lineup(
            team = team,
            formation = formation,
            coach = coach,
            startingXI = players.mapIndexed { i, p -> LineupPlayer(p, i + 1, positions[i], null) },
            substitutes = listOf(LineupPlayer(PlayerRef(team.id * 100 + 12, if (team == riverside) "U. Bakker" else "Q. Laine"), 12, "M", null)),
        )
    }

    override suspend fun standings(competitionId: Long, season: Int): Standings {
        if (competitionId != league.id) throw NotFoundException("Demo competition $competitionId not found")
        val rows = listOf(
            StandingRow(1, riverside, 8, 6, 1, 1, 18, 7, 19, "WWDWL", "Promotion"),
            StandingRow(2, harbour, 8, 5, 2, 1, 14, 8, 17, "WDWWW", "Promotion"),
            StandingRow(3, northbridge, 8, 3, 3, 2, 11, 10, 12, "DLWDW", null),
            StandingRow(4, ashford, 8, 0, 2, 6, 4, 22, 2, "LLDLL", "Relegation"),
        )
        return Standings(league, listOf(StandingsGroup(league.name, rows)))
    }

    override suspend fun team(teamId: Long): TeamProfile {
        val team = teams.firstOrNull { it.id == teamId } ?: throw NotFoundException("Demo team $teamId not found")
        return TeamProfile(team, "Footland", 1880 + (teamId % 50).toInt(), "${team.name} Stadium", team.name.substringBefore(' '), 25_000)
    }

    override suspend fun squad(teamId: Long): List<SquadPlayer> {
        val players = when (teamId) {
            riverside.id -> riversidePlayers
            northbridge.id -> northbridgePlayers
            else -> emptyList()
        }
        val positions = listOf("Goalkeeper") + List(4) { "Defender" } + List(3) { "Midfielder" } + List(3) { "Attacker" }
        return players.mapIndexed { i, p -> SquadPlayer(p, 20 + i, i + 1, positions[i], null) }
    }

    override suspend fun teamMatches(teamId: Long, last: Int, next: Int): List<Match> {
        val today = LocalDate.now(ZoneId.systemDefault())
        return (-3L..3L).flatMap { matchesFor(today.plusDays(it), ZoneId.systemDefault()) }
            .filter { it.home.id == teamId || it.away.id == teamId }
            .distinctBy { it.id }
    }

    override suspend fun player(playerId: Long, season: Int): PlayerProfile {
        val ref = (riversidePlayers + northbridgePlayers).firstOrNull { it.id == playerId }
            ?: throw NotFoundException("Demo player $playerId not found")
        val team = if (playerId / 100 == riverside.id) riverside else northbridge
        val seed = (playerId % 7).toInt()
        return PlayerProfile(
            id = playerId,
            name = ref.name,
            firstName = null,
            lastName = ref.name.substringAfter(". "),
            age = 21 + seed,
            birthDate = null,
            nationality = "Footland",
            height = "${175 + seed * 2} cm",
            weight = "${70 + seed} kg",
            photoUrl = null,
            injured = false,
            position = null,
            seasons = listOf(
                PlayerSeasonStats(
                    team = team, competition = league, appearances = 8, minutes = 600 + seed * 20,
                    goals = seed, assists = seed / 2, rating = 6.8 + seed / 10.0,
                    shotsTotal = 4 + seed * 2, shotsOnTarget = 2 + seed, keyPasses = 5 + seed, passAccuracy = 78 + seed,
                    tackles = 10 - seed, interceptions = 6, duelsWon = 30 + seed, duelsTotal = 55, dribblesSuccess = 4 + seed,
                    yellowCards = 1, redCards = 0, saves = null, goalsConceded = null,
                ),
            ),
        )
    }

    companion object {
        const val LIVE_MATCH_ID = 1L
    }
}
