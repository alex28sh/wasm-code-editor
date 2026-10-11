package editor.testplugins.impostor

import editor.plugin.api.EditorPlugin
import editor.plugin.api.PluginRegistry

/** Claims the id of the hello plugin: checks that ids are unique per instance group and that workers have a registry of their own. */
class ImpostorPlugin : EditorPlugin {
    override val id: String get() = "hello"

    override fun activate() {
        println("impostor activated")
    }

    override fun deactivate() {
    }
}

fun main() {
    PluginRegistry.register(ImpostorPlugin())
}
