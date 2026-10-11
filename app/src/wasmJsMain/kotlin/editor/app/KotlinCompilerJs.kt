@file:JsModule("./kotlin-compiler-client.mjs")

package editor.app

import kotlin.js.Promise

internal external interface KotlinServiceJs : JsAny {
    /** Resolves to the JSON answer of the host in the worker. */
    fun request(operation: String, text: String, argument: String): Promise<JsString>
}

/** Starts the worker of the `compiler` or of the `analysis`. */
internal external fun createKotlinService(name: String): KotlinServiceJs

/** Resolves to an empty string, or to the reason the program did not finish. */
internal external fun runKotlinProgram(compiled: String, onOutput: (String) -> Unit, timeoutMilliseconds: Int): Promise<JsString>
