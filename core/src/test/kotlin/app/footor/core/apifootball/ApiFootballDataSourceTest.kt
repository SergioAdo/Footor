package app.footor.core.apifootball

import app.footor.core.model.CardKind
import app.footor.core.model.GoalKind
import app.footor.core.model.MatchClock
import app.footor.core.model.MatchEvent
import app.footor.core.model.MatchStatus
import app.footor.core.model.ScorePair
import app.footor.core.model.StatType
import app.footor.core.model.TeamSide
import app.footor.core.source.AuthenticationException
import app.footor.core.source.RateLimitedException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class ApiFootballDataSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var source: ApiFootballDataSource

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        source = ApiFootballDataSource(apiKey = "test-key", baseUrl = server.url("/"))
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun resource(name: String) =
        checkNotNull(javaClass.getResource("/apifootball/$name")) { "missing $name" }.readText()

    private fun enqueue(name: String, headers: Map<String, String> = emptyMap()) {
        val response = MockResponse().setBody(resource(name))
        headers.forEach { (k, v) -> response.addHeader(k, v) }
        server.enqueue(response)
    }

    @Test
    fun `match details map score, status, events, stats and lineups`(): Unit = runBlocking {
        enqueue("fixture_live.json", mapOf("x-ratelimit-requests-remaining" to "97"))

        val details = source.matchDetails(868021)

        val request = server.takeRequest()
        assertEquals("/fixtures?id=868021", request.path)
        assertEquals("test-key", request.getHeader("x-apisports-key"))
        assertEquals(97, source.remainingDailyRequests)

        val match = details.match
        assertEquals(MatchStatus.SECOND_HALF, match.status)
        assertEquals(MatchClock(67, null), match.clock)
        assertEquals(ScorePair(2, 1), match.score.current)
        assertEquals(ScorePair(1, 1), match.score.halfTime)
        assertNull(match.score.fullTime)
        assertEquals("Manchester United", match.home.name)
        assertEquals("Old Trafford, Manchester", match.venue)

        // Events come back unordered from the provider and must be sorted, stoppage time included.
        assertEquals(listOf(12, 30, 45, 52, 60, 64), details.events.map { it.minute.elapsed })
        val penalty = assertIs<MatchEvent.Goal>(details.events[0])
        assertEquals(GoalKind.PENALTY, penalty.kind)
        assertEquals(TeamSide.AWAY, penalty.side)
        assertNull(penalty.assist)
        assertEquals("45+2'", details.events[2].minute.toString())
        assertEquals(CardKind.YELLOW, assertIs<MatchEvent.Card>(details.events[1]).kind)
        val sub = assertIs<MatchEvent.Substitution>(details.events[4])
        assertEquals("D. Jota", sub.playerIn?.name)
        assertEquals("D. Nunez", sub.playerOut?.name)
        assert(assertIs<MatchEvent.Var>(details.events[5]).cancelsGoal)

        // Statistics are oriented home/away even though the provider listed the away team first.
        val possession = details.statistics.first { it.type == StatType.POSSESSION }
        assertEquals(42.0, possession.home)
        assertEquals(58.0, possession.away)
        val xg = details.statistics.first { it.type == StatType.EXPECTED_GOALS }
        assertEquals(1.87, xg.home)
        assertEquals(1.32, xg.away)
        assertNull(details.statistics.first { it.type == StatType.RED_CARDS }.home)
        // Unknown statistics are kept, after the known ones.
        assertEquals("Some New Stat", details.statistics.last().label)

        assertEquals(listOf(33L, 40L), details.lineups.map { it.team.id })
        assertEquals("3-4-2-1", details.lineups[0].formation)
    }

    @Test
    fun `standings are sorted by rank`(): Unit = runBlocking {
        enqueue("standings.json")

        val standings = source.standings(39, 2026)

        assertEquals("/standings?league=39&season=2026", server.takeRequest().path)
        val rows = standings.groups.single().rows
        assertEquals(listOf("Manchester City", "Liverpool"), rows.map { it.team.name })
        assertEquals(11, rows[0].goalDifference)
    }

    @Test
    fun `player profile handles string and numeric fields`(): Unit = runBlocking {
        enqueue("player.json")

        val player = source.player(306, 2026)

        assertEquals("Mohamed Salah", player.name)
        assertEquals("Attacker", player.position)
        // Most minutes first.
        assertEquals("Premier League", player.seasons[0].competition.name)
        assertEquals(79, player.seasons[0].passAccuracy)
        assertEquals(81, player.seasons[1].passAccuracy)
        assertEquals(7.45, player.seasons[1].rating)
        assertEquals(7, player.seasons[0].appearances)
    }

    @Test
    fun `missing key error in a 200 response is an authentication failure`(): Unit = runBlocking {
        enqueue("error_token.json")
        assertFailsWith<AuthenticationException> { source.liveMatches() }
    }

    @Test
    fun `daily quota error is a rate limit`(): Unit = runBlocking {
        enqueue("error_quota.json")
        assertFailsWith<RateLimitedException> { source.liveMatches() }
    }

    @Test
    fun `http 429 is a rate limit`(): Unit = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertFailsWith<RateLimitedException> { source.liveMatches() }
    }

    @Test
    fun `identical requests within the ttl share one network call`(): Unit = runBlocking {
        enqueue("fixture_live.json")

        val results = (1..5).map { async { source.match(868021) } }.awaitAll()
        source.match(868021)

        assertEquals(1, server.requestCount)
        assert(results.all { it.score.current == ScorePair(2, 1) })
    }
}
