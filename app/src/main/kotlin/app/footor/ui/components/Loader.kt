package app.footor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.footor.AppContainer
import app.footor.core.source.DataSourceException
import app.footor.core.source.FootballDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val message: String) : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
}

/** One-shot load for non-live screens (team, player, standings); reloads when the provider changes. */
class LoaderViewModel<T>(container: AppContainer, private val load: suspend (FootballDataSource) -> T) : ViewModel() {
    private val attempt = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<Loadable<T>> = combine(container.source, attempt) { source, _ -> source }
        .flatMapLatest { source ->
            flow {
                emit(Loadable.Loading)
                emit(
                    try {
                        Loadable.Ready(load(source))
                    } catch (e: DataSourceException) {
                        Loadable.Failed(e.message ?: "Something went wrong")
                    },
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    fun retry() {
        attempt.value++
    }
}

@Composable
inline fun <reified T> rememberLoader(key: String, noinline load: suspend (FootballDataSource) -> T): LoaderViewModel<T> {
    val container = appContainer()
    return viewModel(key = key) { LoaderViewModel(container, load) }
}

@Composable
fun <T> LoadableContent(loader: LoaderViewModel<T>, content: @Composable (T) -> Unit) {
    val state by loader.state.collectAsStateWithLifecycle()
    when (val s = state) {
        Loadable.Loading -> Loading()
        is Loadable.Failed -> CenteredMessage(s.message, actionLabel = "Retry", onAction = loader::retry)
        is Loadable.Ready -> content(s.value)
    }
}
