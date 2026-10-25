package editor.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
                // The state of the run and the errors are not what the program printed, and must not look like it.
                BasicText(console.title, style = TextStyle(color = stateColor(console.state), fontWeight = FontWeight.Bold))
                console.lines.forEach { BasicText(it) }
                console.errors.forEach { BasicText(it, style = TextStyle(color = errorRed)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val text = session.text
                val compiler = session.compilerStatus?.let { " | Compiler: $it" }.orEmpty()
                val active = session.plugins.joinToString { if (it.sandboxed) "${it.id} (sandboxed)" else it.id }
                BasicText("${caretText(session.caret)} | ${text.lines().size} lines, ${text.length} characters | Plugins: ${active.ifEmpty { "none" }}$compiler")
                session.failures.forEach { BasicText("Plugin loading failed: ${it.module}: ${it.message}") }
            }
            LibrariesButton(session)
            RunButton(session)
        }
    }
}

private val runGreen = Color(0xFF59A869)
private val errorRed = Color(0xFFDB3B4B)
private val finishedGreen = Color(0xFF208A3C)
private val inProgressGray = Color(0xFF6C707E)

private fun stateColor(state: RunState): Color = when {
    state.failed -> errorRed
    state.inProgress -> inProgressGray
    else -> finishedGreen
}
private val buttonBorder = Color(0xFFC9CCD6)

/** A button of the status bar; [placed] learns where it is, for the tests that click what is only drawn. */
@Composable
private fun StatusBarButton(placed: (ButtonBounds) -> Unit, onClick: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    Row(
        Modifier
            .padding(start = 12.dp)
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                placed(ButtonBounds(bounds.left / density, bounds.top / density, bounds.width / density, bounds.height / density))
            }
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, buttonBorder, RoundedCornerShape(4.dp))
            .background(Color.White)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            // No higher than a line of text, so that the status bar is as high with the button as without.
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Opens the libraries panel, as Ctrl+Alt+Shift+S does; says how many libraries there are, in red when one failed. */
@Composable
private fun LibrariesButton(session: EditorSession) {
    StatusBarButton(placed = { session.librariesButtonBounds = it }, onClick = { session.openLibraries() }) {
        val libraries = session.libraries
        val failed = libraries.any { it.state == LibraryState.FAILED }
        BasicText(
            if (libraries.isEmpty()) "Libraries" else "Libraries (${libraries.size})",
            style = TextStyle(color = if (failed) errorRed else Color.Black),
        )
    }
}

/** Compiles and runs the document, as Ctrl+Enter does in the editor. */
@Composable
private fun RunButton(session: EditorSession) {
    StatusBarButton(placed = { session.runButtonBounds = it }, onClick = { session.run() }) {
        // A triangle of its own: the font has no glyph for one.
        Canvas(Modifier.size(9.dp)) {
            val triangle = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2)
                lineTo(0f, size.height)
                close()
            }
            drawPath(triangle, runGreen)
        }
        BasicText("Run")
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
