plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    wasmJs {
        browser()
    }

    sourceSets {
        wasmJsMain.dependencies {
            api(libs.kotlinx.browser)
            implementation(project(":language-kotlin"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(npm("codemirror", libs.versions.codemirror.setup.get()))
            implementation(npm("@codemirror/state", libs.versions.codemirror.state.get()))
            implementation(npm("@codemirror/view", libs.versions.codemirror.view.get()))
            implementation(npm("@codemirror/language", libs.versions.codemirror.language.get()))
            implementation(npm("@codemirror/legacy-modes", libs.versions.codemirror.legacy.modes.get()))
            implementation(npm("@codemirror/commands", libs.versions.codemirror.commands.get()))
            implementation(npm("@codemirror/search", libs.versions.codemirror.search.get()))
            implementation(npm("@codemirror/autocomplete", libs.versions.codemirror.autocomplete.get()))
            implementation(npm("@codemirror/lint", libs.versions.codemirror.lint.get()))
        }
    }
}
