package editor.plugin.api

/**
 * Entry point every plugin implements.
 *
 * A plugin registers one instance in [PluginRegistry] when its module is loaded.
 */
interface EditorPlugin {
    val id: String

    fun activate()

    fun deactivate()
}

/**
 * Registry shared by the host and all in-process plugins.
 *
 * It lives in the API module so that there is exactly one instance per instance group.
 */
object PluginRegistry {
    private val registered = mutableMapOf<String, EditorPlugin>()

    val plugins: Collection<EditorPlugin>
        get() = registered.values

    fun register(plugin: EditorPlugin) {
        check(plugin.id !in registered) { "Plugin '${plugin.id}' is already registered" }
        registered[plugin.id] = plugin
    }
}
