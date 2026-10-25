@file:OptIn(KaExperimentalApi::class)

package editor.analysishost

import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolVisibility
import org.jetbrains.kotlin.analysis.api.symbols.KaVariableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaDeclarationContainerSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.wasm.WasmPlatforms
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.types.Variance
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

private const val FILE_NAME = "main.kt"
private const val MAX_COMPLETIONS = 200

private class Diagnostic(val severity: String, val message: String, val from: Int, val to: Int)

private class Completion(val label: String, val kind: String, val detail: String)

/**
 * The Analysis API over one source file for the Wasm target, behind string requests answered in JSON.
 *
 * Everything lives under [root]: the standard library and the other libraries as unpacked klibs, and the file being
 * worked on.
 */
@OptIn(ExperimentalPathApi::class)
class AnalysisHost(private val root: Path) {
    private val stdlib = root.resolve("lib/stdlib")
    private val libraryRoot = root.resolve("lib/user")

    /** The unpacked klibs of the libraries, dependencies first. */
    private var libraries: List<Path> = emptyList()

    init {
        System.setProperty("java.awt.headless", "true")
    }

    /**
     * `init(archive in Base64)`, `libraries(archive in Base64)`, `analyze(source)` and `complete(source, offset)`.
     * A failure is answered as `{"error": ...}` so that one bad request does not take the host down.
     */
    fun handle(operation: String, first: String, second: String): String = try {
        when (operation) {
            "init" -> installStandardLibrary(Base64.getDecoder().decode(first))
            "libraries" -> installLibraries(Base64.getDecoder().decode(first))
            "analyze" -> analyze(first)
            "complete" -> complete(first, second.toInt())
            else -> error("Unknown operation '$operation'")
        }
    } catch (e: Throwable) {
        json { field("error", e.stackTraceToString()) }
    }

    /** Unpacks the archive the build makes of the standard library klib: a count, then name, size and bytes of each file. */
    fun installStandardLibrary(archive: ByteArray): String {
        val input = DataInputStream(ByteArrayInputStream(archive))
        val count = input.readInt()
        repeat(count) {
            val file = stdlib.resolve(input.readUTF())
            file.parent.createDirectories()
            file.writeBytes(ByteArray(input.readInt()).also(input::readFully))
        }
        return json { field("files", count) }
    }

    /**
     * Replaces the libraries the document may use besides the standard library.
     *
     * The archive has the format of the standard library's, and every entry name starts with the directory of its
     * klib: `0-atomicfu-wasm-js/default/manifest`. The klibs come in the order of their dependencies.
     * An empty archive leaves only the standard library.
     */
    fun installLibraries(archive: ByteArray): String {
        libraryRoot.deleteRecursively()
        val directories = LinkedHashSet<String>()
        var count = 0
        if (archive.isNotEmpty()) {
            val input = DataInputStream(ByteArrayInputStream(archive))
            count = input.readInt()
            repeat(count) {
                val name = input.readUTF()
                val segments = name.split('/')
                require(segments.size > 1 && segments.none { it.isEmpty() || it == "." || it == ".." }) { "Bad entry name in the library archive: $name" }
                directories += segments.first()
                val file = libraryRoot.resolve(name)
                file.parent.createDirectories()
                file.writeBytes(ByteArray(input.readInt()).also(input::readFully))
            }
        }
        libraries = directories.map(libraryRoot::resolve)
        return json {
            field("libraries", libraries.size)
            field("files", count)
        }
    }

    private fun analyze(source: String): String {
        val diagnostics = withAnalysis(source) { file ->
            file.collectDiagnostics(KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS).flatMap { diagnostic ->
                diagnostic.textRanges.map { Diagnostic(diagnostic.severity.name, diagnostic.defaultMessage, it.startOffset, it.endOffset) }
            }
        }
        return json { diagnostics(diagnostics.sortedBy { it.from }) }
    }

    private fun complete(source: String, offset: Int): String {
        val caret = offset.coerceIn(0, source.length)
        var prefixStart = caret
        while (prefixStart > 0 && (source[prefixStart - 1].isLetterOrDigit() || source[prefixStart - 1] == '_')) prefixStart--
        val prefix = source.substring(prefixStart, caret)
        val beforePrefix = source.substring(0, prefixStart).trimEnd()

        val completions = withAnalysis(source) { file ->
            val matches = { name: org.jetbrains.kotlin.name.Name -> !name.isSpecial && name.asString().startsWith(prefix, ignoreCase = true) }
            val symbols: Sequence<KaDeclarationSymbol> = if (beforePrefix.endsWith('.')) {
                // After a dot only the members of what is before it make sense: of the type of an expression,
                // or the nested and static declarations of a class.
                val receiver = (file.findElementAt(beforePrefix.length - 1)?.parent as? KtQualifiedExpression)?.receiverExpression
                val qualifier = ((receiver as? KtQualifiedExpression)?.selectorExpression ?: receiver) as? KtReferenceExpression
                val container = qualifier?.mainReference?.resolveToSymbol() as? KaDeclarationContainerSymbol
                val scope = if (container != null) container.staticMemberScope else receiver?.expressionType?.scope?.declarationScope
                if (scope == null) emptySequence() else scope.callables(matches) + scope.classifiers(matches)
            } else {
                val position = PsiTreeUtil.getParentOfType(file.findElementAt((caret - 1).coerceAtLeast(0)), KtElement::class.java, false) ?: file
                file.scopeContext(position).scopes.asSequence().flatMap { it.scope.callables(matches) + it.scope.classifiers(matches) }
            }
            symbols.mapNotNull { completion(it) }.distinctBy { it.label + it.detail }.take(MAX_COMPLETIONS).toList()
        }
        return json {
            field("from", prefixStart)
            array("items", completions) { completion ->
                field("label", completion.label)
                field("kind", completion.kind)
                field("detail", completion.detail)
            }
        }
    }

