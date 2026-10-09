package app.footor.core.live

import app.footor.core.model.CardKind
import app.footor.core.model.Competition
import app.footor.core.model.MatchClock
import app.footor.core.model.Match
import app.footor.core.model.MatchEvent
import app.footor.core.model.MatchStatus
import app.footor.core.model.Score
import app.footor.core.model.ScorePair
import app.footor.core.model.Team
import app.footor.core.model.TeamSide
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MatchReconcilerTest {

    private fun match(status: MatchStatus, minute: Int?, home: Int?, away: Int?, extra: Int? = null) = Match(
        id = 7,
        kickoff = Instant.parse("2026-10-09T19:00:00Z"),
        status = status,
        clock = MatchClock(minute, extra),
        competition = Competition(1, "League"),
        home = Team(1, "Home"),
        away = Team(2, "Away"),
        score = Score(if (home != null && away != null) ScorePair(home, away) else null),
    )

    @Test
    fun `goal is reported as soon as it appears`() {
        val reconciler = MatchReconciler()
        reconciler.reconcile(match(MatchStatus.FIRST_HALF, 10, 0, 0))

        val result = reconciler.reconcile(match(MatchStatus.FIRST_HALF, 11, 1, 0))

        assertEquals(ScorePair(1, 0), result.match.score.current)
        val goal = assertIs<MatchUpdate.GoalScored>(result.updates.single())
        assertEquals(TeamSide.HOME, goal.side)
    }

    @Test
    fun `kick-off reports status change and opening score without fake goals`() {
        val reconciler = MatchReconciler()
        reconciler.reconcile(match(MatchStatus.SCHEDULED, null, null, null))

        val result = reconciler.reconcile(match(MatchStatus.FIRST_HALF, 1, 0, 0))

        assertEquals(listOf<MatchUpdate>(MatchUpdate.StatusChanged(result.match, MatchStatus.SCHEDULED, MatchStatus.FIRST_HALF)), result.updates)
    }

    @Test
    fun `stale snapshot from an earlier minute is ignored`() {
        val reconciler = MatchReconciler()
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 70, 1, 0))

        val result = reconciler.reconcile(match(MatchStatus.SECOND_HALF, 68, 0, 0))

        assertTrue(result.discardedAsStale)
        assertEquals(ScorePair(1, 0), result.match.score.current)
        assertTrue(result.updates.isEmpty())
    }

    @Test
    fun `stale snapshot from an earlier period is ignored`() {
        val reconciler = MatchReconciler()
        reconciler.reconcile(match(MatchStatus.HALF_TIME, 45, 1, 1))

        val result = reconciler.reconcile(match(MatchStatus.FIRST_HALF, 45, 1, 1, extra = 4))

        assertTrue(result.discardedAsStale)
        assertEquals(MatchStatus.HALF_TIME, result.match.status)
    }

    @Test
    fun `stoppage time counts as progress`() {
        val reconciler = MatchReconciler()
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 90, 1, 1, extra = 2))

        assertTrue(reconciler.reconcile(match(MatchStatus.SECOND_HALF, 90, 1, 1, extra = 1)).discardedAsStale)
        assertFalse(reconciler.reconcile(match(MatchStatus.SECOND_HALF, 90, 1, 1, extra = 3)).discardedAsStale)
    }

    @Test
    fun `a new goal is accepted even if the provider clock went backwards`() {
        val reconciler = MatchReconciler()
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 47, 0, 0))

        val result = reconciler.reconcile(match(MatchStatus.SECOND_HALF, 46, 0, 1))

        assertFalse(result.discardedAsStale)
        assertEquals(ScorePair(0, 1), result.match.score.current)
    }

    @Test
    fun `unexplained score decrease needs confirmation`() {
        val reconciler = MatchReconciler(confirmationsForReversal = 2)
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 60, 2, 0))

        val first = reconciler.reconcile(match(MatchStatus.SECOND_HALF, 61, 1, 0))
        assertEquals(ScorePair(2, 0), first.match.score.current)
        assertTrue(first.updates.isEmpty())

        val second = reconciler.reconcile(match(MatchStatus.SECOND_HALF, 61, 1, 0))
        assertEquals(ScorePair(1, 0), second.match.score.current)
        val cancelled = assertIs<MatchUpdate.GoalCancelled>(second.updates.single())
        assertEquals(TeamSide.HOME, cancelled.side)
        assertFalse(cancelled.byVar)
    }

    @Test
    fun `a single blip back to the old score is not shown`() {
        val reconciler = MatchReconciler(confirmationsForReversal = 2)
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 60, 1, 0))

        assertEquals(ScorePair(1, 0), reconciler.reconcile(match(MatchStatus.SECOND_HALF, 60, 0, 0)).match.score.current)
        val back = reconciler.reconcile(match(MatchStatus.SECOND_HALF, 61, 1, 0))
        assertEquals(ScorePair(1, 0), back.match.score.current)
        assertTrue(back.updates.isEmpty(), "no duplicate goal notification")
    }

    @Test
    fun `VAR cancellation is applied immediately`() {
        val reconciler = MatchReconciler(confirmationsForReversal = 3)
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 66, 1, 1))
        val varEvent = MatchEvent.Var(MatchClock(67), TeamSide.AWAY, 2, null, "Goal cancelled")

        val result = reconciler.reconcile(match(MatchStatus.SECOND_HALF, 68, 1, 0), listOf(varEvent))

        assertEquals(ScorePair(1, 0), result.match.score.current)
        assertTrue(assertIs<MatchUpdate.GoalCancelled>(result.updates.single()).byVar)
    }

    @Test
    fun `new red cards are reported once`() {
        val reconciler = MatchReconciler()
        val yellow = MatchEvent.Card(MatchClock(20), TeamSide.HOME, 1, null, CardKind.YELLOW)
        val red = MatchEvent.Card(MatchClock(55), TeamSide.AWAY, 2, null, CardKind.RED)
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 50, 0, 0), listOf(yellow))

        val withRed = reconciler.reconcile(match(MatchStatus.SECOND_HALF, 56, 0, 0), listOf(yellow, red))
        assertEquals(red, assertIs<MatchUpdate.RedCard>(withRed.updates.single()).card)

        // A poll without details (scoreboard) must not reset the count.
        reconciler.reconcile(match(MatchStatus.SECOND_HALF, 57, 0, 0))
        assertTrue(reconciler.reconcile(match(MatchStatus.SECOND_HALF, 58, 0, 0), listOf(yellow, red)).updates.isEmpty())
    }
}
