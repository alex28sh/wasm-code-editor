@file:JsModule("@codemirror/view")

package editor.codemirror.js

import org.w3c.dom.HTMLElement

external class EditorView : JsAny {
    val state: EditorState
    val dom: HTMLElement

    fun dispatch(spec: JsAny)
    fun focus()
    fun destroy()
}
