package editor.codemirror

import editor.codemirror.js.EditorView
import editor.codemirror.js.createKotlinEditor
import editor.codemirror.js.showDiagnostics as showDiagnosticsInView
import editor.language.kotlin.Highlight
import editor.language.kotlin.KotlinFile
import editor.language.kotlin.Symbol
import editor.language.kotlin.SymbolKind
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement

private fun replaceDocument(length: Int, text: String): JsAny =
    js("({ changes: { from: 0, to: length, insert: text } })")

private fun selectRange(anchor: Int, head: Int): JsAny =
    js("({ selection: { anchor, head }, scrollIntoView: true })")

private fun structureItem(label: String, detail: String, kind: String, from: Int, depth: Int): JsAny =
    js("({ label, detail, kind, from, depth })")

private fun completionItem(label: String, type: String, detail: String): JsAny =
    js("({ label, type, detail })")

private fun completionResult(from: Int, options: JsArray<JsAny>): JsAny =
    js("({ from, options })")

private fun diagnosticItem(from: Int, to: Int, severity: String, message: String): JsAny =
    js("({ from, to, severity, message })")

private fun kotlinHost(
    highlightClasses: JsArray<JsString>,
    analyze: (String) -> Unit,
    changed: (String) -> Unit,
    selectionChanged: (Int, Int, Int, Int) -> Unit,
    highlights: (Int, Int) -> JsArray<JsNumber>,
    occurrences: (Int) -> JsArray<JsNumber>,
    declaration: (Int) -> Int,
    structure: () -> JsArray<JsAny>,
    enclosing: (Int) -> Int,
    foldRange: (Int, Int) -> JsArray<JsNumber>?,
    completions: (Int, String) -> JsArray<JsAny>,
    semanticCompletions: (Int) -> JsAny?,
    run: () -> Unit,
    libraries: () -> Unit,
): JsAny = js(
    "({ highlightClasses, analyze, changed, selectionChanged, highlights, occurrences, declaration, structure, enclosing, foldRange, completions, semanticCompletions, run, libraries })"
)

/** Where the caret is: [line] and [column] count from one. */
data class CaretPosition(val line: Int, val column: Int, val selectedCharacters: Int, val carets: Int)

class SemanticCompletion(val label: String, val kind: String, val detail: String)

/** What can be written in place of the text between [from] and the caret. */
class SemanticCompletions(val from: Int, val items: List<SemanticCompletion>)

/** A problem in the text between the offsets [from] and [to]. */
class EditorDiagnostic(val from: Int, val to: Int, val error: Boolean, val message: String)

/**
 * A CodeMirror editor holding one Kotlin document.
 *
 * The text is analyzed by [KotlinFile]; the analysis is what the editor paints and navigates by.
 * [element] is the DOM node to place into the page; call [destroy] when it is removed.
 */
