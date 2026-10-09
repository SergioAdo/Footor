package app.footor.core.apifootball

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire format of API-Football v3 (https://www.api-football.com/documentation-v3).
// Everything is nullable: the provider omits or nulls fields freely, and a missing field must
// never crash a live screen.

@Serializable
internal data class Envelope<T>(
    val errors: JsonElement? = null,
    val results: Int? = null,
    val response: List<T> = emptyList(),
)

@Serializable
internal data class FixtureDto(
    val fixture: FixtureInfoDto,
    val league: LeagueDto? = null,
    val teams: HomeAwayDto<TeamDto>? = null,
    val goals: GoalsDto? = null,
    val score: ScoreDto? = null,
    val events: List<EventDto>? = null,
    val lineups: List<LineupDto>? = null,
    val statistics: List<TeamStatisticsDto>? = null,
)

@Serializable
internal data class FixtureInfoDto(
    val id: Long,
    val referee: String? = null,
    val timestamp: Long? = null,
    val date: String? = null,
    val venue: VenueDto? = null,
    val status: StatusDto? = null,
)

@Serializable
internal data class VenueDto(
    val id: Long? = null,
    val name: String? = null,
    val city: String? = null,
    val address: String? = null,
    val capacity: Int? = null,
)

@Serializable
internal data class StatusDto(
    val long: String? = null,
    val short: String? = null,
    val elapsed: Int? = null,
    val extra: Int? = null,
)

@Serializable
internal data class LeagueDto(
    val id: Long,
    val name: String? = null,
    val country: String? = null,
    val logo: String? = null,
    val flag: String? = null,
    val season: Int? = null,
    val round: String? = null,
    val standings: List<List<StandingDto>>? = null,
)

@Serializable
internal data class HomeAwayDto<T>(val home: T? = null, val away: T? = null)

@Serializable
internal data class TeamDto(
    val id: Long? = null,
    val name: String? = null,
    val logo: String? = null,
    val country: String? = null,
    val founded: Int? = null,
    val code: String? = null,
)

@Serializable
internal data class GoalsDto(val home: Int? = null, val away: Int? = null)

@Serializable
internal data class ScoreDto(
    val halftime: GoalsDto? = null,
    val fulltime: GoalsDto? = null,
    val extratime: GoalsDto? = null,
    val penalty: GoalsDto? = null,
)

@Serializable
internal data class EventDto(
    val time: EventTimeDto? = null,
    val team: TeamDto? = null,
    val player: NamedDto? = null,
    val assist: NamedDto? = null,
    val type: String? = null,
    val detail: String? = null,
    val comments: String? = null,
)

@Serializable
internal data class EventTimeDto(val elapsed: Int? = null, val extra: Int? = null)

@Serializable
internal data class NamedDto(val id: Long? = null, val name: String? = null)

@Serializable
internal data class TeamStatisticsDto(
    val team: TeamDto? = null,
    val statistics: List<StatisticDto> = emptyList(),
)

@Serializable
internal data class StatisticDto(
    val type: String? = null,
    /** Number, string such as "55%" or "1.32", or null. */
    val value: JsonElement? = null,
)

@Serializable
internal data class LineupDto(
    val team: TeamDto? = null,
    val coach: NamedDto? = null,
    val formation: String? = null,
    val startXI: List<LineupEntryDto> = emptyList(),
    val substitutes: List<LineupEntryDto> = emptyList(),
)

@Serializable
internal data class LineupEntryDto(val player: LineupPlayerDto? = null)

@Serializable
internal data class LineupPlayerDto(
    val id: Long? = null,
    val name: String? = null,
    val number: Int? = null,
    val pos: String? = null,
    val grid: String? = null,
)

@Serializable
internal data class StandingsResponseDto(val league: LeagueDto)

@Serializable
internal data class StandingDto(
    val rank: Int? = null,
    val team: TeamDto? = null,
    val points: Int? = null,
    val group: String? = null,
    val form: String? = null,
    val description: String? = null,
    val all: StandingRecordDto? = null,
)

@Serializable
internal data class StandingRecordDto(
    val played: Int? = null,
    val win: Int? = null,
    val draw: Int? = null,
    val lose: Int? = null,
    val goals: StandingGoalsDto? = null,
)

@Serializable
internal data class StandingGoalsDto(
    @SerialName("for") val scored: Int? = null,
    val against: Int? = null,
)

@Serializable
internal data class TeamInfoDto(val team: TeamDto, val venue: VenueDto? = null)

@Serializable
internal data class SquadDto(val team: TeamDto? = null, val players: List<SquadPlayerDto> = emptyList())

@Serializable
internal data class SquadPlayerDto(
    val id: Long? = null,
    val name: String? = null,
    val age: Int? = null,
    val number: Int? = null,
    val position: String? = null,
    val photo: String? = null,
)

@Serializable
internal data class PlayerResponseDto(
    val player: PlayerDto,
    val statistics: List<PlayerStatisticsDto> = emptyList(),
)

@Serializable
internal data class PlayerDto(
    val id: Long,
    val name: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
    val age: Int? = null,
    val birth: BirthDto? = null,
    val nationality: String? = null,
    val height: String? = null,
    val weight: String? = null,
    val injured: Boolean? = null,
    val photo: String? = null,
)

@Serializable
internal data class BirthDto(val date: String? = null, val place: String? = null, val country: String? = null)

@Serializable
internal data class PlayerStatisticsDto(
    val team: TeamDto? = null,
    val league: LeagueDto? = null,
    val games: PlayerGamesDto? = null,
    val shots: TotalOnDto? = null,
    val goals: PlayerGoalsDto? = null,
    val passes: PassesDto? = null,
    val tackles: TacklesDto? = null,
    val duels: DuelsDto? = null,
    val dribbles: DribblesDto? = null,
    val cards: CardsDto? = null,
)

@Serializable
internal data class PlayerGamesDto(
    // Sic: the provider spells it this way.
    @SerialName("appearences") val appearances: Int? = null,
    val minutes: Int? = null,
    val position: String? = null,
    val rating: String? = null,
)

@Serializable
internal data class TotalOnDto(val total: Int? = null, val on: Int? = null)

@Serializable
internal data class PlayerGoalsDto(
    val total: Int? = null,
    val conceded: Int? = null,
    val assists: Int? = null,
    val saves: Int? = null,
)

@Serializable
internal data class PassesDto(
    val total: Int? = null,
    val key: Int? = null,
    /** Sent as a number or a numeric string depending on the endpoint. */
    val accuracy: JsonElement? = null,
)

@Serializable
internal data class TacklesDto(val total: Int? = null, val blocks: Int? = null, val interceptions: Int? = null)

@Serializable
internal data class DuelsDto(val total: Int? = null, val won: Int? = null)

@Serializable
internal data class DribblesDto(val attempts: Int? = null, val success: Int? = null)

@Serializable
internal data class CardsDto(val yellow: Int? = null, val yellowred: Int? = null, val red: Int? = null)
