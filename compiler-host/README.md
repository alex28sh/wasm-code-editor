# Kotlin compiler and Analysis API in the browser

This module turns the published JVM jars of the Kotlin compiler and of the Analysis API into two WebAssembly
modules that run in web workers of the editor. The editor uses them to compile and run the document
(Ctrl+Enter), to mark errors and warnings, and to complete code.

Nothing here is compiled from Kotlin compiler sources. The jars are compiled ahead of time by
[GraalVM Web Image](https://www.graalvm.org/dev/reference-manual/web-image/) (`native-image --tool:svm-wasm`),
after a bytecode pass that works around what Web Image cannot build or run.

## Requirements

- **Oracle GraalVM 25.1 or later.** Web Image is not in GraalVM Community: it rejects `--tool:svm-wasm`.
  The build was made with Oracle GraalVM 25i4 (`25.4.4.1.1`), which is under Oracle's GraalVM Free Terms and Conditions.
- Its location, as `graalvm.home=<path>` in the root `local.properties`, or in the `GRAALVM_HOME` environment variable.
- Node.js, for `imageTest`.
- About 10 GB of free memory for each image build; `native-image` is given up to 24–28 GB of heap.

Without GraalVM every task of this module is disabled, `devDist` contains no compiler, and the editor works
as it did before, with its own name-based completion and no Ctrl+Enter.

## Commands

```bash
./gradlew :compiler-host:compilerDist   # both images and the standard library, into build/compilerDist
./gradlew :compiler-host:imageTest      # compile, run, analyze and complete with the images under Node.js
./gradlew :compiler-host:jvmScenario :compiler-host:analysisScenario   # the same requests on the JVM, in seconds
./gradlew devDist                       # the editor with the compiler in it
cd e2e && npx playwright test compiler  # the browser tests
```

Each image takes about 80–90 seconds to build. `-PwebImage.debugNames` keeps function names in the images,
which makes their stack traces readable and the images about half as large again.

## What is built

| File in `build/compilerDist` | Size | What it is |
|---|---|---|
| `kotlin-compiler-worker.js` + `.js.wasm` | 75 MB | the compiler for the Wasm target |
| `kotlin-analysis-worker.js` + `.js.wasm` | 75 MB | the Analysis API |
| `kotlin-stdlib-wasm-js.bin` | 12 MB | the standard library klib, repacked |

The root `devDist` task copies these next to the application. The sizes are uncompressed; in the experiment
before this module a compiler image of 87 MB was 23 MB with Brotli.

### The compiler image

- From `org.jetbrains.kotlin:kotlin-compiler` of the Kotlin version in `gradle/libs.versions.toml`, with its
  transitive dependencies, from the bootstrap repository.
- The host is `CompilerHost.kt`. It drives `KotlinWasmCompiler` through its command-line arguments in two runs,
  because the compiler makes a library from sources and an executable from libraries:
  1. sources to an unpacked klib (`-nopack`);
  2. that klib to an executable (`-Xinclude`, `-Xir-produce-js`, `-Xir-dce`).
- Messages are collected through a `MessageCollector`, and their line and column are turned into offsets.

### The Analysis API image

It is a separate image because `kotlin-compiler` is shrunk by ProGuard: methods the compiler does not call are
removed from the IntelliJ classes in it, and the Analysis API fails on the first of them. It is built from:

- the Analysis API as published for the IntelliJ Kotlin plugin: `analysis-api-for-ide`, `analysis-api-k2-for-ide`,
  `analysis-api-impl-base-for-ide`, `analysis-api-platform-interface-for-ide`, `analysis-api-standalone-for-ide`,
  `low-level-api-fir-for-ide`, `symbol-light-classes-for-ide`;
- the unshrunk compiler modules: `kotlin-compiler-common-for-ide`, `-fir-`, `-ir-`, `-fe10-`, `-cli-for-ide`;
- 26 modules of the IntelliJ platform `261.24374.151`, the version the Kotlin repository names in
  `gradle/versions.properties` (`versions.intellijSdk`) at the tag of the Kotlin version;
- 26 libraries (Guava, Caffeine, fastutil, kotlinx-collections-immutable, coroutines, serialization, and so on);
- `src/analysis/intellij-core`: 36 source files copied from the Kotlin repository,
  `dependencies/intellij-core-implementation/src` and `dependencies/intellij-java-psi-api/src` at the tag
  `build-2.5.0-dev-9169`. They are classes the Kotlin build adds to the IntelliJ platform or replaces in it
  (`JavaCoreApplicationEnvironment`, `MockProject`, the Java stub serializers). No published artifact has them unshrunk.

The repositories are declared in `settings.gradle.kts`: `packages.jetbrains.team/maven/p/ij/intellij-dependencies`
for the `-for-ide` artifacts and `www.jetbrains.com/intellij-repository/releases` for the platform.

The host is `AnalysisHost.kt`. Each request writes the document to a file, builds a standalone session with one
source module that depends on the standard library klib, and disposes it afterwards.

## Requests

Both hosts answer `call(operation, first, second)` with JSON. A failure is answered as `{"error": "<stack trace>"}`.

| Image | Operation | Arguments | Answer |
|---|---|---|---|
| both | `init` | the standard library archive in Base64 | `{"files": n}` |
| both | `libraries` | an archive of the other libraries in Base64, or nothing | `{"libraries": n, "files": n}` |
| compiler | `compile` | source | `{"ok", "exitCode", "milliseconds", "diagnostics": [...], "files": [{"name", "content"}]}`, the content in Base64 |
| analysis | `analyze` | source | `{"diagnostics": [...]}` |
| analysis | `complete` | source, offset | `{"from", "items": [{"label", "kind", "detail"}]}` |

`libraries` replaces the libraries besides the standard library; an empty argument leaves the standard library
alone. The archive has the format of the standard library's, and every entry name starts with a directory of its
own for each klib, `0-atomicfu-wasm-js-0.32.1/default/manifest`, the klibs in the order of their dependencies.
The compiler gets them all with `-libraries`; the analysis makes a library module of each, which depends on the
standard library and on the libraries before it. The editor makes the archive in the page from what the user lists
(`app/src/wasmJsMain/resources/kotlin-libraries.mjs`); the build makes one of `kotlinx-coroutines-core` for the
scenarios (`scenarioLibraryArchive`).

A diagnostic is `{"severity": "ERROR" or "WARNING", "message", "from", "to"}` with offsets into the source.
The compiled files are `main.wasm`, `main.mjs`, `main.import-object.mjs` and `main.js-builtins.mjs`.

Completion offers:
- after a dot, the members of the type of the expression before it, or the static and nested declarations of the
  class before it (enum entries, for example);
- elsewhere, the callables and classifiers of every scope at the caret, the default imports included.

It does not offer extension functions after a dot or keywords, does not add imports, and leaves out what a
library declares as internal or private.

## How the build works

1. **`patchJars` / `patchAnalysisJars`** run `PatchJars` over the jars and write the changed classes as loose
   files, which go first on the classpath. A class that several jars contain is taken from the first of them.
2. **`argumentsMetadata`** registers every member of the compiler's `*Arguments` classes for reflection, because
   command-line arguments are assigned through reflection.
3. **`stdlibArchive`** repacks the klib (see below).
4. **`jvmScenario` / `analysisScenario`** run the editor's requests on the JVM under GraalVM's tracing agent
   (`-agentlib:native-image-agent`). They check the answers, and what they reach through reflection becomes the
   reachability metadata of the image.
5. **`webImage` / `analysisImage`** run `native-image --tool:svm-wasm`.
6. **`compilerDist`** appends `src/main/js/worker-glue.js` to each generated script and names the result
   `kotlin-<name>-worker.js`. The generated script loads `<its own address>.wasm`, so the script the worker
   runs and the module must share a name.

### The scenarios decide what works

An image contains only what static analysis finds reachable plus what the scenarios reached through reflection.
Code of a kind the scenarios never exercised can fail in the image with a `MissingReflectionRegistrationError`,
a `NoClassDefFoundError` or an `ExceptionInInitializerError`, while the same request works on the JVM.
This happened with KDoc comments: the first scenarios had none, and completion failed in any document with one.

`src/scenario/shapes.kt` is a program with most kinds of declarations, expressions and comments; both scenarios
compile or analyze it. Both also load `kotlinx-coroutines-core` with `libraries`, compile or analyze a program
that uses it, and remove it again; `imageTest` runs that program under Node.js. When something fails only in the image, add code like it to that file or a request to
`JvmScenario.kt` or `AnalysisScenario.kt`, and rebuild.

## What Web Image cannot do, and what is done about it

All of these were found with Oracle GraalVM 25i4. Web Image is experimental; check them against newer releases
before keeping them.

Bytecode rewriting, in `src/patcher/.../PatchJars.java`:

| Problem | Rewrite |
|---|---|
| The builder crashes on an interface that re-declares `hashCode`, `equals` or `toString` (`Incorrect single abstract method logic`) | the re-declarations are removed from such interfaces |
| `Atomic*FieldUpdater.newUpdater` throws `NullPointerException` at run time, in any program | call sites go to `Updaters` (`src/shared/java`): a read, a comparison and a write through `java.lang.reflect.Field`. The fields they name are written to generated reachability metadata |
| `ThreadMXBean` CPU-time calls throw or break the builder | the switch is dropped, the reads return 0 |
| The builder fails on `kotlinx.coroutines` with `UNSTRUCTURED_LOCKING` | `monitorenter` and `monitorexit` become pops in that package; there are no threads to exclude |
| Flight Recorder is not supported, and the Analysis API emits events | `EventType.getEventType` gives `null` and `isEnabled` gives `false`, which is how that code already skips events |
| Methods that ask the JVM about itself or need threads | they get an empty body; the list is `STUBS` in `PatchJars`: the debugger probes of `DebugAttachDetectorArgs`, the garbage collector probe of `LowMemoryWatcherManager`, `execute` of IntelliJ's shared thread pool, and `EDT.isCurrentThreadEdt` (which loads AWT) |

Replaced classes, in `src/shared/java`:

- `com.intellij.concurrency.VarHandleWrapperImpl`: IntelliJ's concurrent collections reach their fields through
  `VarHandle`, and the Wasm backend cannot compile the float and double compare-and-set of `VarHandle` that this
  makes reachable. The replacement uses reflection.

And one in `src/analysis/kotlin`, ahead of the jars on the classpath of the Analysis API image:

- `org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.BuiltinsVirtualFileProviderCliImpl`: the
  original finds the `.kotlin_builtins` files as entries of the jar it was loaded from. In the image they are
  resources in no jar (`resource:/5!/kotlin/kotlin.kotlin_builtins`), so it found none and logged
  `VirtualFile not found for builtin ...` for each of them on every request. The replacement has no such files.
  They hold the built-in declarations of the JVM; the Wasm module takes its own from the standard library klib.

Build flags and inputs:

- `-H:ImageHeapObjectsPerFunction=20000`: with the default, a function that fills the image heap is 8 MB, and V8
  accepts functions up to about 7.6 MB.
- `kotlin.colors.enabled=false`, set by the hosts: the message renderer otherwise calls jansi's native `isatty`.
- There is no native zip (`java.util.zip.Inflater`), so klibs must be unpacked directories. The standard
  library travels as `kotlin-stdlib-wasm-js.bin`: a count, then the name, size and bytes of each file, which the
  host unpacks into the image's file system. The compiler writes its own library with `-nopack`.

Facts about the image at run time:

- The file system is in memory (Jimfs); files of the page or of the server are not visible. Everything goes in
  and out through the requests.
- There are no threads. `Thread.start()` does nothing, and executors throw when they add a worker.
- A stack overflow is a JavaScript `RangeError` that cannot be caught as `StackOverflowError`. The images are
  tested with the default stack of V8, because a browser gives no more.
- `java.home` is `null`, `os.name` is `Browser`, there is one processor and the maximum heap is 4 GB.
- No AWT, no management beans, no Flight Recorder, no native libraries of any kind.
- Calls from JavaScript into Java need untyped parameters: a functional interface with `Object` parameters
  receives `JSString` values (`WebMain.java`).

### Debugging an image

- Build with `-PwebImage.debugNames`: without it a stack trace is a list of function numbers.
- Stack traces are cut to about seven frames. To see who reaches a class, run the scenario on the JVM with
  `-Xlog:class+load+cause -XX:LogClassLoadingCauseFor=<class name>`.
- A builder failure leaves `svm_err_*.md` in the image directory, with the method being compiled.
- Reproduce in the JVM scenario first: it runs in seconds, and an image takes a minute and a half.

## In the editor

| File | Role |
|---|---|
| `app/src/wasmJsMain/resources/kotlin-compiler-client.mjs` | starts the workers, and runs a compiled program in a worker of its own |
| `app/.../KotlinCompilerJs.kt`, `KotlinCompiler.kt` | Kotlin bindings: `KotlinCompiler.compile`, `KotlinAnalysis.analyze` and `complete` |
| `app/.../EditorSession.kt` | `run()`, the output panel's state, checking the text after changes |
| `editor-codemirror/.../kotlin-editor.mjs`, `CodeEditor.kt` | Ctrl+Enter, problem markers (`@codemirror/lint`), completion from the analysis |
| `app/.../Automation.kt` | `automationRun()` and the compiler's state, for the browser tests |

- Neither worker is started until the first run, so a page that never runs anything downloads nothing extra
  and looks exactly as before.
- The first run starts both. From then on the text is analyzed 300 ms after each change, and completion asks
  the analysis first and falls back to the editor's own completion when the analysis offers nothing.
- A compiled program runs in a module worker made of object URLs: the generated modules import each other by
  relative paths, which the client rewrites. What the program prints through the console goes to the output
  panel. A program is stopped after 10 seconds.

## Known limits

- Every analysis request builds a new session. Under Node.js a request takes 25–350 ms on small files; large
  files will need the session kept between requests.
- The reachability metadata covers what the scenarios cover, as described above.
- Two downloads of 75 MB each and a 12 MB library on the first run, fetched once per page.
- One file, compiled against the standard library and the libraries the editor sends; no multiple files, and no
  compiler plugins, so libraries that need one (`kotlinx-serialization`, Compose) do not work.
- Compiler plugins, scripting and the other compiler back ends are in the images but were never exercised.

## Updating the Kotlin version

1. Change `kotlin` in `gradle/libs.versions.toml`. The `-for-ide` artifacts must exist for that version in the
   `intellij-dependencies` repository.
2. In the Kotlin repository at the tag `build-<version>`, read `versions.intellijSdk` in
   `gradle/versions.properties` and set `intellijVersion` in `build.gradle.kts`. Compare the module lists in
   `dependencies/intellij-core-implementation/build.gradle.kts` and `dependencies/intellij-java-psi-api/build.gradle.kts`
   with the one here.
3. Replace `src/analysis/intellij-core` with the `src` directories of those two modules at that tag.
4. Run the JVM scenarios, then `imageTest`, then the browser tests. Expect new entries for `STUBS` or new
   rewrites when the platform version changes.
