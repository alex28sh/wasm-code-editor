package editor.app

import editor.plugin.api.PluginRegistry
import editor.worker.SandboxedPluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.js.Promise

private fun importModule(url: String): Promise<JsAny> =
    js("import(/* webpackIgnore: true */ url)")

private fun fetchText(url: String): Promise<JsString> =
    js("fetch(url).then(response => response.ok ? response.text() : Promise.reject(new Error('Cannot fetch ' + url)))")

/** One entry of the plugin index: [module] is the base name of the plugin's `.mjs` and `.wasm` files. */
@Serializable
data class PluginIndexEntry(val module: String, val trusted: Boolean)

class LoadedPlugin(val id: String, val sandboxed: Boolean)

class PluginLoadFailure(val module: String, val message: String)

class PluginLoadResult(val loaded: List<LoadedPlugin>, val failures: List<PluginLoadFailure>)

/**
 * Loads plugins in one of two tiers.
 *
 * A trusted plugin is imported into the host's instance group: its entry module links against the already loaded
 * stdlib and API modules through the ES module cache and registers itself in [PluginRegistry] from its `main`.
 * Any other plugin gets a [SandboxedPluginHost] of its own.
 */
object PluginLoader {
    /**
     * Loads every plugin of the index at [indexUrl]; a plugin that fails to load does not affect the others.
     *
     * With [sandboxAll] the trusted plugins are sandboxed too.
     */
    suspend fun loadAll(indexUrl: String, sandboxAll: Boolean = false): PluginLoadResult {
        val index = Json.decodeFromString<List<PluginIndexEntry>>(fetchText(indexUrl).await<JsString>().toString())
        val loaded = mutableListOf<LoadedPlugin>()
        val failures = mutableListOf<PluginLoadFailure>()
        for (entry in index) {
            try {
                loaded += if (entry.trusted && !sandboxAll) loadInProcess(entry.module) else loadSandboxed(entry.module)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += PluginLoadFailure(entry.module, e.message ?: e.toString())
            }
        }
        return PluginLoadResult(loaded, failures)
    }

    private suspend fun loadInProcess(module: String): List<LoadedPlugin> {
        val known = PluginRegistry.plugins.toSet()
        importModule("./$module.mjs").await<JsAny>()
        return PluginRegistry.plugins.filter { it !in known }.map { plugin ->
            plugin.activate()
            LoadedPlugin(plugin.id, sandboxed = false)
        }
    }

    /** Loads the plugin [module] into a new worker; the worker does not outlive a failed load. */
    suspend fun loadSandboxed(module: String): List<LoadedPlugin> {
        val sandbox = SandboxedPluginHost()
        try {
            return sandbox.loadPlugin("./$module.wasm").map { LoadedPlugin(it, sandboxed = true) }
        } catch (e: Throwable) {
            sandbox.terminate()
            throw e
        }
    }
}
