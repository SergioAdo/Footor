package app.footor.core.live

import app.footor.core.model.CardKind
import app.footor.core.model.Match
import app.footor.core.model.MatchEvent
import app.footor.core.model.MatchStatus
import app.footor.core.model.ScorePair
import app.footor.core.model.TeamSide

/** Something a user cares about that changed between two accepted snapshots of a match. */
sealed interface MatchUpdate {
    val match: Match

    data class GoalScored(override val match: Match, val side: TeamSide, val score: ScorePair) : MatchUpdate

    /** A previously shown goal was taken back (VAR, or the provider correcting itself). */
    data class GoalCancelled(override val match: Match, val side: TeamSide, val score: ScorePair, val byVar: Boolean) : MatchUpdate

    data class StatusChanged(override val match: Match, val from: MatchStatus, val to: MatchStatus) : MatchUpdate

    data class RedCard(override val match: Match, val card: MatchEvent.Card) : MatchUpdate
}

/**
 * Decides which provider snapshots to believe.
 *
 * Feeds are served through caches and load balancers, so a poll can return data older than what is
 * already on screen. Showing it would make a goal "disappear" for a few seconds — the most visible
 * accuracy bug a live-score app can have. The rules:
 *
 *  1. A score increase is accepted immediately: stale data can only ever lower a score.
 *  2. Otherwise, a snapshot that moves the match backwards in time (earlier period, or an earlier
 *     minute in the same period) is stale and discarded.
 *  3. A score decrease is accepted immediately when a new VAR "goal cancelled" event explains it;
 *     otherwise only after [confirmationsForReversal] consecutive snapshots agree on it.
 */
class MatchReconciler(private val confirmationsForReversal: Int = 2) {

    private class State(
        val match: Match,
        val redCards: Int,
        val varCancellations: Int,
        val pendingReversal: ScorePair?,
        val pendingCount: Int,
    )

    data class Result(val match: Match, val updates: List<MatchUpdate>, val discardedAsStale: Boolean)

    private val states = HashMap<Long, State>()

    @Synchronized
    fun reconcile(incoming: Match, events: List<MatchEvent>? = null): Result {
        val previous = states[incoming.id]
        if (previous == null) {
            states[incoming.id] = State(incoming, redCards(events), varCancellations(events), null, 0)
            return Result(incoming, emptyList(), discardedAsStale = false)
        }
        val old = previous.match

        if (isStale(old, incoming)) {
            return Result(old, emptyList(), discardedAsStale = true)
        }

        val oldScore = old.score.current
        val newScore = incoming.score.current
        val decreased = oldScore != null && (newScore == null || newScore.home < oldScore.home || newScore.away < oldScore.away)

        if (decreased) {
            // Only a VAR cancellation we have not seen before explains a new decrease.
            val explainedByVar = events != null && varCancellations(events) > previous.varCancellations
            val pendingCount = if (previous.pendingReversal == newScore) previous.pendingCount + 1 else 1
            if (!explainedByVar && pendingCount < confirmationsForReversal) {
                // Keep showing the old score (and the old clock, to stay self-consistent) until confirmed.
                states[incoming.id] = State(old, previous.redCards, previous.varCancellations, newScore, pendingCount)
                return Result(old, emptyList(), discardedAsStale = true)
            }
            return accept(previous, incoming, events, cancelledByVar = explainedByVar)
        }
        return accept(previous, incoming, events, cancelledByVar = false)
    }

    @Synchronized
    fun forget(matchId: Long) {
        states.remove(matchId)
    }

