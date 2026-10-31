package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.impl.SingleIterationAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Seeds}: where a null comes from, a literal, a library return, a field's default, an argument; and what is no
 * evidence of one.
 */
public class TestSeeds extends NullabilityFixtures {

    @Language("java")
    private static final String RETURNS = """
            package a.b;
            class R {
                String direct() { return null; }
                String viaCall() { return direct(); }
                String ternary(boolean b, String s) { return b ? s : null; }
                String literal() { return "x"; }
                String identity(String s) { return s; }
                int primitive() { return 3; }
                <T> T generic(T t) { return t; }
            }
            """;

    @DisplayName("returns: a null literal, through a call, through a ternary; non-null literals and primitives")
    @Test
    public void returns() {
        Report report = run("a.b.R", RETURNS);
        System.out.println(explain(report));
        assertEquals("""
                direct(): String?
                generic(): T!
                generic(0:t): T!
                identity(): String
                identity(0:s): String
                literal(): String
                primitive(): int
                ternary(): String?
                ternary(0:b): boolean
                ternary(1:s): String
                viaCall(): String?""", verdicts(report));
    }

    @Language("java")
    private static final String FIELDS = """
            package a.b;
            class F {
                private final String fin;
                private String assigned;
                private String lazy;
                private String cleared = "x";
                private String initNull = null;
                private String fromParam;
                F(String fin, String assigned) { this.fin = fin; this.assigned = assigned; }
                String getLazy() { if (lazy == null) lazy = "y"; return lazy; }
                void clear() { cleared = null; }
                void setFromParam(String s) { this.fromParam = s; }
                String getFromParam() { return fromParam; }
            }
            """;

    @DisplayName("fields: final, assigned in every constructor, Java's default value, assigned null, initialized null")
    @Test
    public void fields() {
        Report report = run("a.b.F", FIELDS);
        System.out.println(explain(report));
        assertEquals("""
                <init>(0:fin): String
                <init>(1:assigned): String
                assigned: String
                cleared: String?
                fin: String
                fromParam: String?
                getFromParam(): String?
                getLazy(): String
                initNull: String?
                lazy: String?
                setFromParam(0:s): String""", verdicts(report));
    }

    @Language("java")
    private static final String PARAMETERS = """
            package a.b;
            class P {
                private String f;
                void take(String s) { this.f = s; }
                void passNull() { take(null); }
                String keep(String k) { return k; }
                void passLocal(boolean b) { String x = b ? "a" : null; keep(x); }
                void never(String n) { System.out.println(n); }
                void callNever() { never("z"); }
                String tolerant(String t) { if (t == null) return ""; return t; }
            }
            """;

    @DisplayName("parameters: a null argument, a possibly-null local as argument, never null; the field it lands in")
    @Test
    public void parameters() {
        Report report = run("a.b.P", PARAMETERS);
        System.out.println(explain(report));
        assertEquals("""
                f: String?
                keep(): String?
                keep(0:k): String?
                never(0:n): String
                passLocal(0:b): boolean
                take(0:s): String?
                tolerant(): String
                tolerant(0:t): String?""", verdicts(report));
        // tolerant(): after 'if (t == null) return ""', t is known non-null where it is returned (M4)
    }

    @Language("java")
    private static final String LIBRARY = """
            package a.b;
            import java.util.*;
            class L {
                private final Map<String, String> map = new HashMap<>();
                private final NavigableMap<String, String> nav = new TreeMap<>();
                String lookup(String k) { return map.get(k); }
                String viaLocal(String k) { String v = map.get(k); return v; }
                Map.Entry<String, String> first() { return nav.firstEntry(); }
                int size() { return map.size(); }
                @Override public boolean equals(Object other) { return other == this; }
                @Override public int hashCode() { return 1; }
            }
            """;

    @DisplayName("JDK null contracts: Map.get and NavigableMap.firstEntry may return null; equals accepts null")
    @Test
    public void library() {
        Report report = run("a.b.L", LIBRARY);
        System.out.println(explain(report));
        assertEquals("""
                equals(): boolean
                equals(0:other): Object?
                first(): Entry<String, String>?
                hashCode(): int
                lookup(): String?
                lookup(0:k): String
                map: Map<String, String>
                nav: NavigableMap<String, String>
                size(): int
                viaLocal(): String?
                viaLocal(0:k): String""", verdicts(report));
    }