class CodeEditor(
    initialText: String,
    onTextChanged: (String) -> Unit = {},
    onCaretMoved: (CaretPosition) -> Unit = {},
    /** Ctrl+Enter was pressed. */
    onRunRequested: () -> Unit = {},
    /** Ctrl+Alt+Shift+S was pressed, IntelliJ's key for the structure of the project. */
    onLibrariesRequested: () -> Unit = {},
) {
    private var source = initialText
    private val scope = MainScope()

    /**
     * Completes the text at an offset by understanding the program, where the editor on its own goes by the names
     * in the file. While this is `null`, or when it answers `null`, the editor's own completion is used.
     */
    var semanticCompletions: (suspend (text: String, offset: Int) -> SemanticCompletions?)? = null
    private var analysis: KotlinFile? = null

    /** The analysis of the current text, made when it is first asked for. */
    private val file: KotlinFile
        get() = analysis ?: KotlinFile(source).also { analysis = it }

    private val view: EditorView = createKotlinEditor(
        initialText,
        kotlinHost(
            highlightClasses = Highlight.entries.map { it.cssClass.toJsString() }.toJsArray(),
            analyze = { text ->
                source = text
                analysis = null
            },
            changed = onTextChanged,
            selectionChanged = { line, column, selected, carets -> onCaretMoved(CaretPosition(line, column, selected, carets)) },
            highlights = ::highlights,
            occurrences = ::occurrences,
            declaration = ::declaration,
            structure = ::structure,
            enclosing = { offset -> file.enclosing(offset)?.let { file.structure.indexOf(it) } ?: 0 },
            foldRange = { lineStart, lineEnd ->
                file.foldRange(lineStart, lineEnd)?.let { listOf(it.first.toJsNumber(), it.last.toJsNumber()).toJsArray() }
            },
            completions = { offset, prefix ->
                file.completions(offset, prefix).map { completionItem(it.label, it.kind, it.detail) }.toJsArray()
            },
            semanticCompletions = { offset ->
                semanticCompletions?.let { complete ->
                    val text = source
                    scope.promise {
                        complete(text, offset)?.let { found ->
                            completionResult(found.from, found.items.map { completionItem(it.label, it.kind, it.detail) }.toJsArray())
                        }
                    }
                }
            },
            run = onRunRequested,
            libraries = onLibrariesRequested,
        )
    )

    val element: HTMLElement
        get() = view.dom

    var text: String
        get() = view.state.doc.toString()
        set(value) {
            view.dispatch(replaceDocument(view.state.doc.length, value))
        }

    /** Moves the caret to [head], selecting the text from [anchor] when it differs. */
    fun select(anchor: Int, head: Int = anchor) {
        val length = view.state.doc.length
        view.dispatch(selectRange(anchor.coerceIn(0, length), head.coerceIn(0, length)))
    }

    /** Replaces the problems marked in the text; they stay where they are until the next call. */
    fun showDiagnostics(diagnostics: List<EditorDiagnostic>) {
        showDiagnosticsInView(
            view,
            diagnostics.map { diagnosticItem(it.from, it.to, if (it.error) "error" else "warning", it.message) }.toJsArray(),
        )
    }

    fun focus() {
        view.focus()
    }

    fun destroy() {
        view.destroy()
    }

    /** `from, to, highlight` for every highlighted piece between the offsets. */
    private fun highlights(from: Int, to: Int): JsArray<JsNumber> {
        val result = JsArray<JsNumber>()
        var length = 0
        file.highlights(from, to) { start, end, highlight ->
            result[length++] = start.toJsNumber()
            result[length++] = end.toJsNumber()
            result[length++] = highlight.ordinal.toJsNumber()
        }
        return result
    }

    /** `from, to, flags` for the declaration and every use of the symbol at [offset]: 1 marks the declaration, 2 a write. */
    private fun occurrences(offset: Int): JsArray<JsNumber> {
        val result = JsArray<JsNumber>()
        val symbol = file.symbolAt(offset) ?: return result
        var length = 0
        for (occurrence in file.occurrences(symbol)) {
            result[length++] = occurrence.start.toJsNumber()
            result[length++] = occurrence.end.toJsNumber()
            result[length++] = ((if (occurrence.declaration) 1 else 0) or (if (occurrence.write) 2 else 0)).toJsNumber()
        }
        return result
    }

    /** Offset of the declaration of the symbol at [offset]; -2 when the caret is on that declaration, -1 without a symbol. */
    private fun declaration(offset: Int): Int {
        val symbol = file.symbolAt(offset) ?: return -1
        return if (file.isDeclarationAt(offset)) -2 else symbol.nameStart
    }

    private fun structure(): JsArray<JsAny> =
        file.structure.map { structureItem(it.signature, it.kind.title, structureKind(it), it.nameStart, it.depth) }.toJsArray()

    private fun structureKind(symbol: Symbol): String = when (symbol.kind) {
        SymbolKind.INTERFACE -> "interface"
        SymbolKind.OBJECT -> "object"
        SymbolKind.ENUM_ENTRY -> "entry"
        SymbolKind.TYPE_ALIAS -> "alias"
        SymbolKind.FUNCTION -> "function"
        SymbolKind.PROPERTY -> "property"
        else -> "class"
    }
}
