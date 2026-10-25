import java.io.DataOutputStream
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

// The Kotlin compiler as a WebAssembly module: the published JVM jars are compiled ahead
// of time by GraalVM Web Image (`native-image --tool:svm-wasm`), which only Oracle GraalVM 25.1+ has.
// Its location comes from `graalvm.home` in local.properties or from GRAALVM_HOME; without it the module
// builds nothing and the editor runs without a compiler.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val kotlinVersion = libs.versions.kotlin.get()

val graalvmHome: String? = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("graalvm.home") }
    ?: providers.environmentVariable("GRAALVM_HOME").orNull

fun graalvmTool(name: String) = "$graalvmHome/bin/$name"

val compilerJars: Configuration by configurations.creating
val stdlibKlib: Configuration by configurations.creating {
    isTransitive = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named("kotlin-runtime")) }
}

// A library the scenarios compile and analyze programs against, with its dependencies: what the editor sends when a
// user adds `kotlinx-coroutines-core` in the libraries panel.
val scenarioLibraries: Configuration by configurations.creating {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named("kotlin-runtime"))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.wasm)
        attribute(Attribute.of("org.jetbrains.kotlin.wasm.target", String::class.java), "js")
    }
    // The hosts have their own standard library, of the compiler's version.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

// The tool that rewrites the jars for Web Image; it needs the class-file API of JDK 24+ and runs on GraalVM's JDK.
val patcher: SourceSet by sourceSets.creating

dependencies {
    compilerJars("org.jetbrains.kotlin:kotlin-compiler:$kotlinVersion")
    stdlibKlib("org.jetbrains.kotlin:kotlin-stdlib-wasm-js:$kotlinVersion")
    scenarioLibraries("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinx.coroutines.get()}")
}

val imageJars: FileCollection = compilerJars

// Replacements for JDK and IntelliJ classes that do not work in the image, compiled into each of the images.
sourceSets.main { java.srcDir("src/shared/java") }

configurations.compileOnly { extendsFrom(compilerJars) }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    enabled = graalvmHome != null
    // GraalVM's own compiler: the JavaScript interop annotations are a module of that JDK.
    options.isFork = true
    options.forkOptions.javaHome = graalvmHome?.let(::file)
    // Web Image reads the parameter names of methods implemented in JavaScript.
    options.compilerArgs.add("-parameters")
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

tasks.named<JavaCompile>(patcher.compileJavaTaskName) {
    sourceCompatibility = "25"
    targetCompatibility = "25"
}

// `-PwebImage.debugNames` keeps the names of functions in the images, which makes their stack traces readable
// and the images half as large again.
val debugOptions = if (providers.gradleProperty("webImage.debugNames").isPresent) arrayOf("-H:+DebugNames") else emptyArray()

val patchedClasses = layout.buildDirectory.dir("patchedClasses")

val patchJars = tasks.register<JavaExec>("patchJars") {
    description = "Rewrites the classes of the compiler jars that GraalVM Web Image cannot build or run"
    enabled = graalvmHome != null
    executable(graalvmTool("java"))
    classpath = patcher.output
    mainClass = "editor.compilerhost.patcher.PatchJars"
    inputs.files(imageJars)
    outputs.dir(patchedClasses)
    argumentProviders.add { listOf(patchedClasses.get().asFile.path) + imageJars.files.map { it.path } }
    doFirst { delete(patchedClasses) }
}

// Compiler arguments are assigned through reflection, and which ones depends on the flags of each run.
val argumentsMetadata = tasks.register("argumentsMetadata") {
    val jars = compilerJars
    val output = layout.buildDirectory.file("argumentsMetadata/META-INF/native-image/editor/compiler-arguments/reachability-metadata.json")
    inputs.files(jars)
    outputs.dir(layout.buildDirectory.dir("argumentsMetadata"))
    doLast {
        val pattern = Regex("""org/jetbrains/kotlin/cli/common/arguments/\w*Arguments\.class""")
        val classes = jars.files.filter { it.name.startsWith("kotlin-compiler-") }.flatMap { jar ->
            ZipFile(jar).use { zip -> zip.entries().asSequence().map { it.name }.filter(pattern::matches).toList() }
        }.map { it.removeSuffix(".class").replace('/', '.') }.sorted()
        val entries = classes.joinToString(",\n") {
            """    {"type": "$it", "allDeclaredFields": true, "allDeclaredMethods": true, "allPublicMethods": true, "allDeclaredConstructors": true}"""
        }
        output.get().asFile.apply { parentFile.mkdirs() }.writeText("{\n  \"reflection\": [\n$entries\n  ]\n}\n")
    }
}

