package app.footor.core.apifootball

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

internal object ApiFootballMapper {

    fun status(short: String?): MatchStatus = when (short?.uppercase()) {
        "TBD", "NS" -> MatchStatus.SCHEDULED
        "1H" -> MatchStatus.FIRST_HALF
        "HT" -> MatchStatus.HALF_TIME
        "2H" -> MatchStatus.SECOND_HALF
        "ET" -> MatchStatus.EXTRA_TIME
        "BT" -> MatchStatus.BREAK_BEFORE_EXTRA_TIME
        "P" -> MatchStatus.PENALTIES
        "LIVE" -> MatchStatus.LIVE_UNKNOWN_PERIOD
        "INT" -> MatchStatus.INTERRUPTED
        "SUSP" -> MatchStatus.SUSPENDED
        "FT" -> MatchStatus.FINISHED
        "AET" -> MatchStatus.FINISHED_AFTER_EXTRA_TIME
        "PEN" -> MatchStatus.FINISHED_AFTER_PENALTIES
        "AWD", "WO" -> MatchStatus.AWARDED
        "PST" -> MatchStatus.POSTPONED
        "CANC" -> MatchStatus.CANCELLED
        "ABD" -> MatchStatus.ABANDONED
        else -> MatchStatus.UNKNOWN
    }

    fun match(dto: FixtureDto): Match {
        val info = dto.fixture
        return Match(
            id = info.id,
            kickoff = kickoff(info),
            status = status(info.status?.short),
            clock = MatchClock(info.status?.elapsed, info.status?.extra),
            competition = competition(dto.league),
            home = team(dto.teams?.home),
            away = team(dto.teams?.away),
            score = Score(
                current = pair(dto.goals),
                halfTime = pair(dto.score?.halftime),
                fullTime = pair(dto.score?.fulltime),
                extraTime = pair(dto.score?.extratime),
                penalties = pair(dto.score?.penalty),
            ),
            venue = listOfNotNull(info.venue?.name, info.venue?.city).joinToString(", ").ifEmpty { null },
            referee = info.referee,
        )
    }

    fun details(dto: FixtureDto): MatchDetails {
        val match = match(dto)
        val homeId = match.home.id
        return MatchDetails(
            match = match,
            events = dto.events.orEmpty()
                .mapNotNull { event(it, homeId, match.away.id) }
                .sortedBy { it.minute.progress },
            statistics = statistics(dto.statistics.orEmpty(), homeId),
            lineups = dto.lineups.orEmpty()
                .map(::lineup)
                .sortedBy { if (it.team.id == homeId) 0 else 1 },
        )
    }

    private fun kickoff(info: FixtureInfoDto): Instant =
        info.timestamp?.let(Instant::ofEpochSecond)
            ?: info.date?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            ?: Instant.EPOCH

    private fun pair(goals: GoalsDto?): ScorePair? {
        val home = goals?.home ?: return null
        val away = goals.away ?: return null
        return ScorePair(home, away)
    }

    fun team(dto: TeamDto?): Team = Team(
        id = dto?.id ?: -1,
        name = dto?.name ?: "Unknown",
        logoUrl = dto?.logo,
    )

    fun competition(dto: LeagueDto?): Competition = Competition(
        id = dto?.id ?: -1,
        name = dto?.name ?: "Unknown competition",
        country = dto?.country,
        logoUrl = dto?.logo,
        season = dto?.season,
        round = dto?.round,
    )

    private fun player(dto: NamedDto?): PlayerRef? {
        val name = dto?.name?.takeIf { it.isNotBlank() } ?: return null
        return PlayerRef(dto.id, name)
    }

