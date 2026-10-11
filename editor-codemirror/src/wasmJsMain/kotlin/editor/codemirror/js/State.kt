@file:JsModule("@codemirror/state")

package editor.codemirror.js

external class Text : JsAny {
    val length: Int
}

external class EditorState : JsAny {
    val doc: Text
}
