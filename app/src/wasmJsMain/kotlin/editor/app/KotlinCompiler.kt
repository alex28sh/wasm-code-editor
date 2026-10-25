package editor.app

import editor.codemirror.EditorDiagnostic
import editor.codemirror.SemanticCompletion
import editor.codemirror.SemanticCompletions
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val RUN_TIMEOUT_MILLISECONDS = 10_000

@Serializable
internal class CompilerDiagnostic(val severity: String, val message: String, val from: Int, val to: Int) {
    val isError: Boolean get() = severity == "ERROR"

    fun toEditorDiagnostic() = EditorDiagnostic(from, to, isError, message)
}

/** The answer to `analyze` and `compile`; [error] is set when the compiler itself failed. */
@Serializable
internal class CompilerAnswer(
    val ok: Boolean = false,
    val milliseconds: Long = 0,
    val diagnostics: List<CompilerDiagnostic> = emptyList(),
    val error: String? = null,
)

internal class Compilation(val answer: CompilerAnswer, private val json: String) {
    /** Runs the compiled program, passing what it prints to [onOutput]; returns why it did not finish, if it did not. */
    suspend fun run(onOutput: (String) -> Unit): String? =
        runKotlinProgram(json, onOutput, RUN_TIMEOUT_MILLISECONDS).await<JsString>().toString().ifEmpty { null }
}

@Serializable
private class CompletionItem(val label: String, val kind: String, val detail: String)

@Serializable
private class CompletionAnswer(val from: Int = 0, val items: List<CompletionItem> = emptyList(), val error: String? = null)

private val format = Json { ignoreUnknownKeys = true }

/**
 * The Kotlin compiler, compiled to WebAssembly and running in a worker.
 *
 * Creating it starts the download of the compiler, which is tens of megabytes; the first request waits for it.
 */
internal class KotlinCompiler {
    private val service = createKotlinService("compiler")

    /**
     * Replaces the libraries besides the standard library with those in [archive] (Base64; empty for none).
     * The worker answers requests in order, so a compilation asked for after this uses them.
     */
    fun setLibraries(archive: String) {
        service.request("libraries", archive, "")
    }

    suspend fun compile(source: String): Compilation {
        val json = service.request("compile", source, "").await<JsString>().toString()
        return Compilation(format.decodeFromString(json), json)
    }
}

/** The Analysis API, in a worker of its own: it answers while the compiler is busy, and is as large a download. */
internal class KotlinAnalysis {
    private val service = createKotlinService("analysis")

    /** Replaces the libraries the text is analyzed against, as [KotlinCompiler.setLibraries] does. */
    fun setLibraries(archive: String) {
        service.request("libraries", archive, "")
    }

    /** The errors and warnings of [source]. */
    suspend fun analyze(source: String): CompilerAnswer =
        format.decodeFromString(service.request("analyze", source, "").await<JsString>().toString())

    /** What can be written at [offset] of [source]; `null` when the analysis failed. */
    suspend fun complete(source: String, offset: Int): SemanticCompletions? {
        val answer = format.decodeFromString<CompletionAnswer>(
            service.request("complete", source, offset.toString()).await<JsString>().toString()
        )
        if (answer.error != null) return null
        return SemanticCompletions(answer.from, answer.items.map { SemanticCompletion(it.label, it.kind, it.detail) })
    }
}
