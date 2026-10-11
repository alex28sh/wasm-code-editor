package editor.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import editor.codemirror.CaretPosition
import editor.codemirror.CodeEditor
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
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
private const val SAVE_DELAY_MILLISECONDS = 300L

/** What the panel under the editor says about the last run of the program. */
class RunConsole(val title: String, val lines: List<String>)

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
    )

    init {
        // A change made just before the page goes away has not been saved yet. A page without such a change
        // must not save: it would put its old text over what another page has saved since.
        DocumentStore.beforeLeaving { if (saving?.isActive == true) DocumentStore.save(text) }
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
        val source = text
        console = RunConsole("Compiling", emptyList())
        val compilation = compiler.compile(source)
        val answer = compilation.answer
        compilerStatus = "ready"
        if (answer.error != null) {
            console = RunConsole("The compiler failed", answer.error.lines().take(20))
            return
        }
        if (source == text) show(answer.diagnostics)
        if (!answer.ok) {
            console = RunConsole(
                "Compilation failed",
                answer.diagnostics.filter { it.isError }.map { "${position(source, it.from)}: ${it.message}" },
            )
            return
        }
        val lines = mutableListOf<String>()
        console = RunConsole("Running", emptyList())
        val failure = compilation.run { line ->
            lines += line
            console = RunConsole("Running", lines.toList())
        }
        console = RunConsole(if (failure == null) "Finished" else "Failed", lines + listOfNotNull(failure))
    }

    /** Starts the compiler and, next to it, the analysis that from now on checks the text and completes it. */
    private fun startCompiler(): KotlinCompiler {
        analysis = KotlinAnalysis().also { analysis ->
            editor.semanticCompletions = { source, offset -> analysis.complete(source, offset) }
        }
        compilerStatus = "loading"
        return KotlinCompiler().also { compiler = it }
    }

    /** Once the analysis is there, the text is checked again a moment after each change. */
    private fun checkLater() {
        val analysis = analysis ?: return
        checking?.cancel()
        checking = scope.launch {
            delay(CHECK_DELAY_MILLISECONDS)
            val source = text
            val answer = analysis.analyze(source)
            if (source == text && answer.error == null) show(answer.diagnostics)
        }
    }

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
