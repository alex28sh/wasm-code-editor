package editor.compilerhost

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.system.exitProcess

private const val HELLO = """
fun greet(name: String): String = "Hello, ${'$'}name!"

fun main() {
    println(greet("Wasm"))
}
"""

private const val BROKEN = """
fun main() {
    val count: Int = "three"
    println(missing(count))
}
"""

private const val MEMBERS = """
class Box(val label: String) {
    fun describe(): String = "box of " + label
}

fun main() {
    val box = Box("apples")
    println(box.describe())
}
"""

private const val DEPRECATED = """
@Deprecated("Use something else")
fun old() {}

fun main() {
    old()
}
"""

private const val COROUTINES = """
import kotlinx.coroutines.*

suspend fun main() = coroutineScope {
    val squares = (1..3).map { n -> async { delay(10L * (4 - n)); n * n } }
    println("Squares: " + squares.awaitAll())
    launch {
        delay(5)
        println("Launched")
    }
    println("Waiting")
}
"""

/**
 * Runs the requests the editor makes, on the JVM.
 *
 * The build runs it under GraalVM's tracing agent: what the compiler reaches through reflection here is what the
 * WebAssembly image is told to keep, so a kind of request that is missing here may fail in the image.
 * It also fails the build when the host does not answer as expected.
 */
fun main(arguments: Array<String>) {
    val archive = Path.of(arguments[0])
    val host = CompilerHost(Files.createTempDirectory("compiler-host"))
    val failures = mutableListOf<String>()

    fun request(title: String, operation: String, first: String, second: String = "", expected: String): String {
        val answer = host.handle(operation, first, second)
        println("$title: ${answer.take(300)}")
        if (expected !in answer) failures += "$title: expected $expected in ${answer.take(2000)}"
        return answer
    }

    request("init", "init", Base64.getEncoder().encodeToString(archive.readBytes()), expected = "\"files\"")
    request("compile", "compile", HELLO, expected = "\"ok\":true")
    request("compile with errors", "compile", BROKEN, expected = "\"severity\":\"ERROR\"")
    request("compile with a warning", "compile", DEPRECATED, expected = "\"severity\":\"WARNING\"")
    request("compile again", "compile", MEMBERS, expected = "\"ok\":true")
    // As many kinds of declarations, expressions and comments as fit, for the sake of what the agent records.
    val shapes = Path.of(arguments[1]).readText()
    request("compile everything", "compile", shapes, expected = "\"ok\":true")

    // A library the user added, and then removed again.
    request("compile without the library", "compile", COROUTINES, expected = "Unresolved reference 'kotlinx'")
    request("libraries", "libraries", Base64.getEncoder().encodeToString(Path.of(arguments[2]).readBytes()), expected = "\"libraries\":2")
    request("compile with the library", "compile", COROUTINES, expected = "\"ok\":true")
    request("compile without using the library", "compile", HELLO, expected = "\"ok\":true")
    request("no libraries", "libraries", "", expected = "\"libraries\":0")
    request("compile after removing the library", "compile", COROUTINES, expected = "Unresolved reference 'kotlinx'")

    if (failures.isNotEmpty()) {
        failures.forEach(System.err::println)
        exitProcess(1)
    }
    // The IntelliJ platform leaves non-daemon threads behind.
    exitProcess(0)
}
