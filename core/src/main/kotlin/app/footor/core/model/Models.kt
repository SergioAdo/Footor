package app.footor.core.model

import java.time.Instant

/** Lifecycle of a match. [isLive] statuses are the ones the live tracker polls aggressively. */
enum class MatchStatus(val isLive: Boolean, val isFinished: Boolean) {
    SCHEDULED(isLive = false, isFinished = false),
    FIRST_HALF(isLive = true, isFinished = false),
    HALF_TIME(isLive = true, isFinished = false),
    SECOND_HALF(isLive = true, isFinished = false),
    BREAK_BEFORE_EXTRA_TIME(isLive = true, isFinished = false),
    EXTRA_TIME(isLive = true, isFinished = false),
    BREAK_BEFORE_PENALTIES(isLive = true, isFinished = false),
    PENALTIES(isLive = true, isFinished = false),
    /** Live but the provider did not say which period (e.g. API-Football "LIVE"). */
    LIVE_UNKNOWN_PERIOD(isLive = true, isFinished = false),
    INTERRUPTED(isLive = true, isFinished = false),
    SUSPENDED(isLive = false, isFinished = false),
    FINISHED(isLive = false, isFinished = true),
    FINISHED_AFTER_EXTRA_TIME(isLive = false, isFinished = true),
    FINISHED_AFTER_PENALTIES(isLive = false, isFinished = true),
    AWARDED(isLive = false, isFinished = true),
    POSTPONED(isLive = false, isFinished = false),
    CANCELLED(isLive = false, isFinished = false),
    ABANDONED(isLive = false, isFinished = true),
    UNKNOWN(isLive = false, isFinished = false),
}

data class Team(
    val id: Long,
    val name: String,
    val logoUrl: String? = null,
)

data class Competition(
    val id: Long,
    val name: String,
    val country: String? = null,
    val logoUrl: String? = null,
    val season: Int? = null,
    val round: String? = null,
)

data class ScorePair(val home: Int, val away: Int) {
    override fun toString() = "$home - $away"
}

data class Score(
    /** Goals as currently counted (regulation + extra time, excluding the shootout). */
    val current: ScorePair?,
    val halfTime: ScorePair? = null,
    val fullTime: ScorePair? = null,
    val extraTime: ScorePair? = null,
    val penalties: ScorePair? = null,
)

data class MatchClock(
    /** Minute shown on the clock, e.g. 45 for "45+3'". */
    val elapsed: Int?,
    /** Added/stoppage minutes, e.g. 3 for "45+3'". */
    val extra: Int? = null,
) {
    /** Comparable progress value; stoppage time sorts after the base minute. */
    val progress: Int get() = (elapsed ?: 0) * 100 + (extra ?: 0)

    override fun toString(): String = when {
        elapsed == null -> ""
        extra != null && extra > 0 -> "$elapsed+$extra'"
        else -> "$elapsed'"
    }
}

data class Match(
    val id: Long,
    val kickoff: Instant,
    val status: MatchStatus,
    val clock: MatchClock,
    val competition: Competition,
    val home: Team,
    val away: Team,
    val score: Score,
    val venue: String? = null,
    val referee: String? = null,
)

enum class TeamSide { HOME, AWAY }

data class PlayerRef(val id: Long?, val name: String)

sealed interface MatchEvent {
    val minute: MatchClock
    val side: TeamSide?
    val teamId: Long?

    data class Goal(
        override val minute: MatchClock,
        override val side: TeamSide?,
        override val teamId: Long?,
        val scorer: PlayerRef?,
        val assist: PlayerRef?,
        val kind: GoalKind,
    ) : MatchEvent

    data class MissedPenalty(
        override val minute: MatchClock,
        override val side: TeamSide?,
        override val teamId: Long?,
        val player: PlayerRef?,
    ) : MatchEvent

    data class Card(
        override val minute: MatchClock,
        override val side: TeamSide?,
        override val teamId: Long?,
        val player: PlayerRef?,
        val kind: CardKind,
    ) : MatchEvent

    data class Substitution(
        override val minute: MatchClock,
        override val side: TeamSide?,
        override val teamId: Long?,
        val playerIn: PlayerRef?,
        val playerOut: PlayerRef?,
    ) : MatchEvent

