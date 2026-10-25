package editor.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import editor.codemirror.CaretPosition
import editor.codemirror.CodeEditor
import editor.kotlinparser.KotlinSyntax
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

private val sampleText = """
    /** Greets [name]. */
    fun greet(name: String): String = "Hello, ${'$'}name!"

    fun main() {
        println(greet("Wasm"))
    }
""".trimIndent()

private fun sandboxAllRequested(): Boolean =
    js("new URLSearchParams(globalThis.location.search).get('sandbox') === 'all'")

private const val CHECK_DELAY_MILLISECONDS = 300L
private const val SYNTAX_CHECK_DELAY_MILLISECONDS = 100L
private const val SAVE_DELAY_MILLISECONDS = 300L

/** How far the last run of the program has got. */
enum class RunState(val title: String, val inProgress: Boolean = false, val failed: Boolean = false) {
    LOADING_LIBRARIES("Loading libraries", inProgress = true),
    LIBRARIES_FAILED("Libraries failed", failed = true),
    COMPILING("Compiling", inProgress = true),
    RUNNING("Running", inProgress = true),
    FINISHED("Finished"),
    FAILED("Failed", failed = true),
    COMPILATION_FAILED("Compilation failed", failed = true),
    COMPILER_FAILED("The compiler failed", failed = true),
}

/**
 * What the panel under the editor says about the last run of the program: the [state] of the run,
 * the [lines] the program printed, and the [errors] of the compiler or the reason the program did not finish.
 */
class RunConsole(val state: RunState, val lines: List<String> = emptyList(), val errors: List<String> = emptyList()) {
    val title: String get() = state.title
}

/** Where a button drawn by Compose is in the window, in CSS pixels. */
class ButtonBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** State of the running editor, observable from Compose. */
class EditorSession {
    /** Starts as the document the browser kept from the last time, or as the sample. */
    var text by mutableStateOf(DocumentStore.load() ?: sampleText)
        private set

    var plugins by mutableStateOf(emptyList<LoadedPlugin>())
        private set

    var failures by mutableStateOf(emptyList<PluginLoadFailure>())
        private set

    /** Becomes `true` once every plugin of the index has either loaded or failed. */
    var pluginsReady by mutableStateOf(false)
        private set

    var caret by mutableStateOf(CaretPosition(line = 1, column = 1, selectedCharacters = 0, carets = 1))
        private set

    /** What the compiler is doing; `null` until it is first needed, because starting it downloads tens of megabytes. */
    var compilerStatus by mutableStateOf<String?>(null)
        private set

    /** `null` until the program is first run. */
    var console by mutableStateOf<RunConsole?>(null)
        private set

    /** The problems the compiler found in the text they are marked in. */
    internal var diagnostics by mutableStateOf(emptyList<CompilerDiagnostic>())
        private set

    /** Where the Run button is; the tests need it to click what is only drawn. */
    internal var runButtonBounds: ButtonBounds? = null

    /** Where the Libraries button is. */
    internal var librariesButtonBounds: ButtonBounds? = null

    /** The libraries the user listed, besides the standard library, in the order they were added. */
    var libraries by mutableStateOf(LibraryStore.load().map { Library(it, LibraryState.RESOLVING) })
        private set

    /** The klibs of the libraries that loaded, for the compiler and the analysis; empty when there are none. */
    private var librariesArchive = ""
    private var preparingLibraries: Job? = null

    private val librariesPanel = LibrariesPanelModule.createLibrariesPanel(
        add = ::addLibrary,
        remove = ::removeLibrary,
        closed = { editor.focus() },
    )

    private val scope = MainScope()
    private var compiler: KotlinCompiler? = null
    private var analysis: KotlinAnalysis? = null
    private var checking: Job? = null
    private var saving: Job? = null
    private var running: Job? = null

    val editor = CodeEditor(
        text,
        onTextChanged = {
            text = it
            saveLater()
            checkLater()
        },
        onCaretMoved = { caret = it },
        onRunRequested = { run() },
        onLibrariesRequested = { openLibraries() },
    )

    init {
        // A change made just before the page goes away has not been saved yet. A page without such a change
        // must not save: it would put its old text over what another page has saved since.
        DocumentStore.beforeLeaving { if (saving?.isActive == true) DocumentStore.save(text) }
        if (libraries.isNotEmpty()) prepareLibraries()
        checkLater()
    }

    /** Opens the panel in which the user lists libraries. */
    fun openLibraries() {
        librariesPanel.open()
        librariesPanel.render(librariesJson(libraries))
    }

    /** Adds the library with the Maven coordinates [text]; answers why it cannot, or an empty string. */
    fun addLibrary(text: String): String {
        val coordinates = text.trim()
        libraryProblem(coordinates, libraries)?.let { return it }
        updateLibraries(libraries + Library(coordinates, LibraryState.RESOLVING))
        LibraryStore.save(libraries.map { it.coordinates })
        prepareLibraries()
        return ""
    }

    fun removeLibrary(coordinates: String) {
        updateLibraries(libraries.filter { it.coordinates != coordinates })
        LibraryStore.save(libraries.map { it.coordinates })
        prepareLibraries()
    }

    private fun updateLibraries(value: List<Library>) {
        libraries = value
        librariesPanel.render(librariesJson(value))
    }

