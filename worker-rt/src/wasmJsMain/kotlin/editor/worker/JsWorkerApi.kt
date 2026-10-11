package editor.worker

// Minimal externals for the Web Worker API, so that a worker's instance group does not need a DOM library.

internal external interface MessageEvent : JsAny {
    val data: JsAny?
}

internal external interface ErrorEvent : JsAny {
    val message: String?
}

internal external class Worker(url: String, options: JsAny) : JsAny {
    var onmessage: ((MessageEvent) -> Unit)?
    var onerror: ((ErrorEvent) -> Unit)?
    fun postMessage(message: JsAny?)
    fun terminate()
}

internal external interface DedicatedWorkerGlobalScope : JsAny {
    var onmessage: ((MessageEvent) -> Unit)?
    fun postMessage(message: JsAny?)
}

internal external val self: DedicatedWorkerGlobalScope

internal fun moduleWorkerOptions(): JsAny = js("({ type: 'module' })")
