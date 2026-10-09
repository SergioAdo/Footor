package app.footor.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.footor.ui.match.MatchScreen
import app.footor.ui.matches.MatchesScreen
import app.footor.ui.player.PlayerScreen
import app.footor.ui.settings.SettingsScreen
import app.footor.ui.standings.StandingsScreen
import app.footor.ui.team.TeamScreen

@Composable
fun FootorNavHost() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val toTeam: (Long) -> Unit = { nav.navigate("team/$it") }
    val toPlayer: (Long, Int) -> Unit = { id, season -> nav.navigate("player/$id/$season") }
    val toStandings: (Long, Int) -> Unit = { id, season -> nav.navigate("standings/$id/$season") }

    NavHost(navController = nav, startDestination = "matches") {
        composable("matches") {
            MatchesScreen(
                onMatchClick = { nav.navigate("match/$it") },
                onSettingsClick = { nav.navigate("settings") },
            )
        }
        composable("match/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            MatchScreen(
                matchId = it.arguments!!.getLong("id"),
                onBack = back,
                onTeamClick = toTeam,
                onPlayerClick = toPlayer,
                onCompetitionClick = toStandings,
            )
        }
        composable("team/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            TeamScreen(
                teamId = it.arguments!!.getLong("id"),
                onBack = back,
                onMatchClick = { id -> nav.navigate("match/$id") },
                onPlayerClick = toPlayer,
            )
        }
        composable(
            "player/{id}/{season}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("season") { type = NavType.IntType }),
        ) {
            PlayerScreen(playerId = it.arguments!!.getLong("id"), season = it.arguments!!.getInt("season"), onBack = back)
        }
        composable(
            "standings/{id}/{season}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("season") { type = NavType.IntType }),
        ) {
            StandingsScreen(
                competitionId = it.arguments!!.getLong("id"),
                season = it.arguments!!.getInt("season"),
                onBack = back,
                onTeamClick = toTeam,
            )
        }
        composable("settings") { SettingsScreen(onBack = back) }
    }
}