    data class Var(
        override val minute: MatchClock,
        override val side: TeamSide?,
        override val teamId: Long?,
        val player: PlayerRef?,
        val decision: String,
    ) : MatchEvent {
        /** True when VAR overturned a goal, which the live tracker uses to explain a score decrease. */
        val cancelsGoal: Boolean
            get() = decision.contains("goal cancelled", ignoreCase = true) ||
                decision.contains("goal disallowed", ignoreCase = true)
    }
}

enum class GoalKind { NORMAL, PENALTY, OWN_GOAL }
enum class CardKind { YELLOW, SECOND_YELLOW, RED }

/** Well-known statistics, so the UI can order and label them consistently across providers. */
enum class StatType(val label: String, val isPercentage: Boolean = false) {
    POSSESSION("Ball possession", isPercentage = true),
    EXPECTED_GOALS("Expected goals (xG)"),
    TOTAL_SHOTS("Total shots"),
    SHOTS_ON_TARGET("Shots on target"),
    SHOTS_OFF_TARGET("Shots off target"),
    BLOCKED_SHOTS("Blocked shots"),
    SHOTS_INSIDE_BOX("Shots inside box"),
    SHOTS_OUTSIDE_BOX("Shots outside box"),
    CORNERS("Corner kicks"),
    OFFSIDES("Offsides"),
    FOULS("Fouls"),
    YELLOW_CARDS("Yellow cards"),
    RED_CARDS("Red cards"),
    GOALKEEPER_SAVES("Goalkeeper saves"),
    TOTAL_PASSES("Total passes"),
    ACCURATE_PASSES("Accurate passes"),
    PASS_ACCURACY("Pass accuracy", isPercentage = true),
    GOALS_PREVENTED("Goals prevented"),
}

data class StatLine(
    val type: StatType?,
    /** Provider label, kept for statistics we do not know about. */
    val label: String,
    val home: Double?,
    val away: Double?,
)

data class LineupPlayer(
    val player: PlayerRef,
    val number: Int?,
    val position: String?,
    /** "row:column" formation grid from the provider, when known. */
    val grid: String?,
)

data class Lineup(
    val team: Team,
    val formation: String?,
    val coach: String?,
    val startingXI: List<LineupPlayer>,
    val substitutes: List<LineupPlayer>,
)

data class MatchDetails(
    val match: Match,
    val events: List<MatchEvent>,
    val statistics: List<StatLine>,
    val lineups: List<Lineup>,
)

data class StandingRow(
    val rank: Int,
    val team: Team,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
    val points: Int,
    val form: String?,
    val description: String?,
) {
    val goalDifference: Int get() = goalsFor - goalsAgainst
}

data class StandingsGroup(val name: String?, val rows: List<StandingRow>)

data class Standings(val competition: Competition, val groups: List<StandingsGroup>)

data class TeamProfile(
    val team: Team,
    val country: String?,
    val founded: Int?,
    val venueName: String?,
    val venueCity: String?,
    val venueCapacity: Int?,
)

data class SquadPlayer(
    val player: PlayerRef,
    val age: Int?,
    val number: Int?,
    val position: String?,
    val photoUrl: String?,
)

data class PlayerSeasonStats(
    val team: Team,
    val competition: Competition,
    val appearances: Int?,
    val minutes: Int?,
    val goals: Int?,
    val assists: Int?,
    val rating: Double?,
    val shotsTotal: Int?,
    val shotsOnTarget: Int?,
    val keyPasses: Int?,
    val passAccuracy: Int?,
    val tackles: Int?,
    val interceptions: Int?,
    val duelsWon: Int?,
    val duelsTotal: Int?,
    val dribblesSuccess: Int?,
    val yellowCards: Int?,
    val redCards: Int?,
    val saves: Int?,
    val goalsConceded: Int?,
)

data class PlayerProfile(
    val id: Long,
    val name: String,
    val firstName: String?,
    val lastName: String?,
    val age: Int?,
    val birthDate: String?,
    val nationality: String?,
    val height: String?,
    val weight: String?,
    val photoUrl: String?,
    val injured: Boolean?,
    val position: String?,
    val seasons: List<PlayerSeasonStats>,
)
