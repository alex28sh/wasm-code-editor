pluginManagement {
    repositories {
        maven("https://packages.jetbrains.team/maven/p/kt/bootstrap")
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://packages.jetbrains.team/maven/p/kt/bootstrap")
        // The Analysis API is published only as the artifacts the IntelliJ Kotlin plugin is built from,
        // and runs on the modules of the IntelliJ platform.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content {
                includeModuleByRegex("org\\.jetbrains\\.kotlin", ".*-for-ide")
                includeGroupByRegex("org\\.jetbrains\\.intellij\\.deps.*")
                // IntelliJ's multiplatform syntax library, which the Kotlin parser of kotlin-parser is written against.
                includeModuleByRegex("org\\.jetbrains", "(syntax-api|multiplatform)(-.*)?")
            }
        }
        maven("https://www.jetbrains.com/intellij-repository/releases") {
            content { includeGroupByRegex("com\\.jetbrains\\.intellij\\..*") }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "wasm-code-editor"

include(":plugin-api")
include(":worker-rt")
include(":plugin-worker")
include(":language-kotlin")
include(":kotlin-parser")
include(":editor-codemirror")
include(":app")
include(":compiler-host")
include(":plugins:hello")

// Plugins the end-to-end tests load to check the loader and the sandbox; only the end-to-end distribution contains them.
include(":test-plugins:spinning")
include(":test-plugins:reaching")
include(":test-plugins:throwing")
include(":test-plugins:pair")
include(":test-plugins:impostor")
