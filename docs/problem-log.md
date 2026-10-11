# Problem log

Bugs and surprising behaviour of the tools this project is built on: compilers, runtimes, libraries, build and
test tools. Problems of the project's own code do not belong here; they are fixed and covered by a test.

The log exists so that a workaround can be traced to its reason and removed when the reason is gone, and so that
what is worth reporting upstream is in one place. How to add to it is at the [end](#adding-an-entry).

Each entry has:

- **Seen**: the date and the versions.
- **Symptom**: what happens, with the exact message.
- **Code**: the code that triggers it, quoted from where it lives, or a program that reproduces it.
- **Cause**: what is known, and whether it was verified or is a guess.
- **Here**: what the project does about it and where.

Nothing has been reported upstream so far.

Versions the entries refer to, unless an entry says otherwise:

| Tool | Version |
|---|---|
| GraalVM | Oracle GraalVM 25i4 (`graalvm-25.4.4.1.1+1.1`), `native-image --tool:svm-wasm` |
| Kotlin | 2.5.0-dev-9169; sources quoted from the tag `build-2.5.0-dev-9169` of JetBrains/kotlin |
| IntelliJ platform | 261.24374.151; sources quoted from the `-sources` jars of `com.jetbrains.intellij.platform:util` and `util-base` |
| Node.js (V8) | 26.9.0 |
| Compose Multiplatform | 1.12.1 |

## GraalVM Web Image

Web Image is experimental. The workarounds are described together in
[`compiler-host/README.md`](../compiler-host/README.md); the rewriting is in
`compiler-host/src/patcher/java/editor/compilerhost/patcher/PatchJars.java`.

### WI-1. The builder crashes on an interface that re-declares a method of `Object`

- **Seen**: 2026-10-10.
- **Symptom**: the image build fails during analysis:

  ```
  com.oracle.graal.pointsto.util.ParallelExecutionException: failed guarantee: Incorrect single abstract method
  logic for interface AnalysisType<ConcurrentRefHashMap$KeyReference -> ...>. Found at least two candidate methods:
  AnalysisMethod<ConcurrentRefHashMap$KeyReference.get ...> and AnalysisMethod<ConcurrentRefHashMap$KeyReference.hashCode ...>
      at com.oracle.graal.pointsto.util.CompletionExecutor.complete(CompletionExecutor.java:229)
      at com.oracle.graal.pointsto.PointsToAnalysis.doTypeflow(PointsToAnalysis.java:647)
  ```

- **Code**: `com/intellij/util/containers/ConcurrentRefHashMap.java`, legal Java:

  ```java
  interface KeyReference<K> {
    K get();

    // In case of gced reference, equality must be identity-based ...
    @Override
    boolean equals(Object o);

    @Override
    int hashCode();
  }
  ```

- **Cause**: the builder's search for the single abstract method of an interface counts the re-declared
  `equals` and `hashCode` as candidates, although the language specification excludes the public methods of
  `Object`. Verified: with the re-declarations removed from the class file the build passes. The compiler jar
  has 38 such interfaces.
- **Here**: `PatchJars` drops `hashCode()`, `equals(Object)` and `toString()` from every interface that declares them.

### WI-2. `Atomic*FieldUpdater.newUpdater` throws at run time

- **Seen**: 2026-10-10.
- **Symptom**: the image builds; at run time:

  ```
  java.lang.RuntimeException: java.lang.NullPointerException
      at AtomicReferenceFieldUpdater$AtomicReferenceFieldUpdaterImpl.<init>
      at AtomicReferenceFieldUpdater.newUpdater
      at P2.main
  Caused by: java.lang.NullPointerException
      at AtomicReferenceFieldUpdater$AtomicReferenceFieldUpdaterImpl.<init>
  ```

  The same for `AtomicIntegerFieldUpdater`.
- **Code**: the whole reproducer:

  ```java
  import java.util.concurrent.atomic.*;
  public class P2 {
      volatile Object v; volatile int i;
      public static void main(String[] a) {
          var u = AtomicReferenceFieldUpdater.newUpdater(P2.class, Object.class, "v");   // throws
          var n = AtomicIntegerFieldUpdater.newUpdater(P2.class, "i");                   // throws
      }
  }
  ```

  It is everywhere in practice. The first place the compiler hit it was Kotlin's own `lazy(PUBLICATION)`,
  `libraries/stdlib/jvm/src/kotlin/util/LazyJVM.kt`:

  ```kotlin
  companion object {
      private val valueUpdater = java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater(
          SafePublicationLazyImpl::class.java,
          Any::class.java,
          "_value"
      )
  }
  ```

  The compiler jar has 48 classes with such calls, naming 80 fields; `kotlinx.coroutines.JobSupport` is another.
- **Cause**: not investigated beyond the reproducer; the exception comes from the constructor of the JDK's
  updater implementation.
- **Here**: `PatchJars` sends each `newUpdater` call to `editor.compilerhost.web.Updaters`
  (`compiler-host/src/shared/java`), whose updaters read, compare and write through `java.lang.reflect.Field`,
  and writes reflection metadata for the fields named at the call sites. There are no threads, so a plain
  read-compare-write is enough.

### WI-3. `ThreadMXBean` CPU-time calls break the builder

- **Seen**: 2026-10-10.
- **Symptom**: the image build fails:

  ```
  com.oracle.graal.pointsto.util.ParallelExecutionException: Error encountered while parsing
  com.oracle.svm.core.jdk.management.SubstrateThreadMXBean.getCurrentThreadUserTime(SubstrateThreadMXBean.java:209)
  Parsing context:
     at org.jetbrains.kotlin.util.PerformanceManager.currentTime(PerformanceManager.kt:42)
     at org.jetbrains.kotlin.util.PerformanceManager.<init>(PerformanceManager.kt:48)
  Caused by: java.lang.Error: ImageSingletons do not contain key
  com.oracle.svm.guest.staging.core.thread.ThreadCpuTimeSupport
  ```

- **Code**: `core/compiler.common/src/org/jetbrains/kotlin/util/PerformanceManager.kt`:

  ```kotlin
  private fun currentTime(): Time {
      val nanos = System.nanoTime()
      return when {
          detailedPerf -> Time(nanos, threadMXBean.currentThreadUserTime, threadMXBean.currentThreadCpuTime)
          else -> Time(nanos, userNanos = 0, cpuNanos = 0)
      }
  }

  fun initializeCurrentThread() {
      thread = Thread.currentThread()
      threadMXBean = ManagementFactory.getThreadMXBean().also { it.isThreadCpuTimeEnabled = true }
  }
  ```

- **Cause**: the builder cannot even parse the substitution of these methods, because the support they need is
  not registered for Web Image. It is a build failure, not a run-time one, so it does not matter that
  `detailedPerf` is off. The setter fails separately, at run time.
- **Here**: `PatchJars` drops the call of `setThreadCpuTimeEnabled` and replaces the calls of
  `getCurrentThreadUserTime` and `getCurrentThreadCpuTime` with 0.

### WI-4. The builder rejects `kotlinx.coroutines` with `UNSTRUCTURED_LOCKING`

- **Seen**: 2026-10-10, with `kotlinx-coroutines-core-jvm` on the classpath.
- **Symptom**: the image build fails:

  ```
  com.oracle.svm.shared.util.VMError$HostedError: InternalFeature defined by
  com.oracle.svm.hosted.webimage.codegen.WebImageImplicitExceptionsFeature unexpectedly failed with a(n)
  jdk.graal.compiler.debug.GraalError
  Caused by: jdk.graal.compiler.debug.GraalError: should not reach here: unexpected value: UNSTRUCTURED_LOCKING
  ```

- **Code**: the method the builder objected to was not recorded. The classes of the library that contain
  `monitorenter` are `JobSupport`, `EventLoopImplBase`, `internal/ThreadSafeHeap`, `internal/LimitedDispatcher`,
  `internal/SynchronizedKt`, `flow/StateFlowImpl`, `flow/SharedFlowImpl`, `scheduling/CoroutineScheduler` and its
  `Worker` (listed with `javap -c` from version 1.9.0), where the source has the library's inline
  `synchronized(lock) { ... }`.
- **Cause**: a guess: the builder accepts only locking in the shape `javac` produces, and the Kotlin compiler
  emits another shape for an inlined `synchronized` block with several exits. Verified only that replacing the
  monitor instructions in the package makes the build pass.
- **Here**: `PatchJars` turns `monitorenter` and `monitorexit` into `pop` in `kotlinx/coroutines/`. Without
  threads there is nobody to exclude.

### WI-5. No Flight Recorder

- **Seen**: 2026-10-11, in the Analysis API image.
- **Symptom**: the first analysis fails, and the Analysis API reports it as its own error:

  ```
  Error: Flight Recorder is not supported on this VM
      at LLFlightRecorder.<clinit>
      at LLFirTargetResolver.performResolve(FirElementWithResolveState)
  ...
  java.lang.AssertionError: The diagnostic collector has been interrupted by an exception. The result may be incomplete
  ```

- **Code**: `analysis/low-level-api-fir/src/org/jetbrains/kotlin/analysis/low/level/api/fir/util/LLFlightRecorder.kt`:

  ```kotlin
  object LLFlightRecorder {
      private val phaseEventType = EventType.getEventType(LLPhaseEvent::class.java)
      ...
      if (!phaseEventType.isEnabled) {
  ```

- **Cause**: `jdk.jfr.EventType.getEventType` throws in an image without Flight Recorder. The object has six
  such fields, so its initializer fails and every resolve after it does.
- **Here**: `PatchJars` replaces `EventType.getEventType(...)` with `null` and `EventType.isEnabled()` with
  `false`. The code checks `isEnabled` before it creates an event, so nothing else is reached.

### WI-6. `VarHandle` compare-and-set of `float` and `double` cannot be compiled

- **Seen**: 2026-10-10.
- **Symptom**: the image build fails while compiling methods:

  ```
  com.oracle.graal.pointsto.util.ParallelExecutionException: The native image process failed due to a compilation
  problem. As a workaround, try using the '-H:+EnableFallbackCompilation' option to retry problematic
  compilations with fewer optimizations.
  ```

  The methods named in the error report were the JDK's `VarHandle` access paths for `float` and `double`
  compare-and-set; the report was not kept.
- **Code**: `compiler/cli/cli-base/srcJdk9/com/intellij/concurrency/VarHandleWrapperImpl.java`. The class is
  registered for reflection, so all of its methods are reachable, among them:

  ```java
  VarHandle handle = MethodHandles
          .privateLookupIn(containingClass, MethodHandles.lookup())
          .findVarHandle(containingClass, name, type);
  ...
  return myVarHandle.compareAndSet(thisObject, expected, actual);
  ```

- **Cause**: a `VarHandle` whose field type is not known at build time makes every access path of every
  primitive type reachable, and the Wasm backend has no code for two of them. Without the class at all, IntelliJ
  fails earlier: `NullPointerException` in `VarHandleWrapper.getFactory`.
- **Here**: `compiler-host/src/shared/java/com/intellij/concurrency/VarHandleWrapperImpl.java` replaces the class
  with one that reads and writes through `java.lang.reflect.Field` and arrays.

### WI-7. The function that fills the image heap is too large for V8

- **Seen**: 2026-10-10.
- **Symptom**: the image builds, and the module is rejected when it is loaded:

  ```
  CompileError: WebAssembly.instantiate(): size 8077864 > maximum function size 7654321 @+39368781
  ```

- **Code**: generated: the function `fillHeapObjects0`, which writes the objects of the image heap.
- **Cause**: the builder splits that code by a number of objects, not by size, and the default lets one function
  pass V8's limit of 7654321 bytes.
- **Here**: `-H:ImageHeapObjectsPerFunction=20000` in `compiler-host/build.gradle.kts`. After it the largest
  function was 4.9 MB.

### WI-8. A missing native method is a `LinkageError`, which code written for the JVM does not catch

- **Seen**: 2026-10-10 (jansi, zip), 2026-10-11 (AWT, agent properties).
- **Symptom**: four cases of the same kind:

  ```
  java.lang.LinkageError: Found unsupported native method
  org.jetbrains.kotlin.org.fusesource.jansi.internal.CLibrary.isatty(int)
      at PlainTextMessageRenderer.<clinit>
      at MessageRenderer.<clinit>

  java.lang.UnsatisfiedLinkError: Can't load library: zip | java.library.path = [., /usr/lib]
      at Inflater.<clinit>
      at ZipFileSystem.getInflater

  java.lang.UnsatisfiedLinkError: Can't load library: awt | java.library.path = [., /usr/lib]
      at Toolkit.initStatic
      at Toolkit.<clinit>

  java.lang.LinkageError: Found unsupported native method
  jdk.internal.vm.VMSupport.initAgentProperties(java.util.Properties)
      at DebugAttachDetectorArgs.findAgentProperties
      at DebugAttachDetectorArgs.<clinit>
  ```

- **Code**: `compiler/cli/src/org/jetbrains/kotlin/cli/common/messages/PlainTextMessageRenderer.kt` is prepared
  for the native library to be missing, but for the exception the JVM throws:

  ```kotlin
  if (!isWindows && "true" == kotlinColorsEnabled) {
      try {
          isStderrATty = CLibrary.isatty(CLibrary.STDERR_FILENO) != 0
      } catch (_: UnsatisfiedLinkError) {
      }
  }
  ```

  `com/intellij/util/ui/EDT.java` reaches AWT from a check that has nothing to do with the UI here:

  ```java
  private static volatile Supplier<Boolean> ourIsCurrentThreadEDT =
    () -> EventQueue.isDispatchThread();

  public static boolean isCurrentThreadEdt() {
    Thread thread = ourThread;
    return thread != null ? Thread.currentThread() == thread : ourIsCurrentThreadEDT.get();
  }
  ```

  `com/intellij/util/DebugAttachDetectorArgs.java` catches only what reflection declares:

  ```java
  private static final @Nullable Properties AGENT_PROPERTIES = findAgentProperties();
  ...
  return (Properties)vmSupportClass.getMethod("getAgentProperties").invoke(null);
  ```

  The zip case is any read of a packed klib or jar through `java.util.zip`.
- **Cause**: an image has no native libraries. For a native method that was not substituted Web Image throws
  `LinkageError`, where the JVM would throw its subclass `UnsatisfiedLinkError`, so `catch (UnsatisfiedLinkError)`
  does not help. Verified for jansi by the trace above.
- **Here**: `kotlin.colors.enabled=false` is set by the hosts, so the branch is not taken;
  `EDT.isCurrentThreadEdt` and `DebugAttachDetectorArgs.findAgentProperties` are in `STUBS` of `PatchJars`;
  klibs are unpacked directories (the standard library travels as `kotlin-stdlib-wasm-js.bin`, the compiler
  writes with `-nopack`).

### WI-9. Management beans are missing or unimplemented

- **Seen**: 2026-10-11, in the Analysis API image.
- **Symptom**: two different failures:

  ```
  jdk.graal.compiler.debug.GraalError: unimplemented override
      at DebugAttachDetectorArgs.findDebugArgs
      at DebugAttachDetectorArgs.<clinit>
      at Cancellation$DebugNonCancellableState.<clinit>

  java.lang.ExceptionInInitializerError
      at KaFirSessionProvider.<init>(Project)
      ...
      at ManagementSupport.getPlatformMXBeans(Class)
      at LowMemoryWatcherManager.fetchMajorGcDurationAccumulated
      at LowMemoryWatcherManager.<init>(ExecutorService)
      at AppScheduledExecutorService.<init>
      at AppScheduledExecutorService$Holder.<clinit>
  ```

- **Code**: `com/intellij/util/DebugAttachDetectorArgs.java`:

  ```java
  for (String value : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
    if (value.contains("-agentlib:jdwp")) {
  ```

  `com/intellij/openapi/util/LowMemoryWatcherManager.java`:

  ```java
  private static long fetchMajorGcDurationAccumulated() {
    for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (gc.getName().toLowerCase().contains(MAJOR_GC_PATTERN)) {
        return gc.getCollectionTime();
  ```

- **Cause**: `RuntimeMXBean.getInputArguments` exists but throws a builder error at run time, and asking for
  the garbage collector beans fails. Both are reached from static initializers of IntelliJ's core, so creating
  the first Analysis API session fails.
- **Here**: `findDebugArgs` and `fetchMajorGcDurationAccumulated` are in `STUBS` and return `null` and 0.

### WI-10. Threads silently do nothing, and a thread pool throws

- **Seen**: 2026-10-10 (probe), 2026-10-11 (thread pool).
- **Symptom**: this prints nothing at all, neither line, and `join` returns:

  ```java
  try { Thread t = new Thread(() -> System.out.println("thread ran")); t.start(); t.join(); }
  catch (Throwable t) { System.out.println("Thread: " + t); }
  ```

  A pool does fail, but far from the cause:

  ```
  java.lang.ExceptionInInitializerError
      at KaFirSessionProvider.<init>(Project)
      ...
      at ThreadPoolExecutor.addWorker(Runnable, boolean)
      at ThreadPoolExecutor.execute(Runnable)
      at AppScheduledExecutorService$BackendThreadPoolExecutor.execute(Runnable)
      at AbstractExecutorService.submit(Runnable)
      at LowMemoryWatcherManager.<init>(ExecutorService)
  ```

- **Code**: `com/intellij/util/concurrency/AppScheduledExecutorService.java`:

  ```java
  private static final class BackendThreadPoolExecutor extends ThreadPoolExecutor {
    ...
    @Override
    public void execute(@NotNull Runnable command) {
      super.execute(capturePropagationAndCancellationContext(command));
    }
  ```

- **Cause**: there are no threads. Code that waits for a result from another thread would hang instead of failing.
- **Here**: `BackendThreadPoolExecutor.execute` is in `STUBS`: the tasks of IntelliJ's shared pool are
  housekeeping here (watching for low memory, evicting caches) and are dropped.

### WI-11. A stack overflow cannot be caught, and stack traces are cut

- **Seen**: 2026-10-10.
- **Symptom**: the program ends with `Uncaught internal error: RangeError: Maximum call stack size exceeded`.
- **Code**:

  ```java
  static int depth(int n) { try { return depth(n + 1); } catch (StackOverflowError e) { return n; } }
  ```

- **Cause**: the overflow is a JavaScript exception that the image does not turn into `StackOverflowError`.
  Separately, a Java stack trace has about seven frames, and without `-PwebImage.debugNames` they are function
  numbers (`wasm-function[35306]:0x12b862c`).
- **Here**: the images are tested with V8's default stack, because a browser gives no more. To find who reaches
  a class, the scenario is run on the JVM with `-Xlog:class+load+cause -XX:LogClassLoadingCauseFor=<class>`.

### WI-12. A callback from JavaScript must take `Object` parameters

- **Seen**: 2026-10-10.
- **Symptom**: a callback declared with `String` parameters does not receive strings from JavaScript.
- **Code**: what works, from `compiler-host/src/main/java/editor/compilerhost/web/WebMain.java`:

  ```java
  public interface Handler {
      Object handle(Object operation, Object first, Object second);
  }

  @JS.Coerce
  @JS("globalThis.kotlinCompilerHost = { call: (operation, first, second) => handler(operation, first, second) };")
  private static native void install(Handler handler);
  ```

  Inside the handler `operation.getClass().getName()` is `org.graalvm.webimage.api.JSString`. The first version
  had `String handle(String op, String a, String b)`.
- **Cause**: a guess: `@JS.Coerce` converts the arguments of the native method, not those of a callback passed
  through it.
- **Here**: the handlers take `Object` and convert with `JSString.asString()`; they return `JSString.of(...)`.

### WI-13. The generated script looks for the module next to its own address

- **Seen**: 2026-10-11.
- **Symptom**: a worker that imports the generated script under another name gets 404 for the `.wasm` file.
- **Code**: generated, in `kotlin-analysis.js`:

  ```js
  const wasmPath = config.wasm_path || runtime.getCurrentFile() + ".wasm";
  ```

- **Cause**: by design. `config.wasm_path` would be the other way; it was not tried.
- **Here**: `compilerDist` appends `src/main/js/worker-glue.js` to the generated script and names the result
  `kotlin-<name>-worker.js`, next to `kotlin-<name>-worker.js.wasm`.

### WI-14. What reflection reaches must have been reached under the tracing agent

- **Seen**: 2026-10-11.
- **Symptom**: completion failed in any document that had a KDoc comment, in the image only:

  ```
  java.lang.ExceptionInInitializerError
  java.lang.NoClassDefFoundError: Could not initialize class org.jetbrains.kotlin.kdoc.parser.KDocElementTypes
  ```

- **Code**: `compiler/psi/psi-api/src/org/jetbrains/kotlin/kdoc/parser/KDocElementType.java`, used by the static
  fields of `KDocElementTypes`:

  ```java
  public KDocElementType(String debugName, @NotNull Class<? extends PsiElement> psiClass) {
      super(debugName, KotlinLanguage.INSTANCE);
      try {
          psiFactory = psiClass.getConstructor(ASTNode.class);
      } catch (NoSuchMethodException e) {
          throw new RuntimeException("Must have a constructor with ASTNode");
  ```

- **Cause**: by design, but easy to miss. The constructors of `KDocSection`, `KDocTag` and `KDocName` are found
  by reflection, and the scenarios that produce the reflection metadata had no KDoc comment.
- **Here**: `compiler-host/src/scenario/shapes.kt` holds most kinds of declarations, expressions and comments,
  and both scenarios run over it.

### WI-15. Only Oracle GraalVM has Web Image

- **Seen**: 2026-10-10, GraalVM Community Edition 25.
- **Symptom**: `native-image` of the Community Edition rejects `--tool:svm-wasm`.
- **Here**: `graalvm.home` in `local.properties` points to Oracle GraalVM; without it the compiler tasks are disabled.

## Kotlin compiler and Analysis API

### KT-1. The Analysis API does not work over the `kotlin-compiler` jar

- **Seen**: 2026-10-11.
- **Symptom**: every request of the standalone Analysis API fails:

  ```
  java.lang.AssertionError: 'java.lang.String com.intellij.openapi.fileTypes.FileType.getDefaultExtension()'
      at com.intellij.openapi.diagnostic.DefaultLogger.error(DefaultLogger.java:91)
      at com.intellij.openapi.diagnostic.Logger.error(Logger.java:439)
  ```

- **Code**: the interface as it is in `kotlin-compiler-2.5.0-dev-9169.jar` (`javap`):

  ```
  public interface com.intellij.openapi.fileTypes.FileType {
    public abstract java.lang.String getName();
    public abstract java.lang.String getDescription();
    public abstract javax.swing.Icon getIcon();
    public abstract boolean isBinary();
    public default java.lang.String getCharset(com.intellij.openapi.vfs.VirtualFile, byte[]);
  }
  ```

  `getDefaultExtension()` is gone from the interface, while `KotlinFileType` in the same jar still has it, and
  the Analysis API jars call it through the interface.
- **Cause**: the jar is shrunk by ProGuard against what the compiler alone uses. Verified by the listing above.
- **Here**: the Analysis API image is built from the unshrunk `*-for-ide` artifacts and the IntelliJ platform
  modules (`analysisJars` in `compiler-host/build.gradle.kts`).

### KT-2. No published artifact has the unshrunk IntelliJ core patches

- **Seen**: 2026-10-11.
- **Symptom**: with the `*-for-ide` artifacts:

  ```
  java.lang.NoClassDefFoundError: com/intellij/core/JavaCoreApplicationEnvironment
      at org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment$Companion.createApplicationEnvironment(KotlinCoreEnvironment.kt:672)
  Caused by: java.lang.ClassNotFoundException: com.intellij.core.JavaCoreApplicationEnvironment
  ```

- **Code**: the class and 35 others (`MockProject`, the Java stub serializers, `JavaDocParser.kt`) are compiled
  in the Kotlin repository from `dependencies/intellij-core-implementation/src` and
  `dependencies/intellij-java-psi-api/src`.
- **Cause**: those two modules are published only inside the shrunk `kotlin-compiler` jar.
- **Here**: their sources are vendored at the tag of the Kotlin version, in `compiler-host/src/analysis/intellij-core`.

  A related one on the way: `NoClassDefFoundError: kotlinx/serialization/KSerializer` at
  `com.intellij.util.xml.dom.XmlElement.<clinit>(XmlElement.kt:11)`. The platform's XML reader needs
  `kotlinx-serialization-core`, which did not come with the artifacts and is added to `analysisJars` by hand.

### KT-3. Two versions of `org.jetbrains.annotations` on the compile classpath

- **Seen**: 2026-10-11.
- **Symptom**:

  ```
  e: .../intellij-core/com/intellij/java/syntax/parser/JavaDocParser.kt:474:15 No parameter with name 'mutates' found.
  ```

- **Code**: a use of `@Contract(mutates = "...")` in the vendored source.
- **Cause**: an older annotations jar, without `Contract.mutates`, came first on the classpath.
- **Here**: `compileClasspath = analysisJars + compileClasspath` for the `analysis` source set.

### KT-4. The standalone Analysis API finds built-ins only inside a jar

- **Seen**: 2026-10-11, in the Analysis API image.
- **Symptom**: on every request, for each of the eight built-in files, several times:

  ```
  WARN: VirtualFile not found for builtin resource:/5!/kotlin/kotlin.kotlin_builtins
  ```

  and from the same assumption elsewhere, `java.nio.file.NoSuchFileException: /8` at
  `ZipHandler$1.createAccessor`.
- **Code**: `analysis/analysis-api-standalone/analysis-api-standalone-fir/src/.../projectStructure/BuiltinsVirtualFileProviderCliImpl.kt`:

  ```kotlin
  override fun findVirtualFile(url: URL): VirtualFile? {
      val split = URLUtil.splitJarUrl(url.path)
          ?: errorWithAttachment("URL for builtins does not contain jar separator") { ... }
      val jarPath = split.first
      val builtinFile = split.second
      val pathToQuery = jarPath + URLUtil.JAR_SEPARATOR + builtinFile
      val jarFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.JAR_PROTOCOL)
      return jarFileSystem.findFileByPath(pathToQuery)
  }
  ```

- **Cause**: the address of a class-path resource is taken to be `jar:file:<jar>!/<entry>`. In an image it is
  `resource:/5!/kotlin/kotlin.kotlin_builtins`: it has the separator, so the split succeeds, and `/5` is then
  looked up as a jar file. Verified in the sources.
- **Here**: the class is replaced by one with no such files
  (`compiler-host/src/analysis/kotlin/org/jetbrains/kotlin/analysis/api/standalone/base/projectStructure/`).
  A Wasm module takes the built-in declarations from the standard library klib, and the image never had the
  files, so nothing changed but the warnings.

### KT-5. The standalone session leaves threads behind

- **Seen**: 2026-10-11, on the JVM.
- **Symptom**: a program that used the compiler or the Analysis API does not exit after `main` returns.
- **Cause**: the IntelliJ platform starts threads that are not daemons. Which ones was not looked at.
- **Here**: `JvmScenario.kt` and `AnalysisScenario.kt` end with `exitProcess(0)`.

### KT-6. Members of a class are in two scopes for completion

- **Seen**: 2026-10-11.
- **Symptom**: completion after `Scale.` offered nothing for an enum.
- **Code**: what `AnalysisHost.kt` has now:

  ```kotlin
  val scope = if (container != null) container.staticMemberScope else receiver?.expressionType?.scope?.declarationScope
  ```

- **Cause**: not a bug: enum entries and other static declarations are in `staticMemberScope` of the class
  symbol, not in the scope of a type, and a class name used as a receiver has no expression type to ask.
- **Here**: as quoted.

### KT-7. The message of a JavaScript error caught in Kotlin/Wasm is wrapped

- **Seen**: 2026-10-11, Kotlin/Wasm 2.5.0-dev-9169, in the browser.
- **Symptom**: the message of a caught exception is not the message of the JavaScript error:

  ```
  Non-Kotlin exception Error: Cannot fetch ./plugins.json of type 'class Error'
  ```

  where the error was `new Error('Cannot fetch ./plugins.json')`.
- **Code**: `app/src/wasmJsMain/kotlin/editor/app/PluginLoader.kt`: a rejected promise awaited from Kotlin and
  caught as `Throwable`:

  ```kotlin
  private fun fetchText(url: String): Promise<JsString> =
      js("fetch(url).then(response => response.ok ? response.text() : Promise.reject(new Error('Cannot fetch ' + url)))")
  ...
  } catch (e: Throwable) {
      logWarning("[plugins] cannot read the index $indexUrl: ${e.message ?: e}")
  ```

- **Cause**: by design, it seems: a thrown JavaScript value becomes a `JsException` whose `message` describes the
  value instead of repeating its `message`. Not looked up in the standard library.
- **Here**: nothing; the status bar and the plugin log show the wrapped text, and the tests match a part of it.

## Compose Multiplatform and Skiko

### CMP-1. A WebGL warning at every start

- **Seen**: 2026-10-10, Chrome.
- **Symptom**: a WebGL `INVALID_ENUM` warning in the console at every start. The exact text was not kept.
- **Cause**: Skia reads the unmasked GPU name whenever the browser lists `WEBGL_debug_renderer_info`, and
  Skiko's Emscripten glue never enables that extension, so the read is an invalid enum.
- **Here**: `app/src/wasmJsMain/resources/index.html` wraps `HTMLCanvasElement.prototype.getContext`:

  ```js
  if (context && webgl) context.getExtension('WEBGL_debug_renderer_info');
  ```

### CMP-2. What Compose draws goes blank while the mouse is over the editor

- **Seen**: 2026-10-11, in a desktop Chrome window, zoomed, with DevTools docked. Not reproduced in headless
  Chrome at any size or scale that was tried.
- **Symptom**: the status bar and the output panel are white. The state is intact:
  `automationState().console` still has the title and the lines.
- **Code**: `app/src/wasmJsMain/kotlin/editor/app/Main.kt`: a `WebElementView` and Compose text in one column,
  so one WebGL canvas spans the window and the editor is an HTML element over a part of it.

  ```kotlin
  Column(Modifier.fillMaxSize()) {
      WebElementView(factory = { session.editor.element }, modifier = Modifier.fillMaxWidth().weight(1f))
      session.console?.let { console -> Column(...) { BasicText(console.title) ... } }
      Column(...) { BasicText("${caretText(session.caret)} | ...") }
  }
  ```

- **Cause**: a guess that fits. Compose draws a frame only when its own state changes, and Skiko makes the WebGL
  context without `preserveDrawingBuffer`, so a canvas the browser presents again between two frames has no
  picture. Verified only this much: copying the canvas with `drawImage` between frames gave an empty picture
  before the change and the status bar after it.
- **Here**: the same `getContext` wrapper adds `preserveDrawingBuffer: true`. The test is `status bar canvas` in
  `e2e/tests/statusbar.spec.ts`. **Open**: not confirmed in the window where it was seen.

### CMP-3. Compose cannot draw above a web element

- **Seen**: 2026-10-10.
- **Symptom**: nothing drawn by Compose can overlap the area of a `WebElementView`.
- **Here**: the editor has a region of its own in the column quoted in CMP-2, and its popups (completion,
  search, lists of usages) are HTML made by CodeMirror.

## CodeMirror

### CM-1. A completion cannot be accepted right after the list opens

- **Seen**: 2026-10-11, `@codemirror/autocomplete` 6.
- **Symptom**: Enter pressed at once after the list appears inserts a line break instead of the completion.
- **Cause**: by design: the list ignores the accepting keys for `interactionDelay` (75 ms by default) after it opens.
- **Here**: the tests in `e2e/tests/compiler.spec.ts` wait before they accept.

## Playwright

### PW-1. An existing server on the port is used whatever it serves

- **Seen**: 2026-10-11.
- **Symptom**: `distribution › the test plugins are present but not indexed` fails with
  `Expected: 200, Received: 404` for `wasm-code-editor-test-plugins-spinning.wasm`, and the plugin tests after it.
- **Code**: `e2e/playwright.config.ts`:

  ```ts
  command: `node serve.mjs ../build/e2eDist ${port}`,
  url: `http://localhost:${port}/plugins.json`,
  reuseExistingServer: !process.env.CI,
  ```

- **Cause**: a server for `build/devDist`, which has no test plugins, was already on port 8787, and it answers
  the URL that is checked.
- **Here**: `E2E_PORT` chooses another port.

### PW-2. Two runs at once break each other

- **Seen**: 2026-10-11.
- **Symptom**: different tests fail in each run with

  ```
  Error: browserContext.close: ENOENT: no such file or directory, open
  '.../e2e/test-results/.playwright-artifacts-0/traces/...-recording18.network'
  ```

- **Cause**: each run clears `e2e/test-results` when it starts and writes its traces there. A run whose output
  was cut off by `| head` keeps going in the background, which is how two runs came to overlap.
- **Here**: give one of the runs `--output=<another directory>`, and do not pipe a run into `head`.

## Adding an entry

Add an entry when a tool or a library does something its documentation or common sense does not predict and the
project has to work around it, wait for it, or live with it. Add it in the same change as the workaround.

- Take the next number of the section (`WI-16`, `KT-8`), or start a section with a short prefix for a new tool.
  Numbers are never reused; the code may refer to them in comments.
- Write the date and the versions. Without them nobody can tell later whether the problem is still there.
- Keep the exact message and the top frames of the stack trace: they are what the next person searches for.
- Quote the code that triggers the problem, with the path of the file, as it is in the version named. Prefer a
  reproducer of a few lines when there is one. Take the quote from the sources or from `javap`, never from
  memory; when the code could not be found, say so.
- Say how sure the cause is: verified, and how, or a guess.
- When the problem is fixed upstream or the workaround is removed, do not delete the entry: add a line
  **Resolved** with the date and the version.
- When it is reported, add a line **Upstream** with the link.
