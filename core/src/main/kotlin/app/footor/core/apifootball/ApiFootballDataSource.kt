package app.footor.core.apifootball

import app.footor.core.cache.TtlCache
import app.footor.core.model.Match
import app.footor.core.model.MatchDetails
import app.footor.core.model.PlayerProfile
import app.footor.core.model.SquadPlayer
import app.footor.core.model.Standings
import app.footor.core.model.TeamProfile
import app.footor.core.source.AuthenticationException
import app.footor.core.source.DataSourceException
import app.footor.core.source.FootballDataSource
import app.footor.core.source.NotFoundException
import app.footor.core.source.RateLimitedException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [FootballDataSource] backed by API-Football v3. Users bring their own key (free tier: 100 requests/day),
 * so no secret ships with the app.
 */
class ApiFootballDataSource(
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : FootballDataSource {

    override val name = "API-Football"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val cache = TtlCache<String, Any>()

    /** Requests left today according to the last response, or null if unknown. */
    @Volatile
    var remainingDailyRequests: Int? = null
        private set

    override suspend fun matchesOn(date: LocalDate, zone: ZoneId): List<Match> {
        // Today's list contains live games, so keep it nearly as fresh as the live feed.
        val ttl = if (date == LocalDate.now(zone)) LIVE_TTL else 10.minutes
        return get("fixtures", mapOf("date" to date.toString(), "timezone" to zone.id), ttl, FixtureDto.serializer())
            .map(ApiFootballMapper::match)
    }

    override suspend fun liveMatches(): List<Match> =
        get("fixtures", mapOf("live" to "all"), LIVE_TTL, FixtureDto.serializer()).map(ApiFootballMapper::match)

    override suspend fun match(matchId: Long): Match = ApiFootballMapper.match(fixture(matchId))

    override suspend fun matchDetails(matchId: Long): MatchDetails = ApiFootballMapper.details(fixture(matchId))

    // A fixture looked up by id carries events, lineups and statistics, so one call fills the whole match screen.
    private suspend fun fixture(matchId: Long): FixtureDto =
        get("fixtures", mapOf("id" to matchId.toString()), LIVE_TTL, FixtureDto.serializer()).firstOrNull()
            ?: throw NotFoundException("Match $matchId not found")

    override suspend fun standings(competitionId: Long, season: Int): Standings =
        get(
            "standings",
            mapOf("league" to competitionId.toString(), "season" to season.toString()),
            5.minutes,
            StandingsResponseDto.serializer(),
        ).firstOrNull()?.let(ApiFootballMapper::standings)
            ?: throw NotFoundException("No standings for competition $competitionId ($season)")

    override suspend fun team(teamId: Long): TeamProfile =
        get("teams", mapOf("id" to teamId.toString()), 12.hours, TeamInfoDto.serializer()).firstOrNull()
            ?.let(ApiFootballMapper::teamProfile)
            ?: throw NotFoundException("Team $teamId not found")

    override suspend fun squad(teamId: Long): List<SquadPlayer> =
        get("players/squads", mapOf("team" to teamId.toString()), 6.hours, SquadDto.serializer())
            .flatMap(ApiFootballMapper::squad)

    override suspend fun teamMatches(teamId: Long, last: Int, next: Int): List<Match> = coroutineScope {
        val previous = async {
            get("fixtures", mapOf("team" to teamId.toString(), "last" to last.toString()), 2.minutes, FixtureDto.serializer())
        }
        val upcoming = async {
            get("fixtures", mapOf("team" to teamId.toString(), "next" to next.toString()), 30.minutes, FixtureDto.serializer())
        }
        (previous.await() + upcoming.await()).map(ApiFootballMapper::match).distinctBy { it.id }.sortedBy { it.kickoff }
    }

    override suspend fun player(playerId: Long, season: Int): PlayerProfile =
        get(
            "players",
            mapOf("id" to playerId.toString(), "season" to season.toString()),
            1.hours,
            PlayerResponseDto.serializer(),
        ).firstOrNull()?.let(ApiFootballMapper::player)
            ?: throw NotFoundException("Player $playerId has no data for $season")

    private suspend fun <T> get(path: String, params: Map<String, String>, ttl: Duration, serializer: KSerializer<T>): List<T> {
        val url = baseUrl.newBuilder().addPathSegments(path).apply {
            params.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        @Suppress("UNCHECKED_CAST")
        return cache.getOrLoad(url.toString(), ttl) { fetch(url, serializer) } as List<T>
    }

    private suspend fun <T> fetch(url: HttpUrl, serializer: KSerializer<T>): List<T> {
        val request = Request.Builder()
            .url(url)
            .header("x-apisports-key", apiKey)
            .build()
        val body = httpClient.newCall(request).await().use { response ->
            response.header("x-ratelimit-requests-remaining")?.toIntOrNull()?.let { remainingDailyRequests = it }
            when (response.code) {
                401, 403 -> throw AuthenticationException("API key rejected by $name")
                429 -> throw RateLimitedException("$name rate limit reached, slowing down")
            }
            if (!response.isSuccessful) throw DataSourceException("$name returned HTTP ${response.code}")
            response.body?.string() ?: throw DataSourceException("$name returned an empty response")
        }
        val envelope = try {
            json.decodeFromString(Envelope.serializer(serializer), body)
        } catch (e: IllegalArgumentException) {
            throw DataSourceException("Unexpected response from $name", e)
        }
        checkErrors(envelope)
        return envelope.response
    }

    /** API-Football answers HTTP 200 with an `errors` object (or array) for quota and key problems. */
    private fun checkErrors(envelope: Envelope<*>) {
        val messages: Map<String, String> = when (val errors = envelope.errors) {
            is JsonObject -> errors.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
            is JsonArray -> errors.mapIndexed { i, v -> i.toString() to ((v as? JsonPrimitive)?.content ?: v.toString()) }.toMap()
            else -> emptyMap()
        }
        if (messages.isEmpty()) return
        val text = messages.values.joinToString("; ")
        when {
            "token" in messages || text.contains("key", ignoreCase = true) -> throw AuthenticationException(text)
            "requests" in messages || "rateLimit" in messages -> throw RateLimitedException(text)
            else -> throw DataSourceException(text)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://v3.football.api-sports.io/"

        /** API-Football refreshes live fixtures roughly every 15 seconds; caching for less only burns quota. */
        val LIVE_TTL = 15.seconds
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, value, _ -> value.close() }
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(DataSourceException("Network error: ${e.message ?: e.javaClass.simpleName}", e))
        }
    })
}
