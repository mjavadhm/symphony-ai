package io.github.zyrouge.symphony

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zyrouge.symphony.services.Permissions
import io.github.zyrouge.symphony.services.Settings
import io.github.zyrouge.symphony.services.database.Database
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.i18n.Translator
import io.github.zyrouge.symphony.services.radio.Radio
import io.github.zyrouge.symphony.services.llm.LlmClient
import io.github.zyrouge.symphony.services.llm.LlmTasks
import io.github.zyrouge.symphony.services.spotizer.Spotizer
import kotlinx.coroutines.launch
import kotlin.math.abs

class Symphony(application: Application) : AndroidViewModel(application), Symphony.Hooks {
    interface Hooks {
        fun onSymphonyReady() {}
        fun onSymphonyDestroy() {}
        fun onSymphonyActivityReady() {}
        fun onSymphonyActivityPause() {}
        fun onSymphonyActivityDestroy() {}
    }

    val permission = Permissions(this)
    val settings = Settings(this)
    val database = Database(this)
    val groove = Groove(this)
    val radio = Radio(this)
    val translator = Translator(this)
    val semanticSearch = io.github.zyrouge.symphony.services.search.SemanticSearchEngine(this)
    val recommendation = io.github.zyrouge.symphony.services.recommendation.RecommendationEngine(this)
    val flow = io.github.zyrouge.symphony.services.flow.FlowAnalyzer(this)
    val llm = LlmClient(this)
    val llmTasks = LlmTasks(this)
    val spotizer = Spotizer(
        context = application.applicationContext,
        isTrackOnDevice = { track ->
            val title = track.title?.trim()?.lowercase()
            val artist = track.artist?.trim()?.lowercase()
            title != null && groove.song.values().any { song ->
                song.title.trim().lowercase() == title &&
                        (artist == null || song.artists.any { a -> a.trim().lowercase() == artist }) &&
                        (track.durationMs <= 0L || abs(song.duration - track.durationMs) <= 3000L)
            }
        },
        // Freshly downloaded tracks should appear in the library without the user
        // having to trigger a manual re-scan.
        onDownloadCompleted = {
            groove.fetch(Groove.FetchOptions())
        },
    )

    var t by mutableStateOf(translator.getCurrentTranslation())

    val applicationContext get() = getApplication<Application>().applicationContext
    var closeApp: (() -> Unit)? = null
    private var isReady = false
    private var hooks = listOf(this, radio, groove, semanticSearch)

    internal fun emitReady() {
        if (isReady) {
            return
        }
        isReady = true
        notifyHooks { onSymphonyReady() }
    }

    internal fun emitDestroy() {
        notifyHooks { onSymphonyDestroy() }
    }

    internal fun emitActivityReady() {
        emitReady()
        notifyHooks { onSymphonyActivityReady() }
    }

    internal fun emitActivityPause() {
        notifyHooks { onSymphonyActivityPause() }
    }

    internal fun emitActivityDestroy() {
        notifyHooks { onSymphonyActivityDestroy() }
    }

    override fun onSymphonyReady() {
        viewModelScope.launch {
            translator.onChange { nTranslation ->
                t = nTranslation
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        emitDestroy()
    }

    private fun notifyHooks(fn: Hooks.() -> Unit) {
        hooks.forEach { fn.invoke(it) }
    }
}
