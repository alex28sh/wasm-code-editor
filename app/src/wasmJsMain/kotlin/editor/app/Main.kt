package editor.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.WebElementView
import androidx.compose.ui.window.ComposeViewport
import editor.codemirror.CaretPosition

internal lateinit var session: EditorSession
    private set

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    session = EditorSession()
    ComposeViewport {
        App(session)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun App(session: EditorSession) {
    DisposableEffect(session) {
        onDispose { session.editor.destroy() }
    }

    LaunchedEffect(session) {
        session.loadIndexedPlugins()
    }

    Column(Modifier.fillMaxSize()) {
        // Compose cannot draw above a web element, so the editor gets a region of its own.
        WebElementView(
            factory = { session.editor.element },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        // The output of the program appears with the first run (Ctrl+Enter).
        session.console?.let { console ->
            Column(
                Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                BasicText(console.title)
                console.lines.forEach { BasicText(it) }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            val text = session.text
            val compiler = session.compilerStatus?.let { " | Compiler: $it" }.orEmpty()
            val active = session.plugins.joinToString { if (it.sandboxed) "${it.id} (sandboxed)" else it.id }
            BasicText("${caretText(session.caret)} | ${text.lines().size} lines, ${text.length} characters | Plugins: ${active.ifEmpty { "none" }}$compiler")
            session.failures.forEach { BasicText("Plugin loading failed: ${it.module}: ${it.message}") }
        }
    }
}

/** The caret the way IntelliJ shows it: `line:column`, then what is selected. */
private fun caretText(caret: CaretPosition): String = buildString {
    append(caret.line).append(':').append(caret.column)
    when {
        caret.carets > 1 -> append(" (").append(caret.carets).append(" carets)")
        caret.selectedCharacters == 1 -> append(" (1 char)")
        caret.selectedCharacters > 1 -> append(" (").append(caret.selectedCharacters).append(" chars)")
    }
}