    fun event(dto: EventDto, homeId: Long, awayId: Long): MatchEvent? {
        val minute = MatchClock(dto.time?.elapsed, dto.time?.extra)
        val teamId = dto.team?.id
        val side = when (teamId) {
            null -> null
            homeId -> TeamSide.HOME
            awayId -> TeamSide.AWAY
            else -> null
        }
        val detail = dto.detail.orEmpty()
        return when (dto.type?.lowercase()) {
            "goal" -> when {
                detail.contains("missed", ignoreCase = true) ->
                    MatchEvent.MissedPenalty(minute, side, teamId, player(dto.player))
                else -> MatchEvent.Goal(
                    minute = minute,
                    side = side,
                    teamId = teamId,
                    scorer = player(dto.player),
                    assist = player(dto.assist),
                    kind = when {
                        detail.contains("own", ignoreCase = true) -> GoalKind.OWN_GOAL
                        detail.contains("penalty", ignoreCase = true) -> GoalKind.PENALTY
                        else -> GoalKind.NORMAL
                    },
                )
            }
            "card" -> MatchEvent.Card(
                minute = minute,
                side = side,
                teamId = teamId,
                player = player(dto.player),
                kind = when {
                    detail.contains("second", ignoreCase = true) -> CardKind.SECOND_YELLOW
                    detail.contains("red", ignoreCase = true) -> CardKind.RED
                    else -> CardKind.YELLOW
                },
            )
            // API-Football reports the outgoing player in `player` and the incoming one in `assist`.
            "subst" -> MatchEvent.Substitution(minute, side, teamId, playerIn = player(dto.assist), playerOut = player(dto.player))
            "var" -> MatchEvent.Var(minute, side, teamId, player(dto.player), detail)
            else -> null
        }
    }

    fun statType(label: String): StatType? = when (label.lowercase().replace('_', ' ').trim()) {
        "ball possession" -> StatType.POSSESSION
        "expected goals" -> StatType.EXPECTED_GOALS
        "total shots" -> StatType.TOTAL_SHOTS
        "shots on goal" -> StatType.SHOTS_ON_TARGET
        "shots off goal" -> StatType.SHOTS_OFF_TARGET
        "blocked shots" -> StatType.BLOCKED_SHOTS
        "shots insidebox" -> StatType.SHOTS_INSIDE_BOX
        "shots outsidebox" -> StatType.SHOTS_OUTSIDE_BOX
        "corner kicks" -> StatType.CORNERS
        "offsides" -> StatType.OFFSIDES
        "fouls" -> StatType.FOULS
        "yellow cards" -> StatType.YELLOW_CARDS
        "red cards" -> StatType.RED_CARDS
        "goalkeeper saves" -> StatType.GOALKEEPER_SAVES
        "total passes" -> StatType.TOTAL_PASSES
        "passes accurate" -> StatType.ACCURATE_PASSES
        "passes %" -> StatType.PASS_ACCURACY
        "goals prevented" -> StatType.GOALS_PREVENTED
        else -> null
    }

    /** Parses 12, "55%", "1.32" and null alike. */
    fun number(value: JsonElement?): Double? {
        if (value == null || value is JsonNull || value !is JsonPrimitive) return null
        return value.content.trim().removeSuffix("%").trim().toDoubleOrNull()
    }

    fun statistics(teams: List<TeamStatisticsDto>, homeId: Long): List<StatLine> {
        if (teams.isEmpty()) return emptyList()
        val home = teams.firstOrNull { it.team?.id == homeId } ?: teams.first()
        val away = teams.firstOrNull { it !== home }
        val awayByLabel = away?.statistics.orEmpty().associateBy { it.type.orEmpty() }
        val labels = (home.statistics.map { it.type.orEmpty() } + awayByLabel.keys).distinct().filter { it.isNotBlank() }
        val homeByLabel = home.statistics.associateBy { it.type.orEmpty() }
        return labels
            .map { label ->
                StatLine(
                    type = statType(label),
                    label = statType(label)?.label ?: label,
                    home = number(homeByLabel[label]?.value),
                    away = number(awayByLabel[label]?.value),
                )
            }
            // Known stats first in a stable order, unknown ones after in provider order.
            .sortedBy { it.type?.ordinal ?: Int.MAX_VALUE }
    }

