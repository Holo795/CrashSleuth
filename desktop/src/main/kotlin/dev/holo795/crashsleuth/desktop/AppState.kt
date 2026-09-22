package dev.holo795.crashsleuth.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.holo795.crashsleuth.app.Analysis
import dev.holo795.crashsleuth.app.JavaFinder
import dev.holo795.crashsleuth.app.JavaInstall
import dev.holo795.crashsleuth.app.ReportText
import dev.holo795.crashsleuth.app.SearchCancelled
import dev.holo795.crashsleuth.app.SearchListener
import dev.holo795.crashsleuth.app.SearchSetup
import dev.holo795.crashsleuth.app.TargetKind
import dev.holo795.crashsleuth.app.Workbench
import dev.holo795.crashsleuth.bisect.RunRecord
import dev.holo795.crashsleuth.bisect.SearchResult
import dev.holo795.crashsleuth.model.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Texts of the interface, in the chosen language. */
class Ui(val locale: Locale) {
    private val bundle = ResourceBundle.getBundle("crashsleuth/ui", locale, ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
    val messages = Messages(locale)
    val report = ReportText(messages)

    operator fun get(key: String, vararg args: Any?): String {
        val pattern = try { bundle.getString(key) } catch (_: MissingResourceException) { return key }
        return args.foldIndexed(pattern) { index, text, arg -> text.replace("{$index}", arg?.toString() ?: "") }
    }
}

@Serializable
data class Recent(val path: String, val kind: TargetKind, val title: String, val at: Long)

sealed interface Screen {
    data object Home : Screen
    data class Analyzing(val path: Path) : Screen
    data class Failed(val path: Path, val message: String) : Screen
    data class Report(val analysis: Analysis) : Screen
    data class Setup(val analysis: Analysis, val setup: SearchSetup, val javas: List<JavaInstall>) : Screen
    data class Searching(val analysis: Analysis, val setup: SearchSetup) : Screen
}

/** Live state of a running or finished culprit search. */
class SearchProgress(val total: Int) {
    val runs = mutableStateListOf<RunRecord>()
    var suspects by mutableStateOf<List<String>>(emptyList())
    var phase by mutableStateOf("preparing")
    var result by mutableStateOf<SearchResult?>(null)
    var error by mutableStateOf<String?>(null)
    var cancelled by mutableStateOf(false)
    val startedAt = System.currentTimeMillis()
    val cancel = AtomicBoolean(false)
}

class AppState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val workbench = Workbench()
    private var settings = Settings.load()

    var ui by mutableStateOf(Ui(settings.language?.let(Locale::forLanguageTag) ?: Locale.getDefault()))
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    val recents = mutableStateListOf<Recent>().apply { addAll(settings.recents) }
    var search by mutableStateOf<SearchProgress?>(null)
        private set
    private var job: Job? = null

    fun setLanguage(tag: String) {
        ui = Ui(Locale.forLanguageTag(tag))
        settings = settings.copy(language = tag)
        Settings.save(settings)
    }

    fun home() {
        screen = Screen.Home
    }

    fun open(path: Path) {
        screen = Screen.Analyzing(path)
        job?.cancel()
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { workbench.analyze(path) } }
            result.onSuccess { analysis ->
                remember(analysis)
                screen = Screen.Report(analysis)
            }.onFailure { error ->
                screen = Screen.Failed(path, error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun remember(analysis: Analysis) {
        val title = analysis.report.primary?.let { ui.report.title(it) } ?: ui["report.none.title"]
        recents.removeAll { it.path == analysis.target.path }
        recents.add(0, Recent(analysis.target.path, analysis.target.kind, title, System.currentTimeMillis()))
        while (recents.size > 8) recents.removeAt(recents.lastIndex)
        settings = settings.copy(recents = recents.toList())
        Settings.save(settings)
    }

    fun back(analysis: Analysis) {
        screen = Screen.Report(analysis)
    }

    fun prepareSearch(analysis: Analysis) {
        // The screen opens at once; the Java installations of the computer are found meanwhile.
        val base = SearchSetup(analysis.target.path, analysis.target.kind == TargetKind.CLIENT, analysis.target.minecraft, analysis.target.loader)
        screen = Screen.Setup(analysis, base, emptyList())
        scope.launch {
            val (setup, javas) = withContext(Dispatchers.IO) { workbench.defaultSetup(analysis) to JavaFinder.find() }
            if ((screen as? Screen.Setup)?.analysis == analysis) screen = Screen.Setup(analysis, setup, javas)
        }
    }

    fun startSearch(analysis: Analysis, setup: SearchSetup) {
        val progress = SearchProgress(analysis.inventory?.jars?.size ?: 0)
        search = progress
        screen = Screen.Searching(analysis, setup)
        job = scope.launch {
            val listener = object : SearchListener {
                override fun message(text: String) {
                    if (text == "preparing" || text == "installing") scope.launch { progress.phase = text }
                }
                override fun run(record: RunRecord) { scope.launch { progress.runs.add(record); progress.phase = "running" } }
                override fun suspects(keys: List<String>) { scope.launch { progress.suspects = keys } }
            }
            val outcome = withContext(Dispatchers.IO) { runCatching { workbench.search(setup, analysis.suspects, listener, progress.cancel) } }
            outcome.onSuccess { progress.result = it }
                .onFailure { error -> if (error is SearchCancelled) progress.cancelled = true else progress.error = error.message ?: error.javaClass.simpleName }
        }
    }

    fun stopSearch() {
        search?.cancel?.set(true)
    }

    fun openRecent(recent: Recent) = open(Path.of(recent.path))
}

/** The few things remembered between two starts, in the user's own folder. */
@Serializable
data class Settings(val language: String? = null, val recents: List<Recent> = emptyList()) {
    companion object {
        private val file: Path = Path.of(System.getProperty("user.home"), ".crashsleuth", "desktop.json")
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

        fun load(): Settings = runCatching { if (file.exists()) json.decodeFromString(serializer(), file.readText()) else Settings() }.getOrDefault(Settings())

        fun save(settings: Settings) {
            runCatching {
                file.parent.createDirectories()
                file.writeText(json.encodeToString(serializer(), settings))
            }
        }
    }
}
