package io.codelaser.maddi.modification.analyzer.nullability;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CallResults}: what a call's result stands for, through lambdas, method references, streams and factories.
 */
public class TestCallResults extends NullabilityFixtures {

    @DisplayName("lambdas: a lambda's parameter takes the nullability of the functional method's parameter")
    @Test
    public void lambdas() {
        Report report = run("a.b.F", """
                package a.b;
                import java.util.*;
                class F {
                    interface I { int p(String s); }
                    static void each(I i) { i.p(null); }
                    static void use() {
                        each(s -> s.length());
                        each(new I() { public int p(String t) { return t.length(); } });
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.F.I.p(String):0:s"));
        assertEquals("String?", byName.get("a.b.F.$0.p(String):0:s"), "the lambda's parameter");
        assertEquals("String?", byName.get("a.b.F.$1.p(String):0:t"), "the anonymous class's parameter");
    }

    @DisplayName("library factories: an argument for the method's type variable is a value of the result's slot")
    @Test
    public void factories() {
        Report report = run("a.b.Y", """
                package a.b;
                import java.util.*;
                class Y {
                    private static final Map<Integer, Integer[]> TABLE = Map.of(1, new Integer[]{2, null}, 3, new Integer[]{4});
                    private static final List<String> NAMES = Arrays.asList("a", null);
                    private static final List<String> CLEAN = List.of("a", "b");
                    static List<String> wrap(String s) { String t = s.isEmpty() ? null : s; return Arrays.asList(t, "x"); }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Map<Integer, Integer?[]>", byName.get("a.b.Y.TABLE"));
        assertEquals("List<String?>", byName.get("a.b.Y.NAMES"));
        assertEquals("List<String>", byName.get("a.b.Y.CLEAN"));
        assertEquals("List<String?>", byName.get("a.b.Y.wrap(String)"));
    }

    @DisplayName("Kotlin: a lambda's parameter is a value of its target's type argument")
    @Test
    public void lambdaParameterFromTarget() {
        Report report = run("a.b.LP", """
                package a.b;
                class LP {
                    interface ThrowingFunction<T, R> { R apply(T t) throws Exception; }
                    static Integer fromJson(String json) { return json.length(); }
                    static <T> T extract(String text, ThrowingFunction<String, T> parser) {
                        try { return parser.apply(text); } catch (Exception e) { return null; }
                    }
                    static Integer parse(String text) { return extract(text, s -> fromJson(s)); }
                    static Integer none() { return parse(null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("ThrowingFunction<String?, T!>", s.get("a.b.LP.extract(String,a.b.LP.ThrowingFunction):1:parser"),
                s.toString());
        assertEquals("String?", s.get("a.b.LP.fromJson(String):0:json"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a lambda returning null added to a list of functions makes the slot's result nullable")
    @Test
    public void lambdaIntoReceiverSlot() {
        Report report = run("a.b.LS", """
                package a.b;
                import java.util.ArrayList;
                import java.util.HashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.function.Function;
                class LS {
                    static String next(String current) {
                        List<Function<String, String>> chain = new ArrayList<>();
                        chain.add(input -> {
                            if (input.isEmpty()) return null;
                            Map<String, String> states = new HashMap<>();
                            states.put("a", "b");
                            return states.get(current);
                        });
                        return chain.get(0).apply(current) + "";
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        assertEquals("List<Function<String, String?>>", l.get("chain"), l + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a list's nullable function slot reaches a generic constructor through iterator(), jenkins InstallUtil")
    @Test
    public void lambdaSlotThroughIterator() {
        Report report = run("a.b.LI", """
                package a.b;
                import java.util.ArrayList;
                import java.util.Iterator;
                import java.util.List;
                import java.util.function.Function;
                import java.util.function.Supplier;
                class LI {
                    static String next(String current) {
                        List<Function<Supplier<String>, String>> functions = new ArrayList<>();
                        functions.add(proceed -> step(current, proceed));
                        functions.add(input -> {
                            if (current.isEmpty()) return null;
                            return current + "x";
                        });
                        Chain<String> chain = new Chain<>(functions.iterator());
                        return chain.get();
                    }
                    static String step(String current, Supplier<String> proceed) {
                        return current.isEmpty() ? proceed.get() : current;
                    }
                    static class Chain<T> implements Supplier<T> {
                        private final Iterator<Function<Supplier<T>, T>> functions;
                        Chain(Iterator<Function<Supplier<T>, T>> functions) {
                            this.functions = functions;
                        }
                        @Override
                        public T get() {
                            return functions.next().apply(this);
                        }
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("List<Function<Supplier<String?>, String?>>", l.get("functions"), l + "\n" + explain(report));
        assertEquals("Chain<String?>", l.get("chain"), l + "\n" + explain(report));
        assertEquals("Supplier<String?>", s.get("a.b.LI.step(String,java.util.function.Supplier):1:proceed"),
                s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a stream filtered with Objects::nonNull collects non-null elements, jenkins CspBuilder, Jenkins.getAgentProtocols")
    @Test
    public void streamFilterNonNull() {
        Report report = run("a.b.SF", """
                package a.b;
                import java.util.ArrayList;
                import java.util.Arrays;
                import java.util.List;
                import java.util.Objects;
                import java.util.Set;
                import java.util.TreeSet;
                import java.util.stream.Collectors;
                class SF {
                    static class Protocol {
                        String name;
                        String getName() {
                            return name;
                        }
                    }
                    List<Protocol> protocols = new ArrayList<>();
                    Set<String> nonNull(String... values) {
                        final List<String> additions = new ArrayList<>(Arrays.stream(values).toList());
                        Set<String> nonNullAdditions = additions.stream().filter(Objects::nonNull).collect(Collectors.toSet());
                        return nonNullAdditions;
                    }
                    Set<String> names() {
                        return protocols.stream().map(Protocol::getName).filter(n -> n != null)
                                .collect(Collectors.toCollection(TreeSet::new));
                    }
                    static void use(SF sf) {
                        sf.nonNull("a", null);
                        sf.protocols.add(new Protocol());
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.name(), k(pt)));
        assertEquals("Set<String>", l.get("nonNullAdditions"), l + "\n" + explain(report));
        assertEquals("Set<String>", s.get("a.b.SF.names()"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a method reference's nullable result reaches the functional parameter's result slot, jenkins CronTab")
    @Test
    public void methodReferenceResult() {
        Report report = run("a.b.MR", """
                package a.b;
                import java.util.function.Supplier;
                class MR {
                    String message;
                    String getErrorMessage() {
                        return message.isEmpty() ? null : message;
                    }
                    static class Listener {
                        private final Supplier<String> supplier;
                        Listener(Supplier<String> supplier) {
                            this.supplier = supplier;
                        }
                        String text() {
                            return supplier.get();
                        }
                    }
                    Listener listener() {
                        return new Listener(this::getErrorMessage);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Supplier<String?>", s.get("a.b.MR.Listener.<init>(java.util.function.Supplier):0:supplier"),
                s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a lambda's nullable result given to computeIfAbsent reaches the map's values, jenkins XStream2")
    @Test
    public void computeIfAbsentLambda() {
        Report report = run("a.b.CA", """
                package a.b;
                import java.util.concurrent.ConcurrentHashMap;
                class CA {
                    final ConcurrentHashMap<Class<?>, String> cache = new ConcurrentHashMap<>();
                    String compute(Class<?> t) {
                        return t.isArray() ? null : t.getName();
                    }
                    String find(Class<?> t) {
                        String result = cache.computeIfAbsent(t, unused -> compute(t));
                        return result;
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("ConcurrentHashMap<Class<?!>, String?>", s.get("a.b.CA.cache"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a lambda's nullable result through '<T> T call(Supplier<T>)' makes the call's result nullable, jenkins CrumbIssuer")
    @Test
    public void lambdaThroughGenericSupplier() {
        Report report = run("a.b.LG", """
                package a.b;
                import java.util.function.Supplier;
                class LG {
                    static <T> T ifOverridden(Supplier<T> supplier, String name) {
                        if (name.isEmpty()) {
                            return supplier.get();
                        }
                        throw new AbstractMethodError(name);
                    }
                    String issue(String salt) {
                        return salt.isEmpty() ? null : salt;
                    }
                    String issueOld(String salt) {
                        return ifOverridden(() -> issue(salt), "issue");
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String?", s.get("a.b.LG.issueOld(String)"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a three-argument collect returns the container its accumulator fills, jenkins PluginManager")
    @Test
    public void collectContainer() {
        Report report = run("a.b.CC", """
                package a.b;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                class CC {
                    static class Plugin {
                        String url;
                        Plugin(String url) {
                            this.url = url;
                        }
                    }
                    List<Plugin> plugins = new ArrayList<>();
                    void add() {
                        plugins.add(new Plugin(null));
                    }
                    Map<Plugin, String> deprecated() {
                        return plugins.stream()
                                .collect(LinkedHashMap::new, (map, plugin) -> map.put(plugin, plugin.url), Map::putAll);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Map<Plugin, String?>", s.get("a.b.CC.deprecated()"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: computeIfAbsent's result is the map's value, not its key, fernflower BytecodeSourceMapper")
    @Test
    public void computeIfAbsentValue() {
        Report report = run("a.b.CV", """
                package a.b;
                import java.util.ArrayList;
                import java.util.Collections;
                import java.util.HashMap;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                class CV {
                    private final Map<String, Map<String, Map<Integer, Integer>>> mapping = new LinkedHashMap<>();
                    void addMapping(String className, String methodName, int offset, int line) {
                        Map<String, Map<Integer, Integer>> class_mapping = mapping.computeIfAbsent(className, k -> new LinkedHashMap<>());
                        Map<Integer, Integer> method_mapping = class_mapping.computeIfAbsent(methodName, k -> new HashMap<>());
                        method_mapping.putIfAbsent(offset, line);
                    }
                    void use() {
                        addMapping(null, "m", 1, 2);
                    }
                    void dump() {
                        for (Map.Entry<String, Map<String, Map<Integer, Integer>>> class_entry : mapping.entrySet()) {
                            for (Map.Entry<String, Map<Integer, Integer>> method_entry : class_entry.getValue().entrySet()) {
                                Map<Integer, Integer> method_mapping = method_entry.getValue();
                                List<Integer> offsets = new ArrayList<>(method_mapping.keySet());
                                Collections.sort(offsets);
                            }
                        }
                    }
                }
                """, Policy.KOTLIN);
        String l = locals(report);
        assertTrue(l.contains("addMapping.class_mapping: Map<String, Map<Integer, Integer>>\n"), l + "\n" + explain(report));
        assertTrue(l.contains("dump.class_entry: Entry<String?, Map<String, Map<Integer, Integer>>>\n"), l);
        assertTrue((l + "\n").contains("dump.offsets: List<Integer>\n"), l);
    }

    @DisplayName("Kotlin: a local in a lambda body, peeked from a deque and tested for null, is nullable, fernflower ExprProcessor")
    @Test
    public void lambdaLocalPeek() {
        Report report = run("a.b.LP", """
                package a.b;
                import java.util.ArrayDeque;
                import java.util.Deque;
                import java.util.List;
                import java.util.stream.Collectors;
                class LP {
                    static class Entry {
                    }
                    static class Helper {
                        private final Deque<Entry> paths = new ArrayDeque<>();
                        Deque<Entry> getPaths() {
                            return paths;
                        }
                        void writeTo(StringBuilder sb) {
                            sb.append("x");
                        }
                    }
                    static List<Helper> nested(StringBuilder sb, List<Helper> helpers) {
                        return helpers.stream().filter(helper -> {
                            Entry path = helper.getPaths().peek();
                            if (path == null) {
                                helper.writeTo(sb);
                                return false;
                            }
                            return true;
                        }).collect(Collectors.toList());
                    }
                    static boolean plain(StringBuilder sb, Helper helper) {
                        Entry entry = helper.getPaths().peek();
                        if (entry == null) {
                            helper.writeTo(sb);
                            return false;
                        }
                        return true;
                    }
                }
                """, Policy.KOTLIN);
        String l = locals(report);
        assertTrue(l.contains("plain.entry: Entry?"), l + "\n" + explain(report));
        assertTrue(l.contains("test.path: Entry?"), l + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a method returning its parameter filtered of nulls takes nullable elements, jenkins Iterators.removeNull")
    @Test
    public void removeNullParameter() {
        Report report = run("a.b.RM", """
                package a.b;
                import java.util.Iterator;
                import java.util.Objects;
                import java.util.function.Predicate;
                class RM {
                    static class Iterators {
                        static <E> Iterator<E> filter(Iterator<E> unfiltered, Predicate<? super E> retainIfTrue) {
                            return unfiltered;
                        }
                    }
                    static <T> Iterator<T> removeNull(final Iterator<T> itr) {
                        return Iterators.filter(itr, Objects::nonNull);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Iterator<T?>", s.get("a.b.RM.removeNull(java.util.Iterator):0:itr"), s + "\n" + explain(report));
        assertEquals("Iterator<T!>", s.get("a.b.RM.removeNull(java.util.Iterator)"), s + "\n" + explain(report));
    }

    @DisplayName("Kotlin: a call given a variable known non-null returns no null of it, guava's TypeResolver.resolveInternal")
    @Test
    public void knownNonNullArgument() {
        Report report = run("a.b.KA", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                class KA {
                    final Map<String, Object> map = new HashMap<>();
                    Object resolve(Object type) {
                        if (type instanceof String s) return s.trim();
                        return type;
                    }
                    Object resolveInternal(String key) {
                        Object type = map.get(key);
                        if (type == null) {
                            return key;
                        }
                        return new KA().resolve(type);
                    }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String all = s + "\n" + explain(report);
        assertFalse(s.get("a.b.KA.resolveInternal(String)").endsWith("?"), all);
    }

    @DisplayName("Kotlin: a local assigned a nullable value inside a loop condition is nullable; an anonymous class's super arguments flow")
    @Test
    public void conditionAssignmentAndAnonymousSuper() {
        Report report = run("a.b.CA", """
                package a.b;
                class CA {
                    Runnable nextTask;
                    void drain() {
                        Runnable queuedTask;
                        while ((queuedTask = nextTask) != null) {
                            nextTask = null;
                            queuedTask.run();
                        }
                    }
                    static final class Queue { Runnable nextTask; java.util.concurrent.Executor nextExecutor; }
                    void drainOther(Queue executingTaskQueue, Runnable localTask) {
                        try {
                            localTask.run();
                            Runnable queuedTask;
                            java.util.concurrent.Executor queuedExecutor;
                            while ((queuedTask = executingTaskQueue.nextTask) != null
                                && (queuedExecutor = executingTaskQueue.nextExecutor) != null) {
                                executingTaskQueue.nextTask = null;
                                executingTaskQueue.nextExecutor = null;
                                queuedExecutor.execute(queuedTask);
                            }
                        } finally {
                            executingTaskQueue.nextTask = null;
                        }
                    }
                    abstract static class Base {
                        final CharSequence toSplit;
                        Base(CharSequence toSplit) { this.toSplit = toSplit; }
                        abstract int next();
                    }
                    static Base make(CharSequence s) {
                        return new Base(s) { int next() { return 0; } };
                    }
                    static Base nothing() { return make(null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        String l = locals(report);
        assertTrue(l.contains("drain.queuedTask: Runnable?"), l);
        assertTrue(l.contains("drainOther.queuedTask: Runnable?"), l);
        assertTrue(l.contains("drainOther.queuedExecutor: Executor?"), l);
        assertEquals("CharSequence?", s.get("a.b.CA.Base.<init>(CharSequence):0:toSplit"), s.toString());
    }

    @DisplayName("a null returned by a method that may return its argument does not flow back into the argument")
    @Test
    public void identityReturn() {
        Report report = run("a.b.I", """
                package a.b;
                import java.util.*;
                class I {
                    private final Map<String, String> map = new HashMap<>();
                    private String check(String name) {
                        if (map.containsKey(name)) return name;
                        String noDot = name.replace('.', '_');
                        if (!name.equals(noDot) && map.containsKey(noDot)) return noDot;
                        return null;
                    }
                    String get(String key) {
                        String checked = check(key);
                        if (checked == null) {
                            final String upper = key.toUpperCase();
                            if (!upper.equals(key)) {
                                checked = check(upper);
                            }
                        }
                        if (checked == null) return null;
                        return map.get(checked);
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.I.check(String)"));
        assertEquals("String", byName.get("a.b.I.get(String):0:key"), "the return's null is not the argument's");
        assertEquals("String", byName.get("a.b.I.check(String):0:name"));
    }

    @DisplayName("Kotlin: an analysed generic method's own null (E?) reaches the variable its result is assigned to")
    @Test
    public void classTypeVariableResults() {
        Report report = run("a.b.V", """
                package a.b;
                import java.util.*;
                class V {
                    static class VB<E, K> extends ArrayList<E> {
                        private final Map<K, Integer> map = new HashMap<>();
                        void addWithKey(E element, K key) { map.put(key, size()); super.add(element); }
                        E getWithKey(K key) {
                            Integer index = map.get(key);
                            if (index == null) return null;
                            return super.get(index);
                        }
                    }
                    static class Block { int id; }
                    private final VB<Block, Integer> blocks = new VB<>();
                    private final VB<Block, Integer> holes = new VB<>();
                    private final Map<String, Object> attributes = new HashMap<>();
                    void fill(Block b) { blocks.addWithKey(b, b.id); holes.add(null); }
                    Block first() { Block b = holes.get(0); return b; }
                    @SuppressWarnings("unchecked")
                    <T> T attribute(String name, Class<T> type) { return (T) attributes.get(name); }
                    String name() { String n = attribute("name", String.class); return n; }
                    <X> X id(X x) { return x; }
                    String same(String s) { String t = id(s); return t; }
                    List<Block> range(int from, int to) {
                        List<Block> out = new ArrayList<>();
                        for (int j = from; j < to; j++) {
                            Block block = blocks.getWithKey(j);
                            out.add(block);
                        }
                        return out;
                    }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("E?", byName.get("a.b.V.VB.getWithKey(Object)"));
        // 'block = blocks.getWithKey(j)' is nullable, and so is what it is added to (fernflower ControlFlowGraph:351)
        assertEquals("List<Block?>", byName.get("a.b.V.range(int,int)"));
        assertEquals("VB<Block, Integer>", byName.get("a.b.V.blocks"), "the slot itself holds no null");
        // a list method on a two-argument subclass: ArrayList's E is the receiver's argument 0
        assertEquals("VB<Block?, Integer>", byName.get("a.b.V.holes"));
        assertEquals("Block?", byName.get("a.b.V.first()"));
        // a method type variable's own null (a map lookup), but not one a parameter of that type carries
        assertEquals("String?", byName.get("a.b.V.name()"));
        assertEquals("String", byName.get("a.b.V.same(String)"));
    }

    @DisplayName("a library call's nullable result as an alternative of the value: a switch arm, a conditional branch")
    @Test
    public void callResultAlternatives() {
        Report report = run("a.b.SC", """
                package a.b;
                import java.util.Map;
                class SC {
                    private static final Map<String, Integer> TYPES = Map.of("a", 1);
                    static void rule(int k, String s) {
                        Object value;
                        value = switch (k) {
                            case 0 -> TYPES.get(s);
                            default -> s;
                        };
                        take(value);
                    }
                    static void branch(boolean b, String s) {
                        Object value = b ? TYPES.get(s) : s;
                        put(value);
                    }
                    static Object returned(int k, String s) {
                        return switch (k) {
                            case 0 -> s;
                            default -> {
                                String t = s.trim();
                                yield TYPES.get(t);
                            }
                        };
                    }
                    static void take(Object o) { }
                    static void put(Object o) { }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Object?", byName.get("a.b.SC.take(Object):0:o"), "fernflower MatchEngine:124");
        assertEquals("Object?", byName.get("a.b.SC.put(Object):0:o"));
        assertEquals("Object?", byName.get("a.b.SC.returned(int,String)"));
        assertEquals("""
                branch.value: Object?
                returned.t: String
                rule.value: Object?""", locals(report));
    }

    // fernflower's SwitchPatternHelper: case values through two stream pipelines and two records
    @Language("java")
    private static final String STREAMS = """
            package a.b;
            import java.util.*;
            import java.util.stream.*;
            class SP {
                static class Exprent { }
                record Case(List<Exprent> exprents) { }
                record Pair(Exprent exprent, int edge) { }
                private final List<List<Exprent>> caseValues = new ArrayList<>();
                SP() { List<Exprent> vals = new ArrayList<>(); vals.add(null); caseValues.add(vals); }
                List<List<Exprent>> getCaseValues() { return caseValues; }
                void resort(List<Case> cases) {
                    getCaseValues().clear();
                    getCaseValues().addAll(cases.stream().map(t -> t.exprents()).toList());
                }
                List<Case> build() {
                    List<List<Exprent>> sorted = new ArrayList<>();
                    for (List<Exprent> cv : caseValues) {
                        List<Pair> pairs = IntStream.range(0, cv.size()).mapToObj(i -> new Pair(cv.get(i), i)).toList();
                        sorted.add(pairs.stream().map(p -> p.exprent()).collect(Collectors.toList()));
                    }
                    return IntStream.range(0, sorted.size()).mapToObj(i -> new Case(sorted.get(i))).collect(Collectors.toList());
                }
                static List<Exprent> firsts(List<Case> cases) {
                    return cases.stream().filter(c -> !c.exprents().isEmpty()).map(c -> c.exprents().get(0)).toList();
                }
                static Set<Case> same(List<Case> cases) { return cases.stream().map(c -> c).collect(Collectors.toSet()); }
                static Map<Integer, Exprent> byEdge(List<Pair> pairs) {
                    return pairs.stream().collect(Collectors.toMap(p -> p.edge(), p -> p.exprent()));
                }
            }
            """;

    @DisplayName("stream pipelines: map, filter, toList, collect(toList/toSet/toMap) carry the elements' nodes (Kotlin)")
    @Test
    public void streamPipelines() {
        Report report = run("a.b.SP", STREAMS, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        String locals = locals(report);
        assertEquals("List<List<Exprent?>>", byName.get("a.b.SP.caseValues"));
        assertEquals("Exprent?", byName.get("a.b.SP.Pair.exprent"), "new Pair(cv.get(i), i) inside mapToObj");
        assertTrue(locals.contains("build.sorted: List<List<Exprent?>>"), locals);
        assertEquals("List<Exprent?>", byName.get("a.b.SP.Case.exprents"), "new Case(sorted.get(i)) and resort's addAll");
        // the synthesised accessors follow their components (they have no variable data of their own)
        assertEquals("List<Exprent?>", byName.get("a.b.SP.Case.exprents()"));
        assertEquals("Exprent?", byName.get("a.b.SP.Pair.exprent()"));
        assertEquals("List<Exprent?>", byName.get("a.b.SP.firsts(java.util.List)"));
        assertEquals("Set<Case>", byName.get("a.b.SP.same(java.util.List)"));
        assertEquals("Map<Integer, Exprent?>", byName.get("a.b.SP.byEdge(java.util.List)"));
    }

    // langchain4j's JsonSchemaElementJsonUtils.fromMap. Here the argument's links carry the flow; on the corpus they
    // are empty (no links for an immutable String), and callResultArgument supplies the edge. This pins the flow, not
    // the empty-links case, which needs the corpus (TestFernflowerNullabilityCauses, NULLABILITY_CORPUS=langchain4j).
    @DisplayName("a nullable helper result handed to a builder setter, as langchain4j's JsonSchemaElementJsonUtils.fromMap")
    @Test
    public void builderSetterFromHelper() {
        Report report = run("a.b.BS", """
                package a.b;
                import java.util.Map;
                class BS {
                    static class S {
                        private final String description;
                        S(Builder b) { this.description = b.description; }
                        static Builder builder() { return new Builder(); }
                        static class Builder {
                            private String description;
                            Builder description(String description) { this.description = description; return this; }
                            S build() { return new S(this); }
                        }
                    }
                    static class E {
                        private final String description;
                        E(Builder b) { this.description = b.description; }
                        static Builder builder() { return new Builder(); }
                        static class Builder {
                            private String description;
                            Builder description(String description) { this.description = description; return this; }
                            E build() { return new E(this); }
                        }
                    }
                    private static String optionalString(Map<String, Object> map, String field) {
                        Object value = map.get(field);
                        if (value == null) return null;
                        if (!(value instanceof String)) {
                            throw new IllegalArgumentException(field + " must be a string, but was: " + className(value));
                        }
                        return (String) value;
                    }
                    private static String className(Object obj) { return obj == null ? "null" : obj.getClass().getSimpleName(); }
                    static <T> T ensureNotNull(T object, String name) {
                        if (object == null) throw new IllegalArgumentException(name);
                        return object;
                    }
                    private static boolean allStrings(java.util.List<?> list) {
                        return list.stream().allMatch(String.class::isInstance);
                    }
                    static Object fromMap(Map<String, Object> map, String type) {
                        ensureNotNull(map, "map");
                        if (map.containsKey("enum")) {
                            Object enumObj = map.get("enum");
                            if (!(enumObj instanceof java.util.List<?> enumList)) {
                                throw new IllegalArgumentException("enum must be a list, but was: " + className(enumObj));
                            }
                            Object enumTypeObj = map.get("type");
                            if (!allStrings(enumList) || (enumTypeObj != null && !"string".equals(enumTypeObj))) {
                                return null;
                            }
                            return E.builder()
                                    .description(optionalString(map, "description"))
                                    .build();
                        }
                        return switch (type) {
                            case "string" -> map.isEmpty() ? S.builder().description(optionalString(map, "description")).build() : null;
                            default -> null;
                        };
                    }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .filter(e -> e.getKey().fullyQualifiedName().contains("Builder.description(String)"))
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String?", byName.get("a.b.BS.E.Builder.description(String):0:description"));
        assertEquals("String?", byName.get("a.b.BS.S.Builder.description(String):0:description"));
    }

    // CodeLaser/maddi-mod#22 gap 9 (nacos SearchableProperties): the return is nullable only through 'defaultValue', so the
    // result of a call passing a non-null default is not
    @Language("java")
    private static final String GAP9 = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            import java.util.Optional;
            class G9 {
                private final Map<String, Object> map = new HashMap<>();
                <T> Optional<T> search(String key, Class<T> type) { return Optional.ofNullable(type.cast(map.get(key))); }
                Integer getInteger(String key) { return getInteger(key, null); }
                Integer getInteger(String key, Integer defaultValue) {
                    return search(key, Integer.class).orElse(defaultValue);
                }
                int timeout() {
                    Integer n = getInteger("timeout", 5);
                    return n.intValue();
                }
                int retries() {
                    Integer r = getInteger("retries");
                    return r == null ? 0 : r;
                }
                Integer timeoutValue() { return getInteger("timeout", 5); }
                Integer retriesValue() { return getInteger("retries"); }
            }
            """;

    @DisplayName("gap 9: a return nullable only through a parameter is not nullable at a call whose argument is not")
    @Test
    public void returnThroughParameterPerCall() {
        Report report = run("a.b.G9", GAP9);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Integer?", s.get("getInteger(1:defaultValue)"), s.toString());
        String r = s.entrySet().stream().filter(e -> e.getKey().equals("getInteger()")).map(Map.Entry::getValue)
                .collect(Collectors.joining(","));
        assertTrue(r.contains("Integer?"), "the declaration stays nullable: " + s);
        assertTrue(report.callResultNonNull(calls("timeout", "getInteger").getFirst()), report.explain(
                calls("timeout", "getInteger").getFirst().methodInfo()));
        assertFalse(report.callResultNonNull(calls("getInteger", "getInteger").getFirst()),
                "getInteger(key, null) passes the null");
    }

    @DisplayName("gap 9, Kotlin: a return assigned a call passing a non-null default is non-null")
    @Test
    public void returnThroughParameterPerCallKotlin() {
        Report report = run("a.b.G9", GAP9, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Integer", s.get("timeoutValue()"), s.toString());
        assertEquals("Integer?", s.get("retriesValue()"), s.toString());
    }

    // langchain4j ListeningEmbeddingModel.embed: 'textSegment == null ? List.of() : List.of(textSegment)' puts no null
    // into the list, so the builder's list is a List<TextSegment> like the context's final field
    @DisplayName("a library factory's argument known non-null at the call: no null element")
    @Test
    public void factoryArgumentUnderCondition() {
        Report report = run("a.b.LF", """
                package a.b;
                import java.util.List;
                class LF {
                    private final List<String> items;
                    LF(List<String> items) { this.items = items; }
                    static LF of(String s) {
                        List<String> list = s == null ? List.of() : List.of(s);
                        return new LF(list);
                    }
                    static LF none() { return of(null); }
                    List<String> items() { return items; }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String?", s.get("a.b.LF.of(String):0:s"), s.toString());
        assertEquals("List<String>", s.get("a.b.LF.<init>(java.util.List):0:items"), s.toString());
    }

    // langchain4j DefaultAiServiceStartedEvent: 'Optional.ofNullable(this.systemMessage)' with a nullable field is an
    // Optional<SystemMessage>, as the interface it implements declares; an Optional never holds null
    @DisplayName("Optional.ofNullable of a nullable value: the Optional's type argument stays non-null")
    @Test
    public void optionalOfNullable() {
        Report report = run("a.b.OE", """
                package a.b;
                import java.util.Optional;
                class OE {
                    private final String name;
                    OE(String name) { this.name = name; }
                    static OE none() { return new OE(null); }
                    Optional<String> name() { return Optional.ofNullable(name); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String?", s.get("a.b.OE.name"), s.toString());
        assertEquals("Optional<String>", s.get("a.b.OE.name()"), s.toString());
    }

    // langchain4j EmbeddingStoreContentRetriever: 'this(..., (query) -> maxResults, ...)' with a nullable maxResults
    @DisplayName("Kotlin: a lambda's nullable result makes the functional parameter's result type argument nullable")
    @Test
    public void lambdaResultIntoTypeArgument() {
        Report report = run("a.b.LR", """
                package a.b;
                import java.util.function.Function;
                class LR {
                    private final Function<String, Integer> provider;
                    LR(Integer max) { this(query -> max); }
                    private LR(Function<String, Integer> provider) { this.provider = provider; }
                    static LR none() { return new LR((Integer) null); }
                    static LR some() { return new LR(3); }
                    Integer get(String query) { return provider.apply(query); }
                    static <T> T call(java.util.concurrent.Callable<T> action) throws Exception { return action.call(); }
                    static String named() throws Exception { return call(() -> "x"); }
                    static Object none(int i) throws Exception { return call(() -> null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("Integer?", s.get("a.b.LR.<init>(Integer):0:max"), s.toString());
        assertEquals("Function<String, Integer?>", s.get("a.b.LR.<init>(java.util.function.Function):0:provider"),
                s.toString());
        assertEquals("Callable<T!>", s.get("a.b.LR.call(java.util.concurrent.Callable):0:action"), s.toString());
        assertEquals("String", s.get("a.b.LR.named()"), s.toString());
    }
}
