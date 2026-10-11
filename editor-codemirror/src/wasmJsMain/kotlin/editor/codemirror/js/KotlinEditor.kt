@file:JsModule("./kotlin-editor.mjs")

package editor.codemirror.js

/** Creates the editor view; [host] is the object the script asks about the Kotlin text. */
external fun createKotlinEditor(doc: String, host: JsAny): EditorView

/** Replaces the problems marked in the editor; an item is `{ from, to, severity, message }`. */
external fun showDiagnostics(view: EditorView, diagnostics: JsArray<JsAny>)
