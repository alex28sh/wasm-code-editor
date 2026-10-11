package editor.app

private const val KEY = "wasm-code-editor.document"

// The storage is missing or refuses to work in some private modes and when it is full; the editor works without it.
private fun readItem(key: String): String? =
    js("{ try { return globalThis.localStorage.getItem(key) } catch (e) { return null } }")

private fun writeItem(key: String, value: String): Unit =
    js("{ try { globalThis.localStorage.setItem(key, value) } catch (e) {} }")

private fun onPageHidden(action: () -> Unit): Unit =
    js("globalThis.addEventListener('pagehide', () => action())")

/** Keeps the document in the browser, so that a page opened later starts with it. */
internal object DocumentStore {
    fun load(): String? = readItem(KEY)

    fun save(text: String) = writeItem(KEY, text)

    /** Runs [action] when the page is closed, reloaded or left. */
    fun beforeLeaving(action: () -> Unit) = onPageHidden(action)
}
