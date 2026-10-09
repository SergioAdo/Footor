package app.footor

import android.app.Application
import android.content.Context
import app.footor.core.apifootball.ApiFootballDataSource
import app.footor.core.demo.DemoDataSource
import app.footor.core.live.LiveTracker
import app.footor.core.live.MatchReconciler
import app.footor.core.source.FootballDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class FootorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency wiring; small enough that a DI framework would only add weight. */
class AppContainer(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024))
        .build()

    private val _source = MutableStateFlow(createSource(apiKey))

    /** The active provider; screens switch over when the user changes it in settings. */
    val source: StateFlow<FootballDataSource> = _source.asStateFlow()

    private val _tracker = MutableStateFlow(createTracker(_source.value))
    val tracker: StateFlow<LiveTracker> = _tracker.asStateFlow()

    val apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun updateApiKey(key: String?) {
        prefs.edit().putString(KEY_API_KEY, key?.trim()).apply()
        val source = createSource(apiKey)
        _source.value = source
        _tracker.value = createTracker(source)
    }

    private fun createSource(key: String?): FootballDataSource =
        if (key == null) DemoDataSource() else ApiFootballDataSource(key, httpClient)

    // A fresh reconciler per provider: snapshots from different providers must not be compared.
    private fun createTracker(source: FootballDataSource) = LiveTracker(source, MatchReconciler())

    private companion object {
        const val KEY_API_KEY = "api_football_key"
    }
}
