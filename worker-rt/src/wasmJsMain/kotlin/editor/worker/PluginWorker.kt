package editor.worker

import editor.plugin.api.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Worker side of the sandbox: serves [WorkerRequest]s sent by a [SandboxedPluginHost]. */
fun runPluginWorker() {
    val scope = CoroutineScope(Dispatchers.Default)
    self.onmessage = { event ->
        val request = messageJson.decodeFromString<WorkerRequest>(event.data.toString())
        scope.launch {
            val response = try {
                handle(request)
            } catch (e: Throwable) {
                RequestFailed(request.requestId, e.message ?: e.toString())
            }
            post(response)
        }
    }
    post(WorkerReady)
}

private fun post(event: WorkerEvent) {
    self.postMessage(messageJson.encodeToString(event).toJsString())
}

private suspend fun handle(request: WorkerRequest): WorkerResponse = when (request) {
    is LoadPlugin -> {
        val known = PluginRegistry.plugins.toSet()
        instantiateRestricted(request.wasmUrl)
        val loaded = PluginRegistry.plugins.filter { it !in known }
        loaded.forEach { it.activate() }
        PluginsLoaded(request.requestId, loaded.map { it.id })
    }
}