    private fun accept(previous: State, incoming: Match, events: List<MatchEvent>?, cancelledByVar: Boolean): Result {
        val old = previous.match
        val updates = mutableListOf<MatchUpdate>()
        val oldScore = old.score.current
        val newScore = incoming.score.current

        if (old.status != incoming.status) {
            updates += MatchUpdate.StatusChanged(incoming, old.status, incoming.status)
        }
        if (newScore != null) {
            val before = oldScore ?: ScorePair(0, 0)
            repeat((newScore.home - before.home).coerceAtLeast(0)) {
                updates += MatchUpdate.GoalScored(incoming, TeamSide.HOME, newScore)
            }
            repeat((newScore.away - before.away).coerceAtLeast(0)) {
                updates += MatchUpdate.GoalScored(incoming, TeamSide.AWAY, newScore)
            }
            if (oldScore != null) {
                repeat((oldScore.home - newScore.home).coerceAtLeast(0)) {
                    updates += MatchUpdate.GoalCancelled(incoming, TeamSide.HOME, newScore, cancelledByVar)
                }
                repeat((oldScore.away - newScore.away).coerceAtLeast(0)) {
                    updates += MatchUpdate.GoalCancelled(incoming, TeamSide.AWAY, newScore, cancelledByVar)
                }
            }
        }

        // Events are only present when details were fetched; without them keep the previous count.
        val redCards = if (events != null) redCards(events) else previous.redCards
        if (events != null && redCards > previous.redCards) {
            events.filterIsInstance<MatchEvent.Card>()
                .filter { it.kind != CardKind.YELLOW }
                .takeLast(redCards - previous.redCards)
                .forEach { updates += MatchUpdate.RedCard(incoming, it) }
        }

        val varCancellations = if (events != null) varCancellations(events) else previous.varCancellations
        states[incoming.id] = State(incoming, redCards, varCancellations, null, 0)
        return Result(incoming, updates, discardedAsStale = false)
    }

    private fun varCancellations(events: List<MatchEvent>?): Int =
        events.orEmpty().count { it is MatchEvent.Var && it.cancelsGoal }

    private fun redCards(events: List<MatchEvent>?): Int =
        events.orEmpty().count { it is MatchEvent.Card && it.kind != CardKind.YELLOW }

    companion object {
        /**
         * Position of a status on the match timeline, or null for statuses that can occur at any point
         * (interruptions, suspensions, unknown) and therefore say nothing about ordering.
         */
        internal fun timelineRank(status: MatchStatus): Int? = when (status) {
            MatchStatus.SCHEDULED -> 0
            MatchStatus.FIRST_HALF -> 1
            MatchStatus.HALF_TIME -> 2
            MatchStatus.SECOND_HALF -> 3
            MatchStatus.BREAK_BEFORE_EXTRA_TIME -> 4
            MatchStatus.EXTRA_TIME -> 5
            MatchStatus.BREAK_BEFORE_PENALTIES -> 6
            MatchStatus.PENALTIES -> 7
            MatchStatus.FINISHED,
            MatchStatus.FINISHED_AFTER_EXTRA_TIME,
            MatchStatus.FINISHED_AFTER_PENALTIES,
            MatchStatus.AWARDED,
            MatchStatus.ABANDONED -> 10
            MatchStatus.LIVE_UNKNOWN_PERIOD,
            MatchStatus.INTERRUPTED,
            MatchStatus.SUSPENDED,
            MatchStatus.POSTPONED,
            MatchStatus.CANCELLED,
            MatchStatus.UNKNOWN -> null
        }

        internal fun isStale(old: Match, incoming: Match): Boolean {
            val oldScore = old.score.current
            val newScore = incoming.score.current
            if (oldScore != null && newScore != null &&
                newScore.home >= oldScore.home && newScore.away >= oldScore.away && newScore != oldScore
            ) {
                return false
            }
            val oldRank = timelineRank(old.status)
            val newRank = timelineRank(incoming.status)
            if (oldRank != null && newRank != null) {
                if (newRank < oldRank) return true
                if (newRank > oldRank) return false
            }
            // Same period (or not comparable): the clock never runs backwards within a period.
            if (old.status == incoming.status && old.status.isLive && old.clock.elapsed != null && incoming.clock.elapsed != null) {
                return incoming.clock.progress < old.clock.progress
            }
            return false
        }
    }
}