    private fun KaSession.completion(symbol: KaDeclarationSymbol): Completion? {
        val name = symbol.name?.asString() ?: return null
        // What a library keeps to itself is in its scopes all the same.
        if (symbol.origin == KaSymbolOrigin.LIBRARY && symbol.visibility != KaSymbolVisibility.PUBLIC && symbol.visibility != KaSymbolVisibility.PROTECTED) return null
        fun render(type: KaType) = type.render(KaTypeRendererForSource.WITH_SHORT_NAMES, Variance.INVARIANT)
        return when (symbol) {
            is KaFunctionSymbol -> Completion(
                name,
                "function",
                symbol.valueParameters.joinToString(prefix = "(", postfix = "): ") { "${it.name.asString()}: ${render(it.returnType)}" } +
                        render(symbol.returnType),
            )
            is KaVariableSymbol -> Completion(name, "property", render(symbol.returnType))
            is KaCallableSymbol -> Completion(name, "property", render(symbol.returnType))
            is KaClassifierSymbol -> Completion(name, "class", "")
            else -> null
        }
    }

    /** Runs [action] in an analysis session over [source] as the only file of a module that depends on the libraries. */
    private fun <R> withAnalysis(source: String, action: KaSession.(KtFile) -> R): R {
        val sources = root.resolve("analysis/src").apply { deleteRecursively() }.createDirectories()
        sources.resolve(FILE_NAME).writeText(source)
        val disposable = Disposer.newDisposable("analysis")
        try {
            val session = buildStandaloneAnalysisAPISession(disposable) {
                buildKtModuleProvider {
                    platform = WasmPlatforms.wasmJs
                    val standardLibrary = addModule(buildKtLibraryModule {
                        platform = WasmPlatforms.wasmJs
                        addBinaryRoot(stdlib)
                        libraryName = "stdlib"
                    })
                    // Each library sees the standard library and the libraries before it, which include its dependencies.
                    val dependencies = mutableListOf(standardLibrary)
                    for (klib in libraries) {
                        dependencies += addModule(buildKtLibraryModule {
                            platform = WasmPlatforms.wasmJs
                            addBinaryRoot(klib)
                            libraryName = klib.fileName.toString()
                            dependencies.toList().forEach(::addRegularDependency)
                        })
                    }
                    addModule(buildKtSourceModule {
                        platform = WasmPlatforms.wasmJs
                        moduleName = "main"
                        addSourceRoot(sources)
                        dependencies.forEach(::addRegularDependency)
                    })
                }
            }
            val file = session.modulesWithFiles.values.flatten().filterIsInstance<KtFile>().single()
            return analyze(file) { action(file) }
        } finally {
            Disposer.dispose(disposable)
        }
    }
}

/** A JSON object being written; there is no JSON library on the compiler's classpath. */
private class JsonObject(private val out: StringBuilder) {
    private var empty = true

    private fun key(name: String) {
        if (!empty) out.append(',')
        empty = false
        string(name)
        out.append(':')
    }

    private fun string(value: String) {
        out.append('"')
        for (char in value) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' || char == ' ' || char == ' ' -> out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                else -> out.append(char)
            }
        }
        out.append('"')
    }

    fun field(name: String, value: String) {
        key(name)
        string(value)
    }

    fun field(name: String, value: Boolean) {
        key(name)
        out.append(value)
    }

    fun field(name: String, value: Number) {
        key(name)
        out.append(value)
    }

    fun <T> array(name: String, items: Iterable<T>, write: JsonObject.(T) -> Unit) {
        key(name)
        out.append('[')
        items.forEachIndexed { index, item ->
            if (index > 0) out.append(',')
            out.append('{')
            JsonObject(out).write(item)
            out.append('}')
        }
        out.append(']')
    }

    fun diagnostics(diagnostics: List<Diagnostic>) = array("diagnostics", diagnostics) {
        field("severity", it.severity)
        field("message", it.message)
        field("from", it.from)
        field("to", it.to)
    }
}

private fun json(write: JsonObject.() -> Unit): String = buildString {
    append('{')
    JsonObject(this).write()
    append('}')
}
