package editor.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

private const val LIBRARIES_KEY = "wasm-code-editor.libraries"

private val coordinatesPattern = Regex("""^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::([A-Za-z0-9_.+-]+))?$""")

@Serializable
enum class LibraryState {
    @SerialName("resolving") RESOLVING,
    @SerialName("ready") READY,
    @SerialName("failed") FAILED,
}

/**
 * A library the user listed in the libraries panel, by its Maven [coordinates]: what became of it, why it failed
 * ([message]), and, once it is loaded, the [klibs] it brought, its dependencies included.
 */
@Serializable
class Library(
    val coordinates: String,
    val state: LibraryState,
    val message: String = "",
    val klibs: List<String> = emptyList(),
)

/** What `prepareLibraries` of kotlin-libraries.mjs answers. */
@Serializable
internal class PreparedLibraries(val libraries: List<PreparedLibrary>, val archive: String)

@Serializable
internal class PreparedLibrary(val coordinates: String, val error: String? = null, val klibs: List<String> = emptyList())

private val format = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Why [text] cannot be added to [listed], or null when it can. */
internal fun libraryProblem(text: String, listed: List<Library>): String? {
    val match = coordinatesPattern.matchEntire(text) ?: return when {
        text.isEmpty() -> "Write the Maven coordinates of a library: group:artifact:version"
        else -> "'$text' is not group:artifact:version"
    }
    val module = "${match.groupValues[1]}:${match.groupValues[2]}"
    if (listed.any { it.coordinates == module || it.coordinates.startsWith("$module:") }) return "$module is in the list already"
    return null
}

internal fun librariesJson(libraries: List<Library>): String = format.encodeToString(libraries)

internal fun coordinatesJson(coordinates: List<String>): String = format.encodeToString(coordinates)

internal fun parsePreparedLibraries(json: String): PreparedLibraries = format.decodeFromString(json)

/** Keeps the list of libraries in the browser, next to the document. */
internal object LibraryStore {
    fun load(): List<String> = readItem(LIBRARIES_KEY)?.let { stored ->
        runCatching { format.decodeFromString<List<String>>(stored) }.getOrNull()
    }.orEmpty()

    fun save(coordinates: List<String>) = writeItem(LIBRARIES_KEY, format.encodeToString(coordinates))
}
