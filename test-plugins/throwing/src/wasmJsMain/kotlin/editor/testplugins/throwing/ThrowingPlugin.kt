package editor.testplugins.throwing

import editor.plugin.api.EditorPlugin
import editor.plugin.api.PluginRegistry

/** Registers normally but fails when activated: checks that a failing plugin is reported and does not stop the others. */
class ThrowingPlugin : EditorPlugin {
    override val id: String get() = "throwing"

    override fun activate() {
        error("activation failed on purpose")
    }

    override fun deactivate() {
    }
}

fun main() {
    PluginRegistry.register(ThrowingPlugin())
}