    @Language("java")
    private static final String DEGRADED = """
            package a.b;
            class D {
                String compute() { return "c"; }
                void big(boolean b) {
                    String known = compute();
                    String none = null;
                    if (b) none = known;
                    System.out.println(none);
                }
            }
            """;

    @DisplayName("a degraded method (no links): a local assigned null is still seen, the others are unspecified")
    @Test
    public void degradedLocals() {
        TypeInfo t = javaInspector.parse("a.b.D", DEGRADED);
        List<Info> ao = prepWork(t);
        new SingleIterationAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setNullability(true).build()).go(ao, 3);
        MethodInfo big = t.findUniqueMethod("big", 1);
        // what LinkComputerImpl writes when it gives up on a method; its variable data then carries no links
        big.analysis().setAllowControlledOverwrite(io.codelaser.maddi.cst.impl.analysis.PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE);
        Report report = new NullabilityPass(Policy.NULL_MARKED).go(ao);
        String all = locals(report);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("big.none: String?"), all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("big.known: String!"), all);
    }

    // fernflower NestedClassProcessor.insertLocalVars: 'classNode = classNode.parent!!' after 23f8bf65
    @DisplayName("a for loop's updater assigns its variable the parent field's default null")
    @Test
    public void forLoopUpdater() {
        Report report = run("a.b.FL", """
                package a.b;
                class FL {
                    static class Node {
                        public Node parent;
                        String name;
                    }
                    static void link(Node child, Node parent) { child.parent = parent; }
                    static int depth(Node child) {
                        int d = 0;
                        for (Node node = child; node != null; node = node.parent) { d++; }
                        return d;
                    }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        String locals = locals(report);
        System.out.println(locals);
        assertTrue(locals.contains("depth.node: Node?"), locals);
    }

    @DisplayName("a null returned through a call: the chain names the callee, not a null written in the caller")
    @Test
    public void returnedNull() {
        Report report = run("a.b.R", """
                package a.b;
                class R {
                    String name = "n";
                    String find(String key) { if (key.isEmpty()) return null; return key + name; }
                    String relay(String key) { return find(key); }
                    String relay2(String key) { String r = relay(key); return r; }
                    void take(String s) { }
                    void pass(String key) { take(find(key)); }
                }
                """);
        System.out.println(explain(report));
        TypeInfo r = parsed;
        MethodInfo find = r.findUniqueMethod("find", 1);
        MethodInfo relay = r.findUniqueMethod("relay", 1);
        MethodInfo relay2 = r.findUniqueMethod("relay2", 1);
        ParameterInfo s = r.findUniqueMethod("take", 1).parameters().getFirst();
        assertEquals("return a.b.R.find(String) <- null in a.b.R.find(String)", report.explain(find));
        assertEquals("return a.b.R.relay(String) <- return a.b.R.find(String) <- null in a.b.R.find(String)",
                report.explain(relay));
        assertTrue(report.explain(relay2).contains("null in a.b.R.find(String)"), report.explain(relay2));
        assertTrue(report.explain(s).contains("null in a.b.R.find(String)"), report.explain(s));
    }

    @DisplayName("Kotlin: a library null the next statement exits on does not seed the local")
    @Test
    public void deadLibraryNull() {
        Report report = run("a.b.DN", """
                package a.b;
                import java.util.NavigableMap;
                import java.util.NoSuchElementException;
                class DN {
                    static final class Range {
                        final String lowerBound;
                        Range(String lowerBound) { this.lowerBound = lowerBound; }
                    }
                    static Range span(NavigableMap<String, String> map, Range sub, boolean b) {
                        String lowerBound;
                        if (b) {
                            lowerBound = sub.lowerBound;
                        } else {
                            lowerBound = map.ceilingKey(sub.lowerBound);
                            if (lowerBound == null || lowerBound.isEmpty()) {
                                throw new NoSuchElementException();
                            }
                        }
                        return new Range(lowerBound);
                    }
                    static String mapped(NavigableMap<String, String> map, String k) {
                        String m = map.get(k);
                        if (m == null) throw new NoSuchElementException();
                        return m;
                    }
                    static String early(NavigableMap<String, String> map, String k) {
                        String v = map.get(k);
                        System.out.println(v);
                        if (v == null) throw new NoSuchElementException();
                        return v;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String", s.get("a.b.DN.Range.lowerBound"), s + "\n" + explain(report));
        assertTrue(locals(report).contains("early.v: String?"), "read before the test: " + locals(report));
        assertTrue(locals(report).contains("mapped.m: String?"), "Kotlin's Map.get is V?: " + locals(report));
        // the local itself is declared nullable (NOGUARDEDLOCALS): its null flows nowhere, Kotlin smart-casts it
        assertTrue(locals(report).contains("span.lowerBound: String?"), locals(report));
    }

    @DisplayName("Kotlin: a null argument in a field initializer, nacos GrpcClient.recAbilityContext")
    @Test
    public void nullArgumentInFieldInitializer() {
        Report report = run("a.b.FI", """
                package a.b;
                class FI {
                    private final Context context = new Context(null);
                    static class Context {
                        private Object connection;
                        Context(Object connection) { this.connection = connection; }
                        void reset(Object connection) { this.connection = connection; }
                    }
                    void ready(Object c) { context.reset(c); }
                    static final Holder<?> NULL = new Holder<>(null);
                    static class Holder<V> {
                        private final V value;
                        Holder(V value) { this.value = value; }
                        V get() { return value; }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Object?", s.get("a.b.FI.Context.<init>(Object):0:connection"), s + "\n" + explain(report));
        assertEquals("V!", s.get("a.b.FI.Holder.<init>(Object):0:value"), "a type variable's instantiation: " + s);
    }

    @DisplayName("Kotlin: 'return null' in an else branch inside try/finally, nacos NacosDelayTaskExecuteEngine.removeTask")
    @Test
    public void returnNullInTryFinally() {
        Report report = run("a.b.RT", """
                package a.b;
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                import java.util.concurrent.locks.ReentrantLock;
                class RT {
                    interface Engine<T extends Task> { T removeTask(Object key); }
                    static class Task { boolean shouldProcess() { return true; } }
                    static class Impl implements Engine<Task> {
                        private final Map<Object, Task> tasks = new ConcurrentHashMap<>();
                        private final ReentrantLock lock = new ReentrantLock();
                        @Override
                        public Task removeTask(Object key) {
                            lock.lock();
                            try {
                                Task task = tasks.get(key);
                                if (null != task && task.shouldProcess()) {
                                    return tasks.remove(key);
                                } else {
                                    return null;
                                }
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Task?", s.get("a.b.RT.Impl.removeTask(Object)"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: an enum's valueOf takes a String, nacos ServerStatusManager.java:64")
    @Test
    public void enumValueOf() {
        Report report = run("a.b.EV", """
                package a.b;
                class EV {
                    enum Status { UP, DOWN }
                    private String overridden = null;
                    String getOverridden() { return overridden; }
                    Status status() {
                        if (overridden != null && !overridden.isEmpty()) return Status.valueOf(getOverridden());
                        return Status.UP;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String", s.get("a.b.EV.Status.valueOf(String):0:name"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: 'return null' after a conditional return makes the result nullable, jenkins Hudson.getSlave")
    @Test
    public void returnNullAfterConditionalReturn() {
        Report report = run("a.b.RN", """
                package a.b;
                class RN {
                    static class Node {
                    }
                    static class Slave extends Node {
                    }
                    Node node;
                    @io.codelaser.maddi.annotation.Nullable
                    Node getNode(String name) {
                        return node;
                    }
                    public Slave getSlave(String name) {
                        Node n = getNode(name);
                        if (n instanceof Slave)
                            return (Slave) n;
                        return null;
                    }
                    @io.codelaser.maddi.annotation.Nullable
                    Slave getPlugin(String name) {
                        return node instanceof Slave s ? s : null;
                    }
                    boolean isNewer(String v) {
                        return v.isEmpty();
                    }
                    public Slave getUpdateInfo() {
                        Slave p = getPlugin("x");
                        if (p != null && isNewer("y")) return p;
                        return null;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Slave?", s.get("a.b.RN.getSlave(String)"), s + "\n" + explain(report));
        assertEquals("Slave?", s.get("a.b.RN.getUpdateInfo()"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a null argument to an anonymous class's creation in a field initializer reaches the superclass's constructor, jenkins CronTab")
    @Test
    public void anonymousFieldInitializerNull() {
        Report report = run("a.b.CF", """
                package a.b;
                abstract class CF {
                    private final String name;
                    private final CF lower;
                    private CF(String name, CF lower) {
                        this.name = name;
                        this.lower = lower;
                    }
                    abstract int bits();
                    static final CF MINUTE = new CF("minute", null) {
                        @Override
                        int bits() {
                            return 1;
                        }
                    };
                    static final CF HOUR = new CF("hour", MINUTE) {
                        @Override
                        int bits() {
                            return 2;
                        }
                    };
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("CF?", s.get("a.b.CF.<init>(String,a.b.CF):1:lower"), s + "\n" + explain(report));
        assertEquals("String", s.get("a.b.CF.<init>(String,a.b.CF):0:name"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: arguments in an assertThrows(NullPointerException) lambda carry no null into the callee, guava ImmutableClassToInstanceMapTest")
    @Test
    public void rejectedNullFlows() {
        Report report = run("a.b.RF", """
                package a.b;
                import java.util.Collections;
                import java.util.Map;
                import java.util.Objects;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class RF {
                    static Class<?> wrap(Class<?> type) {
                        Objects.requireNonNull(type);
                        return type;
                    }
                    static int copyOf(Map<Class<?>, Number> map) {
                        int n = 0;
                        for (Map.Entry<Class<?>, Number> entry : map.entrySet()) {
                            n += wrap(entry.getKey()).getName().length();
                        }
                        return n;
                    }
                    void testCopyOfNulls() {
                        Map<Class<?>, Number> nullKey = Collections.singletonMap(null, 1.0);
                        assertThrows(NullPointerException.class, () -> copyOf(nullKey));
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertFalse(s.get("a.b.RF.wrap(Class):0:type").endsWith("?"), s + "\n" + explain(report));
        assertFalse(s.get("a.b.RF.copyOf(java.util.Map):0:map").startsWith("Map<Class<?!>?"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a null passed inside assertThrows(NullPointerException.class, ...) is no evidence")
    @Test
    public void rejectedNullArgument() {
        Report report = run("a.b.RN", """
                package a.b;
                import java.util.Objects;
                class RN {
                    interface Executable { void execute() throws Throwable; }
                    static <T extends Throwable> T assertThrows(Class<T> type, Executable e) { return null; }
                    static String put(String key) { return Objects.requireNonNull(key).trim(); }
                    static int size(String s) { return s == null ? 0 : s.length(); }
                    void test() {
                        assertThrows(NullPointerException.class, () -> put(null));
                        assertThrows(IllegalStateException.class, () -> size(null));
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String", s.get("a.b.RN.put(String):0:key"), s.toString());
        assertEquals("String?", s.get("a.b.RN.size(String):0:s"), "another exception: still evidence");
    }

    @Language("java")
    private static final String RUNTIME_NULLS = """
            package a.b;
            import java.util.*;
            class RN {
                static class St { int id; }
                private final Map<Integer, List<String[]>> ranges = new HashMap<>();
                void saveEdge(int id, boolean monitor, boolean cont) {
                    ranges.computeIfAbsent(id, k -> new ArrayList<>())
                            .add(new String[]{"x", monitor ? "1" : null, cont ? "1" : null});
                }
                static boolean choice(St head, List<? super St> lst, int n) {
                    St post = null;
                    if (n > 2) post = new St();
                    lst.add(head);
                    lst.remove(post);
                    lst.add(0, post);
                    return true;
                }
                static void use(St head) {
                    List<St> lst = new ArrayList<>();
                    choice(head, lst, 3);
                }
                static int next(St sample, List<St> successors, boolean last) {
                    Map<String, St[]> mapNext = new HashMap<>();
                    for (St successor : successors) {
                        mapNext.put(sample.id + "#" + successor.id, new St[]{sample, successor, last ? successor : null});
                    }
                    return unique(new HashSet<>(mapNext.values()));
                }
                static int unique(Set<St[]> setNext) {
                    int n = 0;
                    for (St[] arr : setNext) if (arr[2] != null) n++;
                    return n;
                }
            }
            """;

    @DisplayName("nulls Kotlin must carry: a null alternative in an array initializer; a null added to a '? super' list")
    @Test
    public void runtimeNulls() {
        Report report = run("a.b.RN", RUNTIME_NULLS, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Map<Integer, List<String?[]>>", byName.get("a.b.RN.ranges"),
                "fernflower FlattenStatementsHelper.saveEdge");
        assertEquals("List<St?>", byName.get("a.b.RN.choice(a.b.RN.St,java.util.List,int):1:lst"),
                "fernflower DecHelper.isChoiceStatement");
        assertTrue(locals(report).contains("next.mapNext: Map<String, St?[]>"),
                "fernflower FinallyProcessor.compareSubGraphsEx: " + locals(report));
        assertEquals("Set<St?[]>", byName.get("a.b.RN.unique(java.util.Set):0:setNext"),
                "fernflower FinallyProcessor.getUniqueNext(graph, new HashSet<>(mapNext.values()))");
    }

    @DisplayName("JDK hint: JarFile.getManifest() returns null for a jar without a manifest")
    @Test
    public void jarManifest() {
        Report report = run("a.b.JM", """
                package a.b;
                import java.io.IOException;
                import java.util.jar.JarFile;
                import java.util.jar.Manifest;
                class JM {
                    static class Unit {
                        private Manifest manifest;
                        void setManifest(Manifest manifest) { this.manifest = manifest; }
                    }
                    static void addSpace(JarFile archive, Unit unit) throws IOException {
                        unit.setManifest(archive.getManifest());
                    }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Manifest?", byName.get("a.b.JM.Unit.setManifest(java.util.jar.Manifest):0:manifest"),
                "fernflower StructContext.addSpace -> ContextUnit.setManifest");
        assertEquals("Manifest?", byName.get("a.b.JM.Unit.manifest"));
    }

    // a library return annotated nullable in its class file, with no hint: maddi-support's 'SetOnce.getOrDefaultNull'
    // is '@Nullable T getOrDefaultNull()' (libs/support hints are not loaded). Before 2026-10-10 only the hints seeded
    // a library return: nacos' io.kubernetes.client getters ('@jakarta.annotation.Nullable') never reached a
    // dereference. (JUnit 6's JSpecify-annotated 'ExtensionContext.Store.get' cannot serve: jspecify is not on the
    // test class path, so its annotations are skipped as unmappable.)
    @DisplayName("a library return annotated nullable in its class file seeds, like a hinted one")
    @Test
    public void libraryReturnAnnotatedInClassFile() {
        Report report = run("a.b.LR", """
                package a.b;
                import io.codelaser.maddi.support.SetOnce;
                class LR {
                    static String value(SetOnce<String> once) { return once.getOrDefaultNull(); }
                    static String trimmed(String s) { return s.trim(); }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("String?", s.get("value()"), s.toString());
        assertEquals("String", s.get("trimmed()"), s.toString());
    }

    // Class.getClassLoader() is null only for a class of the bootstrap loader: never for this analysis' own types
    // (nacos MysqlHealthCheckProcessor-style 'getClass().getClassLoader()'), still for 'String.class'
    @DisplayName("getClassLoader() of an analysed type's class is not a null source")
    @Test
    public void applicationClassLoader() {
        Report report = run("a.b.CL", """
                package a.b;
                class CL {
                    ClassLoader own() { return getClass().getClassLoader(); }
                    static ClassLoader literal() { return CL.class.getClassLoader(); }
                    static ClassLoader of(CL cl) { return cl.getClass().getClassLoader(); }
                    static ClassLoader bootstrap() { return String.class.getClassLoader(); }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("ClassLoader", s.get("own()"), s.toString());
        assertEquals("ClassLoader", s.get("literal()"), s.toString());
        assertEquals("ClassLoader", s.get("of()"), s.toString());
        assertEquals("ClassLoader?", s.get("bootstrap()"), "String is loaded by the bootstrap loader: " + s);
    }

    @DisplayName("System.getProperty of a standard key is not null; of another key it may be")
    @Test
    public void standardSystemProperty() {
        Report report = run("a.b.SP", """
                package a.b;
                class SP {
                    static String os() { return System.getProperty("os.name"); }
                    static String custom() { return System.getProperty("my.app.home"); }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("String", s.get("os()"), s.toString());
        assertEquals("String?", s.get("custom()"), s.toString());
    }

    /*
     A null literal, a cast null, or a conditional with a null branch handed on through this(...) makes the parameter,
     and the field it is assigned to, nullable (langchain4j OutputGuardrailResult: the static SUCCESS is built by the
     no-arg constructor, which passes '(AiMessage) null').
     */
    @DisplayName("a null passed through this(...) makes the parameter nullable")
    @Test
    public void nullThroughThis() {
        Report report = run("a.b.OG", """
                package a.b;
                import java.util.List;
                class OG {
                    static class Msg { static Msg from(String s) { return new Msg(); } }
                    private static final OG SUCCESS = new OG();
                    private final String result;
                    private final Msg successfulMsg;
                    private final Object successfulResult;
                    private final Msg castMsg;
                    private final Msg condMsg;
                    private OG() { this("ok", (Msg) null, null, null, null); }
                    private OG(String text) { this("ok", null, text, null, text == null ? null : Msg.from(text)); }
                    private OG(Msg cast) { this("ok", null, null, (Msg) null, null); }
                    private OG(String result, Msg successfulMsg, Object successfulResult, Msg castMsg, Msg condMsg) {
                        this.result = result;
                        this.successfulMsg = successfulMsg;
                        this.successfulResult = successfulResult;
                        this.castMsg = castMsg;
                        this.condMsg = condMsg;
                    }
                    Msg successfulMsg() { return successfulMsg; }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Msg?", s.get("<init>(1:successfulMsg)"), s.toString());
        assertEquals("Msg?", s.get("successfulMsg"), s.toString());
        assertEquals("Object?", s.get("<init>(2:successfulResult)"), s.toString());
        assertEquals("Msg?", s.get("<init>(3:castMsg)"), s.toString());
        assertEquals("Msg?", s.get("<init>(4:condMsg)"), s.toString());
        assertEquals("String", s.get("<init>(0:result)"), s.toString());
    }

    @Language("java")
    private static final String OVERLOADED_THIS = """
            package a.b;
            import java.util.Collections;
            import java.util.List;
            import java.util.Optional;
            final class OGR {
                enum Result { SUCCESS, SUCCESS_WITH_RESULT, FAILURE, FATAL }
                static final class Msg { static Msg from(String s) { return new Msg(); } }
                record Failure(String message) { }
                private static final OGR SUCCESS = new OGR();
                private final Result result;
                private final Msg successfulMsg;
                private final Object successfulResult;
                private final List<Failure> failures;
                private OGR(Result result, Msg successfulMsg, Object successfulResult, List<Failure> failures) {
                    this.result = java.util.Objects.requireNonNull(result, "result");
                    this.successfulMsg = successfulMsg;
                    this.successfulResult = successfulResult;
                    this.failures = Optional.ofNullable(failures).orElseGet(List::of);
                }
                private OGR(Result result, String successfulText, Object successfulResult, List<Failure> failures) {
                    this(result, successfulText == null ? null : Msg.from(successfulText), successfulResult, failures);
                }
                private OGR() { this(Result.SUCCESS, (Msg) null, null, Collections.emptyList()); }
                private OGR(String successfulText) { this(Result.SUCCESS_WITH_RESULT, successfulText, null, Collections.emptyList()); }
                private OGR(Msg successfulMsg) { this(Result.SUCCESS_WITH_RESULT, successfulMsg, null, Collections.emptyList()); }
                OGR(List<Failure> failures, boolean fatal) { this(fatal ? Result.FATAL : Result.FAILURE, (Msg) null, null, failures); }
                static OGR success() { return SUCCESS; }
                static OGR successWith(String text) { return text == null ? success() : new OGR(text); }
                static OGR successWith(Msg msg) { return msg == null ? success() : new OGR(msg); }
                static OGR failure(List<Failure> failures) { return new OGR(failures, false); }
                Msg successfulMsg() { return successfulMsg; }
            }
            """;

    private void overloadedThis(Policy policy) {
        Report report = run("a.b.OGR", OVERLOADED_THIS, policy);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info) + (info instanceof ParameterInfo pi
                ? pi.methodInfo().parameters().stream().map(q -> q.parameterizedType().typeInfo().simpleName())
                        .collect(Collectors.joining(",", "[", "]")) : ""), k(pt)));
        assertEquals("Msg?", s.get("<init>(1:successfulMsg)[Result,Msg,Object,List]"), explain(report));
        assertEquals("Msg?", s.get("successfulMsg"), s.toString());
    }

    @DisplayName("a cast null through an overloaded this(...) makes the parameter nullable")
    @Test
    public void nullThroughOverloadedThis() {
        overloadedThis(Policy.NULL_MARKED);
    }

    @DisplayName("a cast null through an overloaded this(...) makes the parameter nullable: Kotlin")
    @Test
    public void nullThroughOverloadedThisKotlin() {
        overloadedThis(Policy.KOTLIN);
    }

    @DisplayName("a conditional's null branch through an overloaded this(...) makes the parameter nullable")
    @Test
    public void conditionalNullThroughOverloadedThis() {
        Report report = run("a.b.OGC", """
                package a.b;
                final class OGC {
                    static final class Msg { static Msg from(String s) { return new Msg(); } }
                    private final Msg msg;
                    private OGC(int code, Msg msg) { this.msg = msg; }
                    private OGC(int code, String text) { this(code, text == null ? null : Msg.from(text)); }
                    static OGC of(String text) { return new OGC(1, text); }
                    static OGC of(Msg msg) { return new OGC(1, java.util.Objects.requireNonNull(msg)); }
                    Msg msg() { return msg; }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info) + (info instanceof ParameterInfo pi
                ? pi.methodInfo().parameters().stream().map(q -> q.parameterizedType().typeInfo().simpleName())
                        .collect(Collectors.joining(",", "[", "]")) : ""), k(pt)));
        assertEquals("Msg?", s.get("<init>(1:msg)[int,Msg]"), s.toString());
        assertEquals("Msg?", s.get("msg"), s.toString());
    }
    @DisplayName("JUnit: a parameterized test's null source passes null to its parameters (langchain4j DocumentTest)")
    @Test
    public void junitNullSource() {
        Report report = run("a.b.JN", """
                package a.b;
                class JN {
                    @interface NullSource {}
                    @interface ValueSource { String[] strings(); }
                    static String from(String text) { return ensureNotBlank(text, "text"); }
                    static String ensureNotBlank(String s, String name) {
                        if (s == null || s.isBlank()) throw new IllegalArgumentException(name + " cannot be null or blank");
                        return s;
                    }
                    @NullSource @ValueSource(strings = {"", " "})
                    void fails(String text, int n) { from(text); }
                    void ok() { from("x"); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.JN.fails(String,int):0:text"), "JUnit's null");
        assertEquals("String?", byName.get("a.b.JN.from(String):0:text"));
        assertEquals("String?", byName.get("a.b.JN.ensureNotBlank(String,String):0:s"));
        assertEquals("String", byName.get("a.b.JN.ensureNotBlank(String,String)"), "non-null after the throw guard");
        assertEquals("String", byName.get("a.b.JN.from(String)"));
    }
}
