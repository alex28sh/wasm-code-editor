package editor.worker

import kotlinx.coroutines.await
import kotlin.js.Promise

/** Maps the Wasm import module names a sandboxed plugin may link against to the host modules providing them. */
const val SHARED_MODULES_INDEX_URL = "./shared-modules.json"

// The compile options and the string constants proxy mirror the glue the compiler generates for every module.
// Resolves to the error message, or to null on success: a JavaScript error rethrown through Kotlin loses its text.
private fun instantiateRestrictedJs(wasmUrl: String, sharedModulesIndexUrl: String): Promise<JsString?> = js(
    """
    (async () => {
        const shared = await (await fetch(sharedModulesIndexUrl)).json();
        const module = await WebAssembly.compileStreaming(fetch(wasmUrl), { builtins: [''], importedStringConstants: "'" });
        const imports = { "'": new Proxy({}, { get(_, prop) { return prop; } }) };
        for (const descriptor of WebAssembly.Module.imports(module)) {
            const name = descriptor.module;
            if (name in imports) continue;
            if (name === 'intrinsics') {
                imports.intrinsics = { tag: (await import(shared['<kotlin>'])).__TAG };
                continue;
            }
            const file = shared[name];
            if (file === undefined) {
                return "Plugin " + wasmUrl + " imports '" + name + "', which is not available to sandboxed plugins";
            }
            imports[name] = (await import(file)).__ALL_EXPORTS;
        }
        const instance = await WebAssembly.instantiate(module, imports);
        instance.exports._start();
        return null;
    })().catch(error => "Cannot load plugin " + wasmUrl + ": " + (error?.message ?? error))
    """
)

/**
 * Instantiates the plugin binary at [wasmUrl] without running any JavaScript shipped with the plugin.
 *
 * The plugin is linked only against the modules listed in the index at [sharedModulesIndexUrl],
 * so it has no access to JavaScript APIs or to host modules outside of that list.
 * Its `main` runs as part of the instantiation.
 *
 * @throws PluginLinkException if the plugin cannot be loaded
 */
suspend fun instantiateRestricted(wasmUrl: String, sharedModulesIndexUrl: String = SHARED_MODULES_INDEX_URL) {
    val error = instantiateRestrictedJs(wasmUrl, sharedModulesIndexUrl).await<JsString?>()
    if (error != null) throw PluginLinkException(error.toString())
}

/** The plugin binary cannot be fetched, compiled or linked against the modules available to it. */
class PluginLinkException(message: String) : Exception(message)
