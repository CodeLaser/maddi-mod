package io.codelaser.maddi.modification.analyzer.shadow;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.PropertyValueMap;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.graph.V;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.io.AnalysisFingerprint;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An incremental analysis recomputes the verdict closure of the changed types, against the carried verdicts of all
 * other types (refactor-base's ModificationAnalysisResource#incrementalAnalyze): the changed types, every type that
 * transitively uses them, and the supertypes whose methods they override. Its contract is a result byte-identical to
 * a cold run. With the reachability pass, the pass runs over the closure only: a method outside it is a boundary
 * whose carried verdict is evidence, where the cold run's pass walked its body.
 * <p>
 * Each case takes one type as the edit, clears the closure's cross-type-derived verdicts and re-analyses the closure
 * alone; the closure's verdicts must equal the cold run's.
 * <p>
 * The one rule that differed: a receiver chain ending in a call to a carried method, {@code new Builder().items(added)
 * .item(i).run()} with Builder outside the closure. The cold run's pass projects only the whole result for an analysed
 * outer callee (R2); taking the carried {@code run()} for a library method, the closure's pass followed the setter's
 * component link and wrote {@code User.build}'s {@code i} modified.
 */
public class TestReachabilityOnClosure extends CommonTest {

    @Language("java")
    private static final String ITEM = """
            package a.b;
            public class Item {
                int n;
                public void touch() { n++; }
                public int get() { return n; }
            }
            """;

    @Language("java")
    private static final String OPS = """
            package a.b;
            import java.util.List;
            import java.util.Map;
            public class Ops {
                public static void touchAll(List<Item> items) { for (Item i : items) i.touch(); }
                public static int sum(List<Item> items) { int s = 0; for (Item i : items) s += i.get(); return s; }
                public static void add(List<Item> items, Item i) { items.add(i); }
                public static Item first(List<Item> items) { return items.get(0); }
                public static void touchValue(Map<String, Item> m, String k) { m.get(k).touch(); }
            }
            """;

    @Language("java")
    private static final String SINK = """
            package a.b;
            import java.util.List;
            public interface Sink { void accept(List<Item> items); }
            """;

    @Language("java")
    private static final String TOUCHER = """
            package a.b;
            import java.util.List;
            public class Toucher implements Sink { public void accept(List<Item> items) { Ops.touchAll(items); } }
            """;

    @Language("java")
    private static final String READER = """
            package a.b;
            import java.util.List;
            public class Reader implements Sink { public void accept(List<Item> items) { Ops.sum(items); } }
            """;

    @Language("java")
    private static final String BUILDER = """
            package a.b;
            import java.util.List;
            public class Builder {
                private List<Item> items;
                private Item item;
                public Builder items(List<Item> items) { this.items = items; return this; }
                public Builder item(Item item) { this.item = item; return this; }
                public void run() { Ops.add(items, item); }
            }
            """;

    @Language("java")
    private static final String NODE = """
            package a.b;
            import java.util.List;
            public class Node {
                Node next;
                final List<Node> children = new java.util.ArrayList<>();
                public int size() { return next == null ? 1 : 1 + next.size(); }
                public int sizeAll() { int s = 1; for (Node c : children) s += c.sizeAll(); return s; }
                public static Node rest(Node n) { return n.next; }
                public void unlink() { this.next = rest(this); }
            }
            """;

    @Language("java")
    private static final String USER = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Map;
            public class User {
                private final List<Item> items = new ArrayList<>();
                private final List<Item> added = new ArrayList<>();
                public void touchMine() { Ops.touchAll(items); }
                public int total() { return Ops.sum(items); }
                public void feed(Sink sink) { sink.accept(items); }
                public void build(Item i) { new Builder().items(added).item(i).run(); }
                public void touchFirst() { Ops.first(items).touch(); }
                public void touchIn(Map<String, Item> m) { Ops.touchValue(m, "k"); }
                public int nodes(Node n) { return n.size() + n.sizeAll(); }
                public void cut(Node n) { n.next = Node.rest(n); }
                public static Node skip(Node n) { Node r = Node.rest(n); r.next = null; return r; }
            }
            """;

    private static final Map<String, String> SOURCES = Map.of("a.b.Item", ITEM, "a.b.Ops", OPS, "a.b.Sink", SINK,
            "a.b.Toucher", TOUCHER, "a.b.Reader", READER, "a.b.Builder", BUILDER, "a.b.Node", NODE,
            "a.b.User", USER);

    // the verdicts the analysis computes, per element, in a stable order
    private static final List<Property> PROPERTIES = List.of(PropertyImpl.NON_MODIFYING_METHOD,
            PropertyImpl.STRUCTURALLY_NON_MODIFYING_METHOD, PropertyImpl.INDEPENDENT_METHOD,
            PropertyImpl.UNMODIFIED_PARAMETER, PropertyImpl.STRUCTURALLY_UNMODIFIED_PARAMETER,
            PropertyImpl.INDEPENDENT_PARAMETER, PropertyImpl.UNMODIFIED_FIELD,
            PropertyImpl.STRUCTURALLY_UNMODIFIED_FIELD, PropertyImpl.IMMUTABLE_TYPE, PropertyImpl.INDEPENDENT_TYPE,
            PropertyImpl.CONTAINER_TYPE);

    @TestFactory
    public List<DynamicTest> reachability() {
        return SOURCES.keySet().stream().sorted().map(edited -> DynamicTest.dynamicTest(edited,
                () -> assertClosureEqualsCold(edited, true))).toList();
    }

    @TestFactory
    public List<DynamicTest> fixpoint() {
        return SOURCES.keySet().stream().sorted().map(edited -> DynamicTest.dynamicTest(edited,
                () -> assertClosureEqualsCold(edited, false))).toList();
    }

    private void assertClosureEqualsCold(String edited, boolean modReach) throws java.io.IOException {
        beforeEach(); // a fresh inspector: the same sources cannot be parsed twice in one
        ParseResult parseResult = javaInspector.parse(SOURCES, javaInspector.failFast()).parseResult();
        Set<TypeInfo> all = SOURCES.keySet().stream().map(parseResult::findType).collect(Collectors.toSet());
        G<Info> graph = prepAnalyzer.doPrimaryTypesReturnGraph(all);
        List<Info> order = new io.codelaser.maddi.callgraph.ComputeAnalysisOrder().go(graph);
        analyzer(modReach).analyze(order, graph);
        Map<String, String> cold = verdicts(all);

        Set<TypeInfo> closure = closure(parseResult.findType(edited), graph);
        closure.forEach(TestReachabilityOnClosure::clearCrossTypeDerived);
        G<Info> closureGraph = graph.subGraph(graph.vertices().stream()
                .filter(v -> closure.contains(primary(v.t()))).collect(Collectors.toSet()));
        List<Info> closureOrder = order.stream().filter(i -> closure.contains(primary(i))).toList();
        analyzer(modReach).analyze(closureOrder, closureGraph);
        Map<String, String> incremental = verdicts(all);

        assertEquals(String.join("\n", cold.values()), String.join("\n", incremental.values()),
                "closure of " + edited + ": " + closure.stream().map(TypeInfo::simpleName).sorted().toList());
    }

    private IteratingAnalyzerImpl analyzer(boolean modReach) {
        return new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30)
                .setStopWhenCycleDetectedAndNoImprovements(true)
                .setModificationViaReachability(modReach)
                .build());
    }

    private static TypeInfo primary(Info info) {
        return info.typeInfo().primaryType();
    }

    // refactor-base's verdictClosure: forward over use edges (a dependency-graph edge runs from user to used),
    // backward over override edges
    private static Set<TypeInfo> closure(TypeInfo edited, G<Info> graph) {
        Map<TypeInfo, Set<TypeInfo>> users = new HashMap<>();
        graph.edgeStream().forEach(e -> users.computeIfAbsent(primary(e.to().t()), _ -> new HashSet<>())
                .add(primary(e.from().t())));
        Set<TypeInfo> closure = new HashSet<>(Set.of(edited));
        Deque<TypeInfo> work = new ArrayDeque<>(closure);
        while (!work.isEmpty()) {
            TypeInfo t = work.poll();
            for (TypeInfo user : users.getOrDefault(t, Set.of())) {
                if (closure.add(user)) work.add(user);
            }
            t.constructorAndMethodStream().forEach(m -> m.overrides().forEach(o -> {
                TypeInfo owner = o.typeInfo().primaryType();
                if (!owner.compilationUnit().externalLibrary() && closure.add(owner)) work.add(owner);
            }));
        }
        return closure;
    }

    private static void clearCrossTypeDerived(TypeInfo t) {
        java.util.function.Predicate<Property> toClear = p -> AnalysisFingerprint.CROSS_TYPE_DERIVED_ONLY.test(p)
                                                              && !PropertyImpl.IMPLEMENTATIONS.key().equals(p.key());
        t.analysis().removeIf(toClear);
        t.constructorAndMethodStream().forEach(m -> {
            m.analysis().removeIf(toClear);
            m.parameters().forEach(p -> p.analysis().removeIf(toClear));
        });
        t.fields().forEach(f -> f.analysis().removeIf(toClear));
    }

    private static Map<String, String> verdicts(Set<TypeInfo> types) {
        Map<String, String> out = new TreeMap<>();
        for (TypeInfo t : types) {
            out.put(t.fullyQualifiedName(), t.fullyQualifiedName() + " " + values(t.analysis()));
            t.constructorAndMethodStream().forEach(m -> {
                out.put(m.fullyQualifiedName(), m.fullyQualifiedName() + " " + values(m.analysis()));
                for (ParameterInfo pi : m.parameters()) {
                    out.put(pi.fullyQualifiedName(), pi.fullyQualifiedName() + " " + values(pi.analysis()));
                }
            });
            t.fields().forEach(f -> out.put(f.fullyQualifiedName(), f.fullyQualifiedName() + " " + values(f.analysis())));
        }
        return out;
    }

    private static String values(PropertyValueMap analysis) {
        return PROPERTIES.stream().map(p -> {
            var v = analysis.getOrNull(p, io.codelaser.maddi.cst.api.analysis.Value.class);
            return v == null ? null : p.key() + "=" + v;
        }).filter(Objects::nonNull).collect(Collectors.joining(" "));
    }
}
