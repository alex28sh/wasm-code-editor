package editor.testplugins.reaching

private fun pageTitle(): String = js("globalThis.document?.title ?? ''")

/** Calls into JavaScript: checks that a sandboxed plugin cannot bring its own JavaScript code. */
fun main() {
    println(pageTitle())
}
