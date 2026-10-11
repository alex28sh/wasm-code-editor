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

private fun logInfo(message: String): Unit = js("console.info(message)")

private fun logWarning(message: String): Unit = js("console.warn(message)")

class LoadedPlugin(val id: String, val sandboxed: Boolean)

/** Where a plugin runs and what decided it, as the log says it. */
private enum class LoadWay(val sandboxed: Boolean, val description: String) {
    TRUSTED(false, "in the application, trusted by the index"),
    UNTRUSTED(true, "in a sandbox worker, not trusted by the index"),
    SANDBOX_ALL(true, "in a sandbox worker, trusted by the index but ?sandbox=all is set"),
    REQUESTED(true, "in a sandbox worker, loaded on request"),
}

class PluginLoadFailure(val module: String, val message: String)

class PluginLoadResult(val loaded: List<LoadedPlugin>, val failures: List<PluginLoadFailure>)

/**
 * Loads plugins in one of two tiers.
 *
 * A trusted plugin is imported into the host's instance group: its entry module links against the already loaded
 * stdlib and API modules through the ES module cache and registers itself in [PluginRegistry] from its `main`.
 * Any other plugin gets a [SandboxedPluginHost] of its own.
 *
 * Every load is written to the browser console: the plugins a module registered and the way it was loaded,
 * or the failure as a warning.
 */
object PluginLoader {
    /**
     * Loads every plugin of the index at [indexUrl]; a plugin that fails to load does not affect the others.
     *
     * With [sandboxAll] the trusted plugins are sandboxed too.
     */
    suspend fun loadAll(indexUrl: String, sandboxAll: Boolean = false): PluginLoadResult {
        val index = try {
            Json.decodeFromString<List<PluginIndexEntry>>(fetchText(indexUrl).await<JsString>().toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logWarning("[plugins] cannot read the index $indexUrl: ${e.message ?: e}")
            throw e
        }
        logInfo("[plugins] the index $indexUrl lists ${index.size}: ${index.joinToString { it.module }.ifEmpty { "nothing" }}")
        val loaded = mutableListOf<LoadedPlugin>()
        val failures = mutableListOf<PluginLoadFailure>()
        for (entry in index) {
            try {
                val way = when {
                    !entry.trusted -> LoadWay.UNTRUSTED
                    sandboxAll -> LoadWay.SANDBOX_ALL
                    else -> LoadWay.TRUSTED
                }
                loaded += load(entry.module, way)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failures += PluginLoadFailure(entry.module, e.message ?: e.toString())
            }
        }
        return PluginLoadResult(loaded, failures)
    }

    private suspend fun load(module: String, way: LoadWay): List<LoadedPlugin> {
        val loaded = try {
            if (way.sandboxed) loadInSandbox(module) else loadInProcess(module)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logWarning("[plugins] failed to load $module ${way.description}: ${e.message ?: e}")
            throw e
        }
        val plugins = loaded.joinToString { it.id }.ifEmpty { "no plugins" }
        logInfo("[plugins] loaded $plugins from $module ${way.description}")
        return loaded
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
    suspend fun loadSandboxed(module: String): List<LoadedPlugin> = load(module, LoadWay.REQUESTED)

    private suspend fun loadInSandbox(module: String): List<LoadedPlugin> {
        val sandbox = SandboxedPluginHost()
        try {
            return sandbox.loadPlugin("./$module.wasm").map { LoadedPlugin(it, sandboxed = true) }
        } catch (e: Throwable) {
            sandbox.terminate()
            throw e
        }
    }
}
