import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
}

// First-party plugins are trusted: they run in the host's instance group.
// Plugins are linked against the host at runtime through the ES module cache, so everything is served
// as unbundled ES modules from one directory: the host's modules plus each plugin's own files.
val pluginProjects = listOf(":plugins:hello")

// Plugins that only the end-to-end tests load: misbehaving ones and ones exercising the registry.
val testPluginProjects = listOf(
    ":test-plugins:spinning",
    ":test-plugins:reaching",
    ":test-plugins:throwing",
    ":test-plugins:pair",
    ":test-plugins:impostor",
)

// Host modules a sandboxed plugin is allowed to link against.
val sandboxSharedModules = listOf("kotlin-kotlin-stdlib", "wasm-code-editor-plugin-api")

val compileTask = "wasmJsDevelopmentExecutableCompileSync"

// JavaScript modules that Kotlin modules import by a relative path. Only the application's own resources reach
// its package directory, so those of the libraries are copied into the distribution from here.
val scriptModules = files("editor-codemirror/src/wasmJsMain/resources")

fun wasmModuleName(projectPath: String) = rootProject.name + projectPath.replace(':', '-')

fun wasmPackageDir(projectPath: String) =
    layout.buildDirectory.dir("wasm/packages/${wasmModuleName(projectPath)}/kotlin")

val generatePluginIndex by tasks.registering {
    val modules = pluginProjects.map { wasmModuleName(it) }
    val output = layout.buildDirectory.file("pluginIndex/plugins.json")
    inputs.property("modules", modules)
    outputs.file(output)
    doLast {
        val entries = modules.joinToString(",\n") { """  { "module": "$it", "trusted": true }""" }
        output.get().asFile.writeText("[\n$entries\n]\n")
    }
}

// The Wasm import module name of a Kotlin module (like `<kotlin>`) appears only in the glue of its dependants,
// next to the file that provides it, so the mapping is collected from there.
val generateSharedModulesIndex by tasks.registering {
    dependsOn(":app:$compileTask")
    val hostDir = wasmPackageDir(":app")
    val allowed = sandboxSharedModules
    val output = layout.buildDirectory.file("sharedModulesIndex/shared-modules.json")
    inputs.dir(hostDir)
    inputs.property("allowed", allowed)
    outputs.file(output)
    doLast {
        val aliasToFile = Regex("""import \{ __ALL_EXPORTS as (\w+) \} from '\./(.+)\.mjs'""")
        val nameToAlias = Regex("""'(<[^']+>)': (\w+),""")
        val files = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        hostDir.get().asFile.listFiles { file -> file.name.endsWith(".import-object.mjs") }!!.forEach { glue ->
            val text = glue.readText()
            aliasToFile.findAll(text).forEach { files[it.groupValues[1]] = it.groupValues[2] }
            nameToAlias.findAll(text).forEach { names[it.groupValues[1]] = it.groupValues[2] }
        }
        val shared = names.mapValues { files.getValue(it.value) }.filterValues { it in allowed }
        check(shared.size == allowed.size) { "Cannot find Wasm module names for ${allowed - shared.values.toSet()}" }
        val entries = shared.entries.sortedBy { it.key }.joinToString(",\n") { """  "${it.key}": "./${it.value}.mjs"""" }
        output.get().asFile.writeText("{\n$entries\n}\n")
    }
}

// Without a bundler the browser has to resolve npm package names itself, which is what an import map is for.
// It covers the packages the generated glue and the script modules import plus everything they depend on.
val generateImportMap by tasks.registering {
    dependsOn(":kotlinWasmNpmInstall", ":app:$compileTask")
    val hostDir = wasmPackageDir(":app")
    val nodeModules = layout.buildDirectory.dir("wasm/node_modules")
    val output = layout.buildDirectory.file("importMap/importmap.json")
    val scripts = scriptModules.asFileTree.matching { include("*.mjs") }
    inputs.dir(hostDir)
    inputs.files(scripts)
    outputs.file(output)
    doLast {
        fun packageJson(name: String) =
            JsonSlurper().parse(nodeModules.get().asFile.resolve("$name/package.json")) as Map<*, *>

        fun importTarget(value: Any?): String? = when (value) {
            is String -> value
            is Map<*, *> -> importTarget(value["import"] ?: value["default"])
            else -> null
        }

        // Resolves `subpath` ("." or "./some/path") of a package the way Node does for ES module imports.
        fun resolve(name: String, subpath: String): String? {
            val pkg = packageJson(name)
            val exports = pkg["exports"]
            val target = when {
                exports is Map<*, *> && exports.keys.none { (it as String).startsWith(".") } ->
                    importTarget(exports).takeIf { subpath == "." }
                exports is Map<*, *> -> importTarget(exports[subpath]) ?: exports.entries.firstNotNullOfOrNull { (key, value) ->
                    val parts = (key as String).split('*')
                    if (parts.size != 2 || !subpath.startsWith(parts[0]) || !subpath.endsWith(parts[1])) return@firstNotNullOfOrNull null
                    importTarget(value)?.replace("*", subpath.substring(parts[0].length, subpath.length - parts[1].length))
                }
                else -> (exports ?: pkg["module"] ?: pkg["main"]).takeIf { subpath == "." } as String?
            }
            return target?.let { "./vendor/$name/${it.removePrefix("./")}" }
        }

        val bareImport = Regex("""from '([^./'][^']*)'""")
        val specifiers = (hostDir.get().asFile.listFiles { file -> file.name.endsWith(".import-object.mjs") }!!.toList() + scripts.files)
            .flatMap { glue -> bareImport.findAll(glue.readText()).map { it.groupValues[1] } }
            .toSortedSet()

        val imports = sortedMapOf<String, String>()
        val pending = ArrayDeque<String>()
        for (specifier in specifiers) {
            val segments = specifier.split('/')
            val name = segments.take(if (specifier.startsWith("@")) 2 else 1).joinToString("/")
            val subpath = segments.drop(name.count { it == '/' } + 1).joinToString("/")
            imports[specifier] = resolve(name, if (subpath.isEmpty()) "." else "./$subpath")
                ?: error("Cannot resolve npm import '$specifier'")
            pending += name
        }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            resolve(name, ".")?.let { imports[name] = it }
            (packageJson(name)["dependencies"] as Map<*, *>?)?.keys?.forEach { pending += it as String }
        }
        output.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("imports" to imports))) + "\n")
    }
}

