package editor.plugins.hello

import editor.plugin.api.EditorPlugin
import editor.plugin.api.PluginRegistry

class HelloPlugin : EditorPlugin {
    override val id: String get() = "hello"

    override fun activate() {
        println("hello plugin activated")
    }

    override fun deactivate() {
        println("hello plugin deactivated")
    }
}

fun main() {
    PluginRegistry.register(HelloPlugin())
}
