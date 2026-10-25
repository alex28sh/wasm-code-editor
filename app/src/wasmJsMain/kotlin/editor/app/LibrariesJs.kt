package editor.app

import kotlin.js.Promise

internal external interface LibrariesPanelJs : JsAny {
    fun open()
    fun close()
    fun isOpen(): Boolean

    /** Shows the libraries, given as JSON. */
    fun render(json: String)
}

@JsModule("./libraries-panel.mjs")
internal external object LibrariesPanelModule : JsAny {
    /** [add] answers an error message, or an empty string when the library was added. */
    fun createLibrariesPanel(add: (String) -> String, remove: (String) -> Unit, closed: () -> Unit): LibrariesPanelJs
}

@JsModule("./kotlin-libraries.mjs")
internal external object KotlinLibrariesModule : JsAny {
    /** Resolves and downloads the libraries in a JSON array of coordinates; see `prepareLibraries` there. */
    fun prepareLibraries(requestedJson: String): Promise<JsString>
}
