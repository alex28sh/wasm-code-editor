package editor.testplugins.pair

import editor.plugin.api.EditorPlugin
import editor.plugin.api.PluginRegistry

/** Registers two plugins from one module: checks that a module is not limited to a single plugin. */
class PairPlugin(override val id: String) : EditorPlugin {
    override fun activate() {
        println("$id activated")
    }

    override fun deactivate() {
    }
}

fun main() {
    PluginRegistry.register(PairPlugin("pair-first"))
    PluginRegistry.register(PairPlugin("pair-second"))
}
