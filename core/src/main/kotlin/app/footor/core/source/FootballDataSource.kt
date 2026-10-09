package app.footor.core.source

import app.footor.core.model.Match
import app.footor.core.model.MatchDetails
import app.footor.core.model.PlayerProfile
import app.footor.core.model.SquadPlayer
import app.footor.core.model.Standings
import app.footor.core.model.TeamProfile
import java.time.LocalDate
import java.time.ZoneId

/**
 * A provider of football data. The app only talks to this interface, so providers can be swapped
 * (or combined) without touching the UI.
 */
interface FootballDataSource {
    val name: String

    suspend fun matchesOn(date: LocalDate, zone: ZoneId): List<Match>

    suspend fun liveMatches(): List<Match>

    suspend fun match(matchId: Long): Match

    suspend fun matchDetails(matchId: Long): MatchDetails

    suspend fun standings(competitionId: Long, season: Int): Standings

    suspend fun team(teamId: Long): TeamProfile

    suspend fun squad(teamId: Long): List<SquadPlayer>

    suspend fun teamMatches(teamId: Long, last: Int, next: Int): List<Match>

    suspend fun player(playerId: Long, season: Int): PlayerProfile
}

/** Any failure talking to a provider, with a message suitable for display. */
open class DataSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

class RateLimitedException(message: String) : DataSourceException(message)

class AuthenticationException(message: String) : DataSourceException(message)

class NotFoundException(message: String) : DataSourceException(message)
