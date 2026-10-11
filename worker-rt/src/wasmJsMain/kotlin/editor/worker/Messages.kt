package editor.worker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed interface WorkerRequest {
    val requestId: Int
}

/** Asks the worker to instantiate the plugin binary at [wasmUrl] inside its own instance group. */
@Serializable
@SerialName("loadPlugin")
data class LoadPlugin(override val requestId: Int, val wasmUrl: String) : WorkerRequest

/** Anything the worker sends to the host. */
@Serializable
sealed interface WorkerEvent

/** Sent once the worker has installed its message handler; the host sends nothing before it arrives. */
@Serializable
@SerialName("ready")
data object WorkerReady : WorkerEvent

@Serializable
sealed interface WorkerResponse : WorkerEvent {
    val requestId: Int
}

@Serializable
@SerialName("pluginsLoaded")
data class PluginsLoaded(override val requestId: Int, val pluginIds: List<String>) : WorkerResponse

@Serializable
@SerialName("requestFailed")
data class RequestFailed(override val requestId: Int, val message: String) : WorkerResponse

internal val messageJson = Json { ignoreUnknownKeys = true }