    private fun lineupPlayer(dto: LineupEntryDto): LineupPlayer? {
        val p = dto.player ?: return null
        return LineupPlayer(
            player = PlayerRef(p.id, p.name ?: return null),
            number = p.number,
            position = p.pos,
            grid = p.grid,
        )
    }

    fun lineup(dto: LineupDto): Lineup = Lineup(
        team = team(dto.team),
        formation = dto.formation,
        coach = dto.coach?.name,
        startingXI = dto.startXI.mapNotNull(::lineupPlayer),
        substitutes = dto.substitutes.mapNotNull(::lineupPlayer),
    )

    fun standings(dto: StandingsResponseDto): Standings = Standings(
        competition = competition(dto.league),
        groups = dto.league.standings.orEmpty().map { rows ->
            StandingsGroup(
                name = rows.firstOrNull()?.group,
                rows = rows.map { row ->
                    StandingRow(
                        rank = row.rank ?: 0,
                        team = team(row.team),
                        played = row.all?.played ?: 0,
                        won = row.all?.win ?: 0,
                        drawn = row.all?.draw ?: 0,
                        lost = row.all?.lose ?: 0,
                        goalsFor = row.all?.goals?.scored ?: 0,
                        goalsAgainst = row.all?.goals?.against ?: 0,
                        points = row.points ?: 0,
                        form = row.form,
                        description = row.description,
                    )
                }.sortedBy { it.rank },
            )
        },
    )

    fun teamProfile(dto: TeamInfoDto): TeamProfile = TeamProfile(
        team = team(dto.team),
        country = dto.team.country,
        founded = dto.team.founded,
        venueName = dto.venue?.name,
        venueCity = dto.venue?.city,
        venueCapacity = dto.venue?.capacity,
    )

    fun squad(dto: SquadDto): List<SquadPlayer> = dto.players.mapNotNull { p ->
        SquadPlayer(
            player = PlayerRef(p.id, p.name ?: return@mapNotNull null),
            age = p.age,
            number = p.number,
            position = p.position,
            photoUrl = p.photo,
        )
    }

    fun player(dto: PlayerResponseDto): PlayerProfile {
        val p = dto.player
        val seasons = dto.statistics.map { s ->
            PlayerSeasonStats(
                team = team(s.team),
                competition = competition(s.league),
                appearances = s.games?.appearances,
                minutes = s.games?.minutes,
                goals = s.goals?.total,
                assists = s.goals?.assists,
                rating = s.games?.rating?.toDoubleOrNull(),
                shotsTotal = s.shots?.total,
                shotsOnTarget = s.shots?.on,
                keyPasses = s.passes?.key,
                passAccuracy = number(s.passes?.accuracy)?.toInt(),
                tackles = s.tackles?.total,
                interceptions = s.tackles?.interceptions,
                duelsWon = s.duels?.won,
                duelsTotal = s.duels?.total,
                dribblesSuccess = s.dribbles?.success,
                yellowCards = s.cards?.yellow,
                redCards = (s.cards?.red ?: 0) + (s.cards?.yellowred ?: 0),
                saves = s.goals?.saves,
                goalsConceded = s.goals?.conceded,
            )
        }
        return PlayerProfile(
            id = p.id,
            name = p.name ?: listOfNotNull(p.firstname, p.lastname).joinToString(" "),
            firstName = p.firstname,
            lastName = p.lastname,
            age = p.age,
            birthDate = p.birth?.date,
            nationality = p.nationality,
            height = p.height,
            weight = p.weight,
            photoUrl = p.photo,
            injured = p.injured,
            position = dto.statistics.firstNotNullOfOrNull { it.games?.position },
            // Most-played competition first.
            seasons = seasons.sortedByDescending { it.minutes ?: 0 },
        )
    }
}