tasks.register<Sync>("devDist") {
    description = "Assembles the host and all plugins as unbundled ES modules"
    group = "distribution"
    dependsOn(":app:$compileTask")
    val importMap = generateImportMap.map { it.outputs.files.singleFile }
    inputs.file(importMap)
    from(wasmPackageDir(":app")) {
        filesMatching("index.html") {
            filter { line ->
                if ("<!-- importmap -->" in line) "<script type=\"importmap\">\n${importMap.get().readText()}</script>" else line
            }
        }
    }
    from(layout.buildDirectory.dir("wasm/node_modules")) {
        into("vendor")
        exclude("${rootProject.name}-*", "skiko-*", ".*")
    }
    (pluginProjects + ":plugin-worker").forEach { project ->
        dependsOn("$project:$compileTask")
        from(wasmPackageDir(project)) {
            include("${wasmModuleName(project)}.*")
        }
    }
    from(scriptModules)
    // The compiler and the analysis are there only when GraalVM is: see compiler-host.
    project(":compiler-host").tasks.named("compilerDist").let { compilerDist ->
        if (compilerDist.get().enabled) from(compilerDist)
    }
    from(generatePluginIndex)
    from(generateSharedModulesIndex)
    into(layout.buildDirectory.dir("devDist"))
}

val e2eDist by tasks.registering(Sync::class) {
    description = "Assembles the distribution the end-to-end tests run against: devDist plus the test plugins"
    group = "verification"
    from(tasks.named("devDist"))
    testPluginProjects.forEach { project ->
        dependsOn("$project:$compileTask")
        from(wasmPackageDir(project)) {
            include("${wasmModuleName(project)}.*")
        }
    }
    into(layout.buildDirectory.dir("e2eDist"))
}

tasks.register<Exec>("e2eTest") {
    description = "Runs the browser tests in e2e/ against the end-to-end distribution (needs Node.js and Chrome)"
    group = "verification"
    dependsOn(e2eDist)
    workingDir = file("e2e")
    commandLine("npx", "playwright", "test")
}