    /** Resolves and downloads the listed libraries anew, and hands them to the compiler and the analysis. */
    private fun prepareLibraries() {
        preparingLibraries?.cancel()
        updateLibraries(libraries.map { Library(it.coordinates, LibraryState.RESOLVING) })
        val requested = libraries.map { it.coordinates }
        preparingLibraries = scope.launch {
            val answer = parsePreparedLibraries(
                KotlinLibrariesModule.prepareLibraries(coordinatesJson(requested)).await<JsString>().toString()
            )
            updateLibraries(answer.libraries.map {
                if (it.error != null) Library(it.coordinates, LibraryState.FAILED, it.error)
                else Library(it.coordinates, LibraryState.READY, klibs = it.klibs)
            })
            librariesArchive = answer.archive
            compiler?.setLibraries(librariesArchive)
            analysis?.setLibraries(librariesArchive)
            checkLater()
        }
    }

    /** Saves the text a moment after the last change, not on every keystroke. */
    private fun saveLater() {
        saving?.cancel()
        saving = scope.launch {
            delay(SAVE_DELAY_MILLISECONDS)
            DocumentStore.save(text)
        }
    }

    /** Compiles the text and runs the program, replacing a run that is still going. */
    fun run(): Job {
        running?.cancel()
        return scope.launch { compileAndRun() }.also { running = it }
    }

    private suspend fun compileAndRun() {
        val compiler = compiler ?: startCompiler()
        while (preparingLibraries?.isActive == true) {
            console = RunConsole(RunState.LOADING_LIBRARIES)
            preparingLibraries?.join()
        }
        val failed = libraries.filter { it.state == LibraryState.FAILED }
        if (failed.isNotEmpty()) {
            console = RunConsole(RunState.LIBRARIES_FAILED, errors = failed.map { "${it.coordinates}: ${it.message}" })
            return
        }
        val source = text
        console = RunConsole(RunState.COMPILING)
        val compilation = compiler.compile(source)
        val answer = compilation.answer
        compilerStatus = "ready"
        if (answer.error != null) {
            console = RunConsole(RunState.COMPILER_FAILED, errors = answer.error.lines().take(20))
            return
        }
        if (source == text) show(answer.diagnostics)
        if (!answer.ok) {
            console = RunConsole(
                RunState.COMPILATION_FAILED,
                errors = answer.diagnostics.filter { it.isError }.map { "${position(source, it.from)}: ${it.message}" },
            )
            return
        }
        val lines = mutableListOf<String>()
        console = RunConsole(RunState.RUNNING)
        val failure = compilation.run { line ->
            lines += line
            console = RunConsole(RunState.RUNNING, lines.toList())
        }
        console = when (failure) {
            null -> RunConsole(RunState.FINISHED, lines)
            else -> RunConsole(RunState.FAILED, lines, errors = failure.lines())
        }
    }

    /** Starts the compiler and, next to it, the analysis that from now on checks the text and completes it. */
    private fun startCompiler(): KotlinCompiler {
        analysis = KotlinAnalysis().also { analysis ->
            editor.semanticCompletions = { source, offset -> analysis.complete(source, offset) }
        }
        compilerStatus = "loading"
        return KotlinCompiler().also { compiler = it }.also {
            // Libraries loaded before the compiler was there; those that load later are sent when they are ready.
            if (librariesArchive.isNotEmpty()) {
                it.setLibraries(librariesArchive)
                analysis?.setLibraries(librariesArchive)
            }
        }
    }

    /**
     * Checks the text a moment after each change. Until the compiler is started, the compiler's own parser does it in
     * the page and finds the syntax errors; from then on the analysis finds those and everything else.
     */
    private fun checkLater() {
        checking?.cancel()
        val analysis = analysis
        checking = scope.launch {
            if (analysis == null) {
                delay(SYNTAX_CHECK_DELAY_MILLISECONDS)
                show(syntaxErrors(text))
                return@launch
            }
            delay(CHECK_DELAY_MILLISECONDS)
            val source = text
            val answer = analysis.analyze(source)
            if (source == text && answer.error == null) show(answer.diagnostics)
        }
    }

    /** The syntax errors of [source], from the Kotlin compiler's multiplatform parser (kotlin-parser). */
    private fun syntaxErrors(source: String): List<CompilerDiagnostic> =
        KotlinSyntax.parse(source).errors.map { CompilerDiagnostic("ERROR", it.message, it.from, it.to) }

    private fun show(found: List<CompilerDiagnostic>) {
        diagnostics = found
        editor.showDiagnostics(found.map { it.toEditorDiagnostic() })
    }

    /** `line:column` of [offset], counting from one. */
    private fun position(source: String, offset: Int): String {
        val before = source.substring(0, offset.coerceIn(0, source.length))
        return "${before.count { it == '\n' } + 1}:${before.length - before.lastIndexOf('\n')}"
    }

    /**
     * Loads the plugins of the index.
     *
     * The page address can only tighten the trust decision: `?sandbox=all` moves trusted plugins into workers.
     */
    suspend fun loadIndexedPlugins() {
        try {
            val result = PluginLoader.loadAll("./plugins.json", sandboxAll = sandboxAllRequested())
            plugins += result.loaded
            failures += result.failures
        } catch (e: Throwable) {
            failures += PluginLoadFailure("plugins.json", e.message ?: e.toString())
        }
        pluginsReady = true
    }

    /** Loads one more plugin in a sandbox, recording a failure before rethrowing it. */
    suspend fun loadSandboxedPlugin(module: String): List<LoadedPlugin> {
        try {
            return PluginLoader.loadSandboxed(module).also { plugins += it }
        } catch (e: Throwable) {
            failures += PluginLoadFailure(module, e.message ?: e.toString())
            throw e
        }
    }
}
