package editor.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PluginWorkerException(message: String) : Exception(message)

/**
 * Host side of the sandbox: owns one Worker running its own instance group and talks to it with serialized messages.
 *
 * A request that does not complete within [requestTimeout] terminates the worker, because a plugin stuck in a loop
 * cannot be interrupted in any other way.
 */
class SandboxedPluginHost(
    workerUrl: String = "./wasm-code-editor-plugin-worker.mjs",
    private val requestTimeout: Duration = 10.seconds,
) {
    private val worker = Worker(workerUrl, moduleWorkerOptions())
    private val pending = mutableMapOf<Int, CompletableDeferred<WorkerResponse>>()
    private val ready = CompletableDeferred<Unit>()
    private var nextRequestId = 0

    init {
        worker.onmessage = { event ->
            when (val message = messageJson.decodeFromString<WorkerEvent>(event.data.toString())) {
                WorkerReady -> ready.complete(Unit)
                is WorkerResponse -> pending.remove(message.requestId)?.complete(message)
            }
        }
        worker.onerror = { event ->
            failPending(PluginWorkerException(event.message ?: "Plugin worker failed"))
        }
    }

    /** Loads and activates the plugin binary at [wasmUrl] in the worker and returns the ids it registered. */
    suspend fun loadPlugin(wasmUrl: String): List<String> {
        val response = request(LoadPlugin(nextRequestId++, wasmUrl))
        return (response as PluginsLoaded).pluginIds
    }

    fun terminate() {
        worker.terminate()
        failPending(PluginWorkerException("Plugin worker was terminated"))
    }

    private suspend fun request(request: WorkerRequest): WorkerResponse {
        val result = CompletableDeferred<WorkerResponse>()
        pending[request.requestId] = result
        val response = try {
            withTimeout(requestTimeout) {
                ready.await()
                worker.postMessage(messageJson.encodeToString(request).toJsString())
                result.await()
            }
        } catch (e: TimeoutCancellationException) {
            terminate()
            throw PluginWorkerException("Plugin worker did not respond within $requestTimeout and was terminated")
        }
        if (response is RequestFailed) throw PluginWorkerException(response.message)
        return response
    }

    private fun failPending(cause: PluginWorkerException) {
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { it.completeExceptionally(cause) }
        ready.completeExceptionally(cause)
    }
}