// The klib is a zip, and the image has neither a zip implementation nor a way to list a directory of the server,
// so the library travels as one file: a count, then the name, the size and the bytes of each entry.
val stdlibArchive = tasks.register("stdlibArchive") {
    description = "Packs the unpacked standard library klib into the one file the compiler host reads"
    val klib = stdlibKlib
    val output = layout.buildDirectory.file("stdlib/kotlin-stdlib-wasm-js.bin")
    inputs.files(klib)
    outputs.file(output)
    doLast {
        ZipFile(klib.singleFile).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }.toList()
            DataOutputStream(output.get().asFile.apply { parentFile.mkdirs() }.outputStream().buffered()).use { out ->
                out.writeInt(entries.size)
                for (entry in entries) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    out.writeUTF(entry.name)
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
        }
    }
}

// The libraries in the format of the `libraries` request: entries of each klib under a directory of its own,
// dependencies first. The editor makes the same in the page (kotlin-libraries.mjs) from what the user adds.
val scenarioLibraryArchive = tasks.register("scenarioLibraryArchive") {
    description = "Packs the klibs of the scenario library and its dependencies into one archive for the hosts"
    val libraries = scenarioLibraries
    val output = layout.buildDirectory.file("scenarioLibraries/libraries.bin")
    inputs.files(libraries)
    outputs.file(output)
    doLast {
        val files = libraries.incoming.artifacts.artifacts.associate { it.id.componentIdentifier.displayName to it.file }
        // Depth first, so that every klib comes after the ones it depends on.
        val ordered = mutableListOf<File>()
        val visited = mutableSetOf<String>()
        fun visit(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            val name = component.id.displayName
            if (!visited.add(name)) return
            component.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { visit(it.selected) }
            files[name]?.let(ordered::add)
        }
        libraries.incoming.resolutionResult.root.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { visit(it.selected) }
        val entries = ordered.withIndex().flatMap { (index, klib) ->
            val directory = "$index-${klib.name.removeSuffix(".klib")}"
            ZipFile(klib).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.sortedBy { it.name }
                    .map { "$directory/${it.name}" to zip.getInputStream(it).use { input -> input.readBytes() } }.toList()
            }
        }
        DataOutputStream(output.get().asFile.apply { parentFile.mkdirs() }.outputStream().buffered()).use { out ->
            out.writeInt(entries.size)
            for ((name, bytes) in entries) {
                out.writeUTF(name)
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
        logger.lifecycle("Scenario libraries: ${ordered.joinToString { it.name }}")
    }
}

val hostClasses = sourceSets.main.get().output

// What the image is built from, in the order that lets the rewritten classes shadow the ones in the jars.
val imageClasspath = files(patchedClasses, hostClasses, layout.buildDirectory.dir("argumentsMetadata"), imageJars)
    .builtBy(patchJars, argumentsMetadata)

val agentMetadata = layout.buildDirectory.dir("agentMetadata")

val jvmScenario = tasks.register<JavaExec>("jvmScenario") {
    description = "Runs the editor's requests on the JVM, recording what the image has to keep reachable for them"
    group = "verification"
    enabled = graalvmHome != null
    executable(graalvmTool("java"))
    classpath = imageClasspath
    mainClass = "editor.compilerhost.JvmScenarioKt"
    val archive = stdlibArchive.map { it.outputs.files.singleFile }
    inputs.file(archive)
    outputs.dir(agentMetadata)
    val sample = layout.projectDirectory.file("src/scenario/shapes.kt")
    inputs.file(sample)
    val libraryArchive = scenarioLibraryArchive.map { it.outputs.files.singleFile }
    inputs.file(libraryArchive)
    argumentProviders.add { listOf(archive.get().path, sample.asFile.path, libraryArchive.get().path) }
    jvmArgumentProviders.add {
        listOf(
            "-agentlib:native-image-agent=config-output-dir=${agentMetadata.get().asFile}/META-INF/native-image/editor/compiler-host",
            "--enable-native-access=ALL-UNNAMED",
        )
    }
    doFirst { delete(agentMetadata) }
}

val imageDirectory = layout.buildDirectory.dir("webImage")

val webImage = tasks.register<Exec>("webImage") {
    description = "Compiles the compiler host to WebAssembly with GraalVM Web Image"
    group = "build"
    enabled = graalvmHome != null
    val classpath = imageClasspath + files(agentMetadata).builtBy(jvmScenario)
    inputs.files(classpath)
    inputs.property("debugOptions", debugOptions.toList())
    outputs.dir(imageDirectory)
    doFirst {
        delete(imageDirectory)
        mkdir(imageDirectory)
        commandLine(
            graalvmTool("native-image"),
            "--tool:svm-wasm",
            "-J-Xmx24g",
            "-Os",
            "-H:+AddAllCharsets",
            "-H:+UnlockExperimentalVMOptions",
            *debugOptions,
            // The functions that fill the image heap are otherwise larger than V8 accepts.
            "-H:ImageHeapObjectsPerFunction=20000",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "-cp", classpath.asPath,
            "-o", imageDirectory.get().file("kotlin-compiler").asFile.path,
            "editor.compilerhost.web.WebMain",
        )
    }
    // The text form of the module is written next to it and is more than a gigabyte.
    doLast { delete(imageDirectory.get().file("kotlin-compiler.js.wat")) }
}

// ---- The Analysis API, as a second image. ----
// It cannot share the compiler jar above, which is shrunk by ProGuard: it needs the unshrunk compiler modules
// and the IntelliJ platform modules they are built against.

val intellijVersion = "261.24374.151"
val analysisJars: Configuration by configurations.creating { isTransitive = false }

dependencies {
    listOf(
        "analysis-api-for-ide", "analysis-api-k2-for-ide", "analysis-api-impl-base-for-ide",
        "analysis-api-platform-interface-for-ide", "analysis-api-standalone-for-ide", "low-level-api-fir-for-ide",
        "symbol-light-classes-for-ide",
        "kotlin-compiler-common-for-ide", "kotlin-compiler-fir-for-ide", "kotlin-compiler-ir-for-ide",
        "kotlin-compiler-fe10-for-ide", "kotlin-compiler-cli-for-ide",
    ).forEach { analysisJars("org.jetbrains.kotlin:$it:$kotlinVersion") }
    listOf(
        "platform:util-base", "platform:util", "platform:core", "platform:core-impl", "platform:extensions",
        "java:java-frontback-psi", "java:java-psi",
        "platform:util-base-multiplatform", "platform:util-class-loader", "platform:util-multiplatform", "platform:util-rt",
        "platform:util-xml-dom", "java:java-frontback-psi-impl", "java:java-psi-impl", "java:java-syntax", "platform:eel",
        "platform:plugin-system-parser-impl", "platform:syntax", "platform:syntax-extensions", "platform:syntax-psi",
        "platform:diagnostic", "platform:diagnostic-telemetry", "platform:syntax-i18-n", "platform:syntax-util",
        "platform:util-progress", "platform:util-coroutines",
    ).forEach { analysisJars("com.jetbrains.intellij.$it:$intellijVersion") }
    listOf(
        "org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion",
        "org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion",
        "org.jetbrains.kotlin:kotlin-reflect:1.6.10",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.9.0",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.9.0",
        "org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.5.1",
        "org.jetbrains:annotations:24.1.0",
        "com.google.guava:guava:33.3.1-jre",
        "com.google.guava:failureaccess:1.0.2",
        "com.github.ben-manes.caffeine:caffeine:2.9.3",
        "org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.14-jb1",
        "org.jetbrains.intellij.deps:asm-all:9.6.1",
        "org.jetbrains.intellij.deps:trove4j:1.0.20200330",
        "org.jetbrains.intellij.deps:log4j:1.2.17.2",
        "org.jetbrains.intellij.deps:jdom:2.0.6",
        "org.jetbrains.intellij.deps.jna:jna:5.9.0.26",
        "org.jetbrains.intellij.deps.jna:jna-platform:5.9.0.26",
        "org.codehaus.woodstox:stax2-api:4.2.1",
        "com.fasterxml:aalto-xml:1.3.0",
        "one.util:streamex:0.7.2",
        "org.lz4:lz4-java:1.7.1",
        "javax.inject:javax.inject:1",
        "io.vavr:vavr:0.10.4",
        "io.opentelemetry:opentelemetry-api:1.41.0",
        "io.opentelemetry:opentelemetry-context:1.41.0",
    ).forEach { analysisJars(it) }
}

val analysis: SourceSet by sourceSets.creating {
    // First, so that the annotations the Kotlin plugin adds with its standard library do not shadow the newer ones.
    compileClasspath = analysisJars + compileClasspath
    // The classes the Kotlin repository adds to the IntelliJ platform and replaces in it when it builds its compiler
    // (dependencies/intellij-core-implementation and intellij-java-psi-api at the tag of this Kotlin version).
    // No published artifact has them unshrunk, and the compiler modules for the IDE need them outside of the IDE.
    java.srcDir("src/analysis/intellij-core")
    java.srcDir("src/shared/java")
}

kotlin.sourceSets.named("analysis") { kotlin.srcDir("src/analysis/intellij-core") }

val patchedAnalysisClasses = layout.buildDirectory.dir("patchedAnalysisClasses")

val patchAnalysisJars = tasks.register<JavaExec>("patchAnalysisJars") {
    description = "Rewrites the classes of the Analysis API jars that GraalVM Web Image cannot build or run"
    enabled = graalvmHome != null
    executable(graalvmTool("java"))
    classpath = patcher.output
    mainClass = "editor.compilerhost.patcher.PatchJars"
    inputs.files(analysisJars)
    outputs.dir(patchedAnalysisClasses)
    argumentProviders.add { listOf(patchedAnalysisClasses.get().asFile.path) + analysisJars.files.map { it.path } }
    doFirst { delete(patchedAnalysisClasses) }
}

val analysisImageClasspath = files(patchedAnalysisClasses, analysis.output, analysisJars).builtBy(patchAnalysisJars)
val analysisAgentMetadata = layout.buildDirectory.dir("analysisAgentMetadata")

val analysisScenario = tasks.register<JavaExec>("analysisScenario") {
    description = "Runs the Analysis API requests on the JVM, recording what the image has to keep reachable for them"
    group = "verification"
    enabled = graalvmHome != null
    executable(graalvmTool("java"))
    classpath = analysisImageClasspath
    mainClass = "editor.analysishost.AnalysisScenarioKt"
    val archive = stdlibArchive.map { it.outputs.files.singleFile }
    inputs.file(archive)
    outputs.dir(analysisAgentMetadata)
    val sample = layout.projectDirectory.file("src/scenario/shapes.kt")
    inputs.file(sample)
    val libraryArchive = scenarioLibraryArchive.map { it.outputs.files.singleFile }
    inputs.file(libraryArchive)
    argumentProviders.add { listOf(archive.get().path, sample.asFile.path, libraryArchive.get().path) }
    jvmArgumentProviders.add {
        listOf(
            "-agentlib:native-image-agent=config-output-dir=${analysisAgentMetadata.get().asFile}/META-INF/native-image/editor/analysis-host",
            "--enable-native-access=ALL-UNNAMED",
        )
    }
    doFirst { delete(analysisAgentMetadata) }
}

val analysisImageDirectory = layout.buildDirectory.dir("analysisImage")

val analysisImage = tasks.register<Exec>("analysisImage") {
    description = "Compiles the Analysis API host to WebAssembly with GraalVM Web Image"
    group = "build"
    enabled = graalvmHome != null
    val classpath = analysisImageClasspath + files(analysisAgentMetadata).builtBy(analysisScenario)
    inputs.files(classpath)
    inputs.property("debugOptions", debugOptions.toList())
    outputs.dir(analysisImageDirectory)
    doFirst {
        delete(analysisImageDirectory)
        mkdir(analysisImageDirectory)
        commandLine(
            graalvmTool("native-image"),
            "--tool:svm-wasm",
            "-J-Xmx28g",
            "-Os",
            "-H:+AddAllCharsets",
            "-H:+UnlockExperimentalVMOptions",
            *debugOptions,
            "-H:ImageHeapObjectsPerFunction=20000",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "-cp", classpath.asPath,
            "-o", analysisImageDirectory.get().file("kotlin-analysis").asFile.path,
            "editor.analysishost.web.WebMain",
        )
    }
    doLast { delete(analysisImageDirectory.get().file("kotlin-analysis.js.wat")) }
}

// ---- What the editor serves. ----

val compilerDist = tasks.register("compilerDist") {
    description = "Everything the editor serves to compile and analyze Kotlin in the browser"
    group = "distribution"
    enabled = graalvmHome != null
    val images = mapOf("kotlin-compiler" to imageDirectory, "kotlin-analysis" to analysisImageDirectory)
    val archive = stdlibArchive.map { it.outputs.files.singleFile }
    val glue = layout.projectDirectory.file("src/main/js/worker-glue.js")
    val output = layout.buildDirectory.dir("compilerDist")
    dependsOn(webImage, analysisImage)
    images.values.forEach { inputs.dir(it) }
    inputs.file(archive)
    inputs.file(glue)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        for ((name, image) in images) {
            // The generated script loads `<its own address>.wasm`, so the worker script and the module share a name.
            val script = image.get().file("$name.js").asFile.readText() + glue.asFile.readText()
            directory.resolve("$name-worker.js").writeText(script)
            image.get().file("$name.js.wasm").asFile.copyTo(directory.resolve("$name-worker.js.wasm"))
        }
        archive.get().copyTo(directory.resolve(archive.get().name))
    }
}

tasks.register<Exec>("imageTest") {
    description = "Compiles, runs and analyzes programs with the WebAssembly images under Node.js"
    group = "verification"
    enabled = graalvmHome != null
    val dist = compilerDist.map { it.outputs.files.singleFile }
    val libraries = scenarioLibraryArchive.map { it.outputs.files.singleFile }
    inputs.dir(dist)
    inputs.file(libraries)
    inputs.dir("src/test/js")
    inputs.dir("src/scenario")
    // No larger stack than the default: a browser gives the images no more.
    doFirst {
        val arguments = "'${dist.get().path}' '${libraries.get().path}'"
        commandLine("sh", "-c", "node src/test/js/image-test.cjs $arguments && node src/test/js/analysis-image-test.cjs $arguments")
    }
}
