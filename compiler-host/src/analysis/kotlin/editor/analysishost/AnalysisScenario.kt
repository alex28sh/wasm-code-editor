package editor.analysishost

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
    println(box.de)
    println("text".le)
}
"""

/** Runs the requests the editor would make to the Analysis API, on the JVM, and fails when an answer is not the expected one. */
fun main(arguments: Array<String>) {
    val host = AnalysisHost(Files.createTempDirectory("analysis-host"))
    val failures = mutableListOf<String>()

    fun request(title: String, operation: String, first: String, second: String = "", expected: String) {
        val started = System.currentTimeMillis()
        val answer = host.handle(operation, first, second)
        println("$title (${System.currentTimeMillis() - started} ms): ${answer.take(300)}")
        if (expected !in answer) failures += "$title: expected $expected in ${answer.take(6000)}"
    }

    fun after(text: String, marker: String) = (text.indexOf(marker) + marker.length).toString()

    request("init", "init", Base64.getEncoder().encodeToString(Path.of(arguments[0]).readBytes()), expected = "\"files\"")
    request("analyze", "analyze", HELLO, expected = "\"diagnostics\":[]")
    request("analyze with errors", "analyze", BROKEN, expected = "Unresolved reference")
    request("complete members", "complete", MEMBERS, after(MEMBERS, "box.de"), expected = "\"label\":\"describe\"")
    request("complete library members", "complete", MEMBERS, after(MEMBERS, "\"text\".le"), expected = "\"label\":\"length\"")
    request("complete in scope", "complete", HELLO, after(HELLO, "println(gre"), expected = "\"label\":\"greet\"")
    request("complete from the library", "complete", HELLO, after(HELLO, "    print"), expected = "\"label\":\"println\"")

    // As many kinds of declarations, expressions and comments as fit, for the sake of what the agent records.
    val shapes = Path.of(arguments[1]).readText()
    request("analyze everything", "analyze", shapes, expected = "\"diagnostics\":[]")
    request("complete in everything", "complete", shapes, after(shapes, "    println(larg"), expected = "\"label\":\"largest\"")
    request("complete a library member in everything", "complete", shapes, after(shapes, "text!!.len"), expected = "\"label\":\"length\"")
    request("complete an enum", "complete", shapes, after(shapes, "Scale.MET"), expected = "\"label\":\"METER\"")

    if (failures.isNotEmpty()) {
        failures.forEach(System.err::println)
        exitProcess(1)
    }
    // The IntelliJ platform leaves non-daemon threads behind.
    exitProcess(0)
}
