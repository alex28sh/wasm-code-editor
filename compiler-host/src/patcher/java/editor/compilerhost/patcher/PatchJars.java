package editor.compilerhost.patcher;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.lang.reflect.AccessFlag;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * GraalVM Web Image workarounds applied to jar classes, written as loose class files to put first on the classpath:
 *  1. interfaces must not re-declare Object methods (builder SAM-detection crash);
 *  2. Atomic*FieldUpdater.newUpdater call sites go to editor.compilerhost.web.Updaters (the JDK ones throw NPE under Web Image),
 *     and the fields they name are collected into reachability metadata;
 *  3. ThreadMXBean CPU-time calls are dropped or return 0 (unsupported: one throws, the others break the builder);
 *  4. monitorenter/monitorexit in kotlinx.coroutines become plain pops (the builder fails on its unstructured locking,
 *     and there are no threads to exclude);
 *  5. Flight Recorder event types are null and never enabled (Flight Recorder is not supported), which is how
 *     the code that emits events already skips them;
 *  6. methods that only ask the JVM about things the image does not have get an empty body ({@link #STUBS}).
 */
public class PatchJars {
    static final Set<String> OBJECT_METHODS = Set.of("hashCode()I", "toString()Ljava/lang/String;", "equals(Ljava/lang/Object;)Z");
    static final String ATOMIC = "java/util/concurrent/atomic/Atomic";
    static final ClassDesc UPDATERS = ClassDesc.of("editor.compilerhost.web.Updaters");
    /** `class.method descriptor` of the methods that return nothing, zero or null instead of running. */
    static final Set<String> STUBS = Set.of(
            // Read the arguments and the agents of the JVM to tell whether a debugger is attached.
            "com/intellij/util/DebugAttachDetectorArgs.findDebugArgs()Ljava/lang/String;",
            "com/intellij/util/DebugAttachDetectorArgs.findAgentProperties()Ljava/util/Properties;",
            // Asks the garbage collector beans how long collections took.
            "com/intellij/openapi/util/LowMemoryWatcherManager.fetchMajorGcDurationAccumulated()J",
            // IntelliJ's shared thread pool: there are no threads to run its tasks, which are all housekeeping here
            // (watching for low memory, evicting caches), so they are dropped.
            "com/intellij/util/concurrency/AppScheduledExecutorService$BackendThreadPoolExecutor.execute(Ljava/lang/Runnable;)V",
            // Asks AWT, which needs a native library, whether this is the UI thread; there is no UI.
            "com/intellij/util/ui/EDT.isCurrentThreadEdt()Z"
    );
    static final Map<String, Set<String>> fields = new TreeMap<>();
    static int unresolved;

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        java.net.URL[] urls = new java.net.URL[args.length - 1];
        for (int i = 1; i < args.length; i++) urls[i - 1] = Path.of(args[i]).toUri().toURL();
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(ClassHierarchyResolver.defaultResolver()
                .orElse(ClassHierarchyResolver.ofResourceParsing(new java.net.URLClassLoader(urls, null)))));
        int interfaces = 0, callSiteClasses = 0;
        // A class that several jars contain is loaded from the first of them.
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < args.length; i++) {
            try (JarFile jar = new JarFile(args[i])) {
                for (JarEntry e : Collections.list(jar.entries())) {
                    if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/") || !seen.add(e.getName())) continue;
                    byte[] bytes = jar.getInputStream(e).readAllBytes();
                    ClassModel cm;
                    try { cm = ClassFile.of().parse(bytes); } catch (Exception ex) { continue; }
                    boolean strip = cm.flags().has(AccessFlag.INTERFACE) && cm.methods().stream().anyMatch(PatchJars::isRedeclaration);
                    boolean coroutines = cm.thisClass().asInternalName().startsWith("kotlinx/coroutines/");
                    stripMonitors = coroutines;
                    boolean updaters = usesUpdaters(cm);
                    String className = cm.thisClass().asInternalName();
                    boolean stubs = cm.methods().stream().anyMatch(m -> isStub(className, m));
                    if (!strip && !updaters && !stubs) continue;
                    if (strip) interfaces++;
                    if (updaters) { callSiteClasses++; collectFields(cm); }
                    ClassTransform t = ClassTransform.dropping(el -> el instanceof MethodModel m && isRedeclaration(m))
                            .andThen(ClassTransform.transformingMethodBodies(PatchJars::redirect))
                            .andThen(stubbing(className));
                    Path target = out.resolve(e.getName());
                    Files.createDirectories(target.getParent());
                    Files.write(target, cf.transformClass(cm, t));
                }
            }
        }
        StringBuilder json = new StringBuilder("{\n  \"reflection\": [\n");
        int n = 0;
        for (var en : fields.entrySet()) {
            json.append(n++ == 0 ? "" : ",\n").append("    {\"type\": \"").append(en.getKey()).append("\", \"fields\": [");
            int k = 0;
            for (String f : en.getValue()) json.append(k++ == 0 ? "" : ", ").append("{\"name\": \"").append(f).append("\"}");
            json.append("]}");
        }
        json.append("\n  ]\n}\n");
        Path meta = out.resolve("META-INF/native-image/editor/field-updaters/reachability-metadata.json");
        Files.createDirectories(meta.getParent());
        Files.writeString(meta, json);
        System.out.println("interfaces stripped: " + interfaces + ", classes with newUpdater: " + callSiteClasses
                + ", fields registered: " + fields.values().stream().mapToInt(Set::size).sum() + ", unresolved call sites: " + unresolved);
    }

    static boolean isStub(String className, MethodModel m) {
        return STUBS.contains(className + "." + m.methodName().stringValue() + m.methodType().stringValue());
    }

    /** Replaces the code of the methods of [STUBS] with a return of the default value of their type. */
    static ClassTransform stubbing(String className) {
        return (builder, element) -> {
            if (!(element instanceof MethodModel m) || !isStub(className, m)) {
                builder.with(element);
                return;
            }
            builder.withMethod(m.methodName(), m.methodType(), m.flags().flagsMask(), method -> method.withCode(code -> {
                switch (TypeKind.from(m.methodTypeSymbol().returnType())) {
                    case VOID -> code.return_();
                    case REFERENCE -> code.aconst_null().areturn();
                    case LONG -> code.lconst_0().lreturn();
                    case FLOAT -> code.fconst_0().freturn();
                    case DOUBLE -> code.dconst_0().dreturn();
                    default -> code.iconst_0().ireturn();
                }
            }));
        };
    }

    static boolean isRedeclaration(MethodModel m) {
        return m.flags().has(AccessFlag.ABSTRACT)
                && OBJECT_METHODS.contains(m.methodName().stringValue() + m.methodType().stringValue());
    }

    static boolean isNewUpdater(CodeElement el) {
        return el instanceof InvokeInstruction inv && inv.opcode() == Opcode.INVOKESTATIC
                && inv.name().equalsString("newUpdater") && inv.owner().asInternalName().startsWith(ATOMIC)
                && inv.owner().asInternalName().endsWith("FieldUpdater");
    }

    static boolean usesUpdaters(ClassModel cm) {
        for (MethodModel m : cm.methods()) {
            var code = m.code();
            if (code.isPresent() && code.get().elementStream().anyMatch(PatchJars::isRewritten)) return true;
        }
        return false;
    }

    static boolean isCpuTimeSwitch(CodeElement el) {
        return el instanceof InvokeInstruction inv && inv.name().equalsString("setThreadCpuTimeEnabled");
    }

    static boolean isCpuTimeRead(CodeElement el) {
        return el instanceof InvokeInstruction inv && inv.owner().asInternalName().equals("java/lang/management/ThreadMXBean")
                && (inv.name().equalsString("getCurrentThreadUserTime") || inv.name().equalsString("getCurrentThreadCpuTime"));
    }

    static boolean stripMonitors;

    static boolean isFlightRecorderCall(CodeElement el, String name) {
        return el instanceof InvokeInstruction inv && inv.owner().asInternalName().equals("jdk/jfr/EventType") && inv.name().equalsString(name);
    }

    static boolean isRewritten(CodeElement el) {
        return isNewUpdater(el) || isCpuTimeSwitch(el) || isCpuTimeRead(el) || (stripMonitors && el instanceof MonitorInstruction)
                || isFlightRecorderCall(el, "getEventType") || isFlightRecorderCall(el, "isEnabled");
    }

    static void redirect(CodeBuilder b, CodeElement el) {
        if (isFlightRecorderCall(el, "getEventType")) {
            b.pop().aconst_null();
        } else if (isFlightRecorderCall(el, "isEnabled")) {
            b.pop().iconst_0();
        } else if (el instanceof MonitorInstruction && stripMonitors) {
            b.pop();
        } else if (isCpuTimeSwitch(el)) {
            b.pop2(); // receiver and the boolean argument
        } else if (isCpuTimeRead(el)) {
            b.pop().lconst_0();
        } else if (isNewUpdater(el)) {
            InvokeInstruction inv = (InvokeInstruction) el;
            String owner = inv.owner().asInternalName();
            String name = owner.contains("Reference") ? "newRefUpdater" : owner.contains("Integer") ? "newIntUpdater" : "newLongUpdater";
            b.invokestatic(UPDATERS, name, inv.typeSymbol());
        } else {
            b.with(el);
        }
    }

    /** The class and field name are the constants loaded just before the call: (tclass[, vclass], name). */
    static void collectFields(ClassModel cm) {
        for (MethodModel m : cm.methods()) {
            if (m.code().isEmpty()) continue;
            List<String> classes = new ArrayList<>();
            String name = null;
            for (CodeElement el : m.code().get()) {
                if (el instanceof ConstantInstruction.LoadConstantInstruction ldc) {
                    ConstantDesc c = ldc.constantValue();
                    if (c instanceof ClassDesc cd) classes.add(cd.descriptorString());
                    else if (c instanceof String s) name = s;
                } else if (isNewUpdater(el)) {
                    boolean ref = ((InvokeInstruction) el).owner().asInternalName().contains("Reference");
                    int idx = classes.size() - (ref ? 2 : 1);
                    if (name == null || idx < 0) { unresolved++; System.out.println("unresolved: " + cm.thisClass().asInternalName() + "." + m.methodName()); }
                    else {
                        String d = classes.get(idx);
                        fields.computeIfAbsent(d.substring(1, d.length() - 1).replace('/', '.'), k -> new TreeSet<>()).add(name);
                    }
                    classes.clear(); name = null;
                }
            }
        }
    }
}
