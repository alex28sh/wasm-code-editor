// The Kotlin compiler's own lexer and parser, as Kotlin/Wasm: the multiplatform parser of the Kotlin repository
// (upstream/, unchanged; see upstream/UPSTREAM.md) and a small API the editor uses (src/).

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    wasmJs {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir("upstream")
            dependencies {
                implementation(libs.jetbrains.syntax.api)
                implementation(libs.jetbrains.annotations)
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
