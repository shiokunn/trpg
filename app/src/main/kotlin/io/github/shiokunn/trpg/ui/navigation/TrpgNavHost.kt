package io.github.shiokunn.trpg.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.shiokunn.trpg.AppContainer
import io.github.shiokunn.trpg.ui.CharacterViewModel
import io.github.shiokunn.trpg.ui.GameViewModel
import io.github.shiokunn.trpg.ui.HomeViewModel
import io.github.shiokunn.trpg.ui.JournalViewModel
import io.github.shiokunn.trpg.ui.ModelsViewModel
import io.github.shiokunn.trpg.ui.SavesViewModel
import io.github.shiokunn.trpg.ui.SettingsViewModel
import io.github.shiokunn.trpg.ui.WorldViewModel
import io.github.shiokunn.trpg.ui.character.CharacterScreen
import io.github.shiokunn.trpg.ui.components.LoadingPane
import io.github.shiokunn.trpg.ui.creation.NewAdventureScreen
import io.github.shiokunn.trpg.ui.game.GameScreen
import io.github.shiokunn.trpg.ui.home.HomeScreen
import io.github.shiokunn.trpg.ui.journal.JournalScreen
import io.github.shiokunn.trpg.ui.models.ModelsScreen
import io.github.shiokunn.trpg.ui.saves.SavesScreen
import io.github.shiokunn.trpg.ui.settings.SettingsScreen
import io.github.shiokunn.trpg.ui.world.WorldScreen

object Routes {
    const val HOME = "home"
    const val NEW_ADVENTURE = "new_adventure"
    const val SAVES = "saves"
    const val MODELS = "models"
    const val SETTINGS = "settings"

    const val GAME = "game/{sessionId}"
    const val CHARACTER = "character/{sessionId}"
    const val WORLD = "world/{sessionId}"
    const val JOURNAL = "journal/{sessionId}"

    fun game(sessionId: String) = "game/$sessionId"
    fun character(sessionId: String) = "character/$sessionId"
    fun world(sessionId: String) = "world/$sessionId"
    fun journal(sessionId: String) = "journal/$sessionId"
}

@Composable
fun TrpgNavHost(container: AppContainer) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = io.github.shiokunn.trpg.ui.createFactory { HomeViewModel(container) })
            HomeScreen(
                viewModel = vm,
                onNewAdventure = { navController.navigate(Routes.NEW_ADVENTURE) },
                onOpenSession = { navController.navigate(Routes.game(it)) },
                onSaves = { navController.navigate(Routes.SAVES) },
                onModels = { navController.navigate(Routes.MODELS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.NEW_ADVENTURE) {
            NewAdventureScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SAVES) {
            val vm: SavesViewModel = viewModel(factory = io.github.shiokunn.trpg.ui.createFactory { SavesViewModel(container) })
            SavesScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenSession = { navController.navigate(Routes.game(it)) },
            )
        }

        composable(Routes.MODELS) {
            val vm: ModelsViewModel = viewModel(factory = io.github.shiokunn.trpg.ui.createFactory { ModelsViewModel(container) })
            ModelsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = io.github.shiokunn.trpg.ui.createFactory { SettingsViewModel(container) })
            SettingsScreen(viewModel = vm, onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.GAME,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId")
            if (sessionId.isNullOrBlank()) {
                LoadingPane("セッションが見つかりません")
            } else {
                val vm: GameViewModel = viewModel(
                    key = "game_$sessionId",
                    factory = io.github.shiokunn.trpg.ui.createFactory { GameViewModel(container, sessionId) },
                )
                GameScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onCharacter = { navController.navigate(Routes.character(sessionId)) },
                    onWorld = { navController.navigate(Routes.world(sessionId)) },
                    onJournal = { navController.navigate(Routes.journal(sessionId)) },
                )
            }
        }

        composable(
            route = Routes.CHARACTER,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId")
            if (sessionId.isNullOrBlank()) {
                LoadingPane("セッションが見つかりません")
            } else {
                val vm: CharacterViewModel = viewModel(
                    key = "character_$sessionId",
                    factory = io.github.shiokunn.trpg.ui.createFactory { CharacterViewModel(container, sessionId) },
                )
                CharacterScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
        }

        composable(
            route = Routes.WORLD,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId")
            if (sessionId.isNullOrBlank()) {
                LoadingPane("セッションが見つかりません")
            } else {
                val vm: WorldViewModel = viewModel(
                    key = "world_$sessionId",
                    factory = io.github.shiokunn.trpg.ui.createFactory { WorldViewModel(container, sessionId) },
                )
                WorldScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
        }

        composable(
            route = Routes.JOURNAL,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
        ) { entry ->
            val sessionId = entry.arguments?.getString("sessionId")
            if (sessionId.isNullOrBlank()) {
                LoadingPane("セッションが見つかりません")
            } else {
                val vm: JournalViewModel = viewModel(
                    key = "journal_$sessionId",
                    factory = io.github.shiokunn.trpg.ui.createFactory { JournalViewModel(container, sessionId) },
                )
                JournalScreen(viewModel = vm, onBack = { navController.popBackStack() })
            }
        }
    }
}
