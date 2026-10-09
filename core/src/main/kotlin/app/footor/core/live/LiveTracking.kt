package app.footor.core.live

import app.footor.core.model.Match
import app.footor.core.model.MatchDetails
import app.footor.core.model.MatchStatus
import app.footor.core.source.DataSourceException
import app.footor.core.source.FootballDataSource
import app.footor.core.source.RateLimitedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** What a screen renders: the latest trusted data plus how fresh it is and what just changed. */
data class LiveState<T>(
    val data: T? = null,
    /** Changes detected by the last poll (goals, status changes, red cards). */
    val updates: List<MatchUpdate> = emptyList(),
    /** Error from the last poll; [data] still holds the last good value. */
    val error: DataSourceException? = null,
    val lastUpdated: Instant? = null,
)

/**
 * How often to poll. Live matches are polled at the provider's own refresh rate; anything else is
 * polled just often enough to catch kick-off, which keeps free-tier quotas usable for a whole day.
 */
data class PollingPolicy(
    val live: Duration = 15.seconds,
    val halfTime: Duration = 30.seconds,
    /** Used from [kickoffWindow] before kick-off until the match goes live. */
    val nearKickoff: Duration = 1.minutes,
    val kickoffWindow: Duration = 15.minutes,
    val idle: Duration = 10.minutes,
    /** Providers keep correcting stats and scorers for a while after the final whistle. */
    val afterFinish: Duration = 2.minutes,
    val refreshesAfterFinish: Int = 3,
    val maxErrorBackoff: Duration = 2.minutes,
    val rateLimitBackoff: Duration = 1.minutes,
) {
    fun nextDelay(matches: Collection<Match>, now: Instant): Duration {
        // In a list, finished matches no longer drive the pace; the next live or upcoming one does.
        return matches.filterNot { it.status.isFinished }.minOfOrNull { delayFor(it, now) } ?: idle
    }

    fun delayFor(match: Match, now: Instant): Duration = when {
        match.status == MatchStatus.HALF_TIME || match.status == MatchStatus.BREAK_BEFORE_EXTRA_TIME -> halfTime
        match.status.isLive -> live
        match.status == MatchStatus.SCHEDULED -> {
            val untilKickoff = java.time.Duration.between(now, match.kickoff).toKotlinDuration()
            when {
                untilKickoff <= kickoffWindow -> nearKickoff
                // Wake up just as the kick-off window opens.
                else -> minOf(idle, untilKickoff - kickoffWindow)
            }
        }
        match.status.isFinished -> afterFinish
        else -> idle
    }

    fun errorBackoff(error: DataSourceException, consecutiveFailures: Int): Duration =
        if (error is RateLimitedException) {
            rateLimitBackoff * consecutiveFailures.coerceAtMost(5)
        } else {
            minOf(maxErrorBackoff, 5.seconds * (1 shl (consecutiveFailures - 1).coerceIn(0, 5)))
        }
}

/**
 * Turns one-shot provider calls into self-pacing live streams. One [MatchReconciler] is shared so the
 * scoreboard and the match screen always agree on the score.
 */
class LiveTracker(
    private val source: FootballDataSource,
    private val reconciler: MatchReconciler = MatchReconciler(),
    private val policy: PollingPolicy = PollingPolicy(),
    private val now: () -> Instant = Instant::now,
) {

    /** All matches on [date], re-polled while any of them is live or about to start. */
    fun scoreboard(date: LocalDate, zone: ZoneId, refreshRequests: Flow<Unit> = emptyFlow()): Flow<LiveState<List<Match>>> =
        poll(
            refreshRequests = refreshRequests,
            fetch = {
                source.matchesOn(date, zone).map { incoming ->
                    val result = reconciler.reconcile(incoming)
                    result.match to result.updates
                }.let { pairs -> pairs.map { it.first } to pairs.flatMap { it.second } }
            },
            isFinished = { false },
            nextDelay = { matches, _ -> policy.nextDelay(matches, now()) },
        )

    /** Full detail of one match, stopping a few refreshes after the final whistle. */
    fun match(matchId: Long, refreshRequests: Flow<Unit> = emptyFlow()): Flow<LiveState<MatchDetails>> {
        var previous: MatchDetails? = null
        return poll(
            refreshRequests = refreshRequests,
            fetch = {
                val incoming = source.matchDetails(matchId)
                val result = reconciler.reconcile(incoming.match, incoming.events)
                val shown = if (result.discardedAsStale) {
                    previous ?: incoming.copy(match = result.match)
                } else {
                    incoming
                }
                previous = shown
                shown to result.updates
            },
            isFinished = { it.match.status.isFinished },
            nextDelay = { details, finishedPolls ->
                if (details.match.status.isFinished && finishedPolls > policy.refreshesAfterFinish) null
                else policy.delayFor(details.match, now())
            },
        )
    }

    private fun <T : Any> poll(
        refreshRequests: Flow<Unit>,
        fetch: suspend () -> Pair<T, List<MatchUpdate>>,
        isFinished: (T) -> Boolean,
        /** Returns null to stop polling. The Int counts successful polls that saw a finished match. */
        nextDelay: (T, Int) -> Duration?,
    ): Flow<LiveState<T>> = flow {
        var state = LiveState<T>()
        var failures = 0
        var finishedPolls = 0
        while (true) {
            val wait: Duration? = try {
                val (data, updates) = fetch()
                failures = 0
                if (isFinished(data)) finishedPolls++
                state = LiveState(data, updates, null, now())
                emit(state)
                nextDelay(data, finishedPolls)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DataSourceException) {
                failures++
                state = state.copy(updates = emptyList(), error = e)
                emit(state)
                policy.errorBackoff(e, failures)
            }
            if (wait == null) return@flow
            // A manual refresh request cuts the wait short.
            withTimeoutOrNull(wait) { refreshRequests.firstOrNull() ?: awaitCancellation() }
        }
    }
}
