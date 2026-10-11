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

    if (failures.isNotEmpty()) {
        failures.forEach(System.err::println)
        exitProcess(1)
    }
    // The IntelliJ platform leaves non-daemon threads behind.
    exitProcess(0)
}
