package app.footor.core.live

import app.footor.core.demo.DemoDataSource
import app.footor.core.model.Competition
import app.footor.core.model.Match
import app.footor.core.model.MatchClock
import app.footor.core.model.MatchDetails
import app.footor.core.model.MatchStatus
import app.footor.core.model.Score
import app.footor.core.model.ScorePair
import app.footor.core.model.Team
import app.footor.core.source.DataSourceException
import app.footor.core.source.RateLimitedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTrackerTest {

    private val start = Instant.parse("2026-10-09T19:00:00Z")
    private fun TestScope.virtualNow(): Instant = start.plusMillis(currentTime)

    private fun match(status: MatchStatus, kickoff: Instant) = Match(
        id = 1, kickoff = kickoff, status = status, clock = MatchClock(null),
        competition = Competition(1, "L"), home = Team(1, "H"), away = Team(2, "A"), score = Score(null),
    )

    @Test
    fun `polling pace follows match state`() {
        val policy = PollingPolicy()
        val now = start
        assertEquals(15.seconds, policy.delayFor(match(MatchStatus.SECOND_HALF, now), now))
        assertEquals(30.seconds, policy.delayFor(match(MatchStatus.HALF_TIME, now), now))
        assertEquals(1.minutes, policy.delayFor(match(MatchStatus.SCHEDULED, now.plusSeconds(600)), now))
        // 20 minutes out: sleep until the 15-minute window opens.
        assertEquals(5.minutes, policy.delayFor(match(MatchStatus.SCHEDULED, now.plusSeconds(1200)), now))
        assertEquals(10.minutes, policy.delayFor(match(MatchStatus.SCHEDULED, now.plusSeconds(86_400)), now))
        // A finished match does not keep a day's scoreboard polling fast.
        assertEquals(
            1.minutes,
            policy.nextDelay(listOf(match(MatchStatus.FINISHED, now), match(MatchStatus.SCHEDULED, now.plusSeconds(60))), now),
        )
        assertEquals(10.minutes, policy.nextDelay(listOf(match(MatchStatus.FINISHED, now)), now))
    }

    @Test
    fun `error backoff grows and is longer when rate limited`() {
        val policy = PollingPolicy()
        val error = DataSourceException("boom")
        assertEquals(listOf(5.seconds, 10.seconds, 20.seconds, 40.seconds, 80.seconds, 2.minutes, 2.minutes), (1..7).map { policy.errorBackoff(error, it) })
        assertEquals(2.minutes, policy.errorBackoff(RateLimitedException("slow down"), 2))
    }

    @Test
    fun `demo live match is followed goal by goal including the VAR reversal`() = runTest {
        // One match minute per virtual second.
        val source = DemoDataSource(now = { virtualNow() }, secondsPerMinute = 1)
        val tracker = LiveTracker(source, MatchReconciler(), PollingPolicy(live = 1.seconds, halfTime = 1.seconds), now = { virtualNow() })

        val states = mutableListOf<LiveState<MatchDetails>>()
        val job = launch { tracker.match(DemoDataSource.LIVE_MATCH_ID).collect { states += it } }
        advanceTimeBy(1_000_000)
        runCurrent()
        job.cancel()

        val updates = states.flatMap { it.updates }
        val goals = updates.filterIsInstance<MatchUpdate.GoalScored>().map { it.score }
        // The demo starts at 20' with 1-0 already on the board.
        assertEquals(listOf(ScorePair(1, 1), ScorePair(2, 1), ScorePair(2, 1)), goals)
        assertTrue(updates.filterIsInstance<MatchUpdate.GoalCancelled>().single().byVar)
        assertEquals(1, updates.filterIsInstance<MatchUpdate.RedCard>().size)
        val final = states.last().data!!.match
        assertEquals(MatchStatus.FINISHED, final.status)
        assertEquals(ScorePair(2, 1), final.score.current)
        // Polling stops on its own after the post-match refreshes.
        assertEquals(1 + PollingPolicy().refreshesAfterFinish, states.count { it.data?.match?.status == MatchStatus.FINISHED })
    }

    @Test
    fun `manual refresh cuts the wait short`() = runTest {
        val source = DemoDataSource(now = { virtualNow() })
        val tracker = LiveTracker(source, now = { virtualNow() })
        val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        val collected = mutableListOf<Long>()
        val timer = launch {
            tracker.scoreboard(LocalDate.of(2026, 10, 9), ZoneOffset.UTC, refresh).take(2).collect { collected += currentTime }
        }
        runCurrent()
        advanceTimeBy(1_000)
        refresh.tryEmit(Unit)
        runCurrent()
        timer.join()

        assertEquals(listOf(0L, 1_000L), collected)
    }
}
