@file:OptIn(ExperimentalJsExport::class)

package editor.app

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.Promise

// Automation API: exports of the application's ES module, used by the end-to-end tests to observe and drive
// the parts of the application that Compose draws on a canvas and that are therefore invisible in the DOM.

@Serializable
private class AutomationPlugin(val id: String, val sandboxed: Boolean)

@Serializable
private class AutomationFailure(val module: String, val message: String)

@Serializable
private class AutomationCaret(val line: Int, val column: Int, val selectedCharacters: Int, val carets: Int)

@Serializable
private class AutomationConsole(val title: String, val lines: List<String>)

@Serializable
private class AutomationDiagnostic(val severity: String, val message: String, val from: Int, val to: Int)

@Serializable
private class AutomationState(
    val pluginsReady: Boolean,
    val text: String,
    val caret: AutomationCaret,
    val plugins: List<AutomationPlugin>,
    val failures: List<AutomationFailure>,
    val compiler: String?,
    val console: AutomationConsole?,
    val diagnostics: List<AutomationDiagnostic>,
)

@Serializable
private class AutomationLoadResult(val loaded: List<String>, val error: String?)

private val automationScope = MainScope()

/** Returns the session state as JSON. */
@JsExport
fun automationState(): String = Json.encodeToString(
    AutomationState(
        pluginsReady = session.pluginsReady,
        text = session.text,
        caret = session.caret.let { AutomationCaret(it.line, it.column, it.selectedCharacters, it.carets) },
        plugins = session.plugins.map { AutomationPlugin(it.id, it.sandboxed) },
        failures = session.failures.map { AutomationFailure(it.module, it.message) },
        compiler = session.compilerStatus,
        console = session.console?.let { AutomationConsole(it.title, it.lines) },
        diagnostics = session.diagnostics.map { AutomationDiagnostic(it.severity, it.message, it.from, it.to) },
    )
)

@JsExport
fun automationSetText(text: String) {
    session.editor.text = text
}

/** Selects the text between the offsets, or places the caret when they are equal. */
@JsExport
fun automationSelect(anchor: Int, head: Int) {
    session.editor.select(anchor, head)
}

/** Loads the plugin [module] in a sandbox; resolves to JSON with the loaded plugin ids or the error message. */
@JsExport
fun automationLoadSandboxedPlugin(module: String): Promise<JsAny?> = automationScope.promise {
    val result = try {
        AutomationLoadResult(session.loadSandboxedPlugin(module).map { it.id }, error = null)
    } catch (e: Throwable) {
        AutomationLoadResult(emptyList(), error = e.message ?: e.toString())
    }
    Json.encodeToString(result).toJsString()
}

/** Compiles and runs the text, as Ctrl+Enter does; resolves when the program has finished or failed to compile. */
@JsExport
fun automationRun(): Promise<JsAny?> = automationScope.promise {
    session.run().join()
    null
}
