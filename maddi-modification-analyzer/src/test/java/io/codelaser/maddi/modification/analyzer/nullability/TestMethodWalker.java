package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.expression.VariableExpression;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.IfElseStatement;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.statement.TryStatement;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MethodWalker}: the walk of a method body, its use sites, smart casts, locals, guards and key presence.
 */
public class TestMethodWalker extends NullabilityFixtures {

    @DisplayName("locals: keyed by declaration, so same-named locals of sibling blocks have their own verdict")
    @Test
    public void locals() {
        Report report = run("a.b.V", LOCALS);
        System.out.println(explain(report));
        assertEquals("""
                caught.ex: RuntimeException
                flows.w: String?
                flows.y: String?
                loops.e: String
                loops.f: String?
                reassigned.x: String?
                sibling.s#2: String
                sibling.s: String?""", locals(report));
        assertEquals("String?", k(report.verdicts().entrySet().stream()
                .filter(e -> e.getKey() instanceof MethodInfo mi && "flows".equals(mi.name()))
                .findFirst().orElseThrow().getValue()));
        // the lookup the printer uses: by declaring element
        MethodInfo sibling = parsed.findUniqueMethod("sibling", 1);
        io.codelaser.maddi.cst.api.statement.Statement ifElse = sibling.methodBody().statements().getFirst();
        var first = (io.codelaser.maddi.cst.api.statement.LocalVariableCreation) ifElse.block().statements().getFirst();
        var second = (io.codelaser.maddi.cst.api.statement.LocalVariableCreation) ifElse.otherBlocksStream()
                .findFirst().orElseThrow().statements().getFirst();
        assertEquals("String?", k(report.local(sibling, first, first.localVariable())));
        assertEquals("String", k(report.local(sibling, second, second.localVariable())));
    }

    @Language("java")
    private static final String NESTED_LOCALS = """
            package a.b;
            import java.util.ArrayList;
            import java.util.LinkedList;
            import java.util.List;
            class N {
                static class Node { List<Node> succ = new ArrayList<>(); Node next; }
                void flatten(Node root) {
                    class StackEntry {
                        final Node node;
                        StackEntry(Node node) { this.node = node; }
                    }
                    LinkedList<StackEntry> stack = new LinkedList<>();
                    stack.add(new StackEntry(root));
                    mainloop:
                    while (!stack.isEmpty()) {
                        StackEntry statEntry = stack.removeFirst();
                        if (statEntry == null) continue mainloop;
                        Node source = statEntry.node;
                        if (source != null) {
                            for (int i = 0; i < source.succ.size(); i++) {
                                Node shortEntry = null;
                                Node longEntry = null;
                                if (i > 2) { shortEntry = source.succ.get(i); longEntry = source; }
                                while (true) {
                                    StackEntry entry = null;
                                    if (shortEntry != null) entry = new StackEntry(shortEntry);
                                    if (entry == null || longEntry == null) break;
                                    stack.add(entry);
                                }
                            }
                        }
                    }
                }
            }
            """;

    @DisplayName("locals initialized null, nested in loops after a local class declaration (fernflower)")
    @Test
    public void nestedLocals() {
        Report report = run("a.b.N", NESTED_LOCALS);
        String all = locals(report);
        System.out.println(all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("flatten.shortEntry: Node?"), all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("flatten.longEntry: Node?"), all);
        org.junit.jupiter.api.Assertions.assertTrue(all.contains("flatten.entry: StackEntry?"), all);
    }

    @Language("java")
    private static final String USE_SITES = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            class U {
                private final Map<String, String> map = new HashMap<>();
                private String cache;
                U() { cache = "c"; }
                String lookup(String k) {
                    String v = map.get(k);
                    if (v == null) return "default";
                    cache = v;
                    return v;
                }
                void passOn(String k) {
                    String v = map.get(k);
                    if (v != null) {
                        take(v);
                    }
                }
                void take(String t) { System.out.println(t); }
                int deref(String s) {
                    String w = map.get(s);
                    int n = w.length();
                    keep(w);
                    return n;
                }
                void keep(String x) { System.out.println(x); }
                void requireIt(String p) {
                    if (p == null) throw new IllegalArgumentException();
                    System.out.println(p);
                }
                String unguarded(String k) { String u = map.get(k); return u; }
                private String lazy;
                String getLazy() { String result = lazy; return result == null ? lazy = "z" : result; }
            }
            """;

    @DisplayName("use sites (M4): a null checked away, dereferenced away, or thrown on does not travel")
    @Test
    public void useSites() {
        Report report = run("a.b.U", USE_SITES);
        System.out.println(explain(report));
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> label(e.getKey()), e -> k(e.getValue())));
        assertEquals("String", byLabel.get("cache"), "assigned after 'if (v == null) return'");
        assertEquals("String", byLabel.get("lookup()"), "returned after the guard");
        assertEquals("String", byLabel.get("take(0:t)"), "passed inside 'if (v != null)'");
        assertEquals("String", byLabel.get("keep(0:x)"), "passed after 'w.length()'");
        // a null test is an expectation of null, even when its branch throws (guava's checking methods)
        assertEquals("String?", byLabel.get("requireIt(0:p)"));
        assertEquals("String?", byLabel.get("unguarded()"), "no check: Map.get's null travels");
        assertEquals("String?", byLabel.get("lazy"), "Java's default value");
        assertEquals("String", byLabel.get("getLazy()"), "the lazy getter: null only in the branch that assigns");
        String locals = locals(report);
        org.junit.jupiter.api.Assertions.assertTrue(locals.contains("lookup.v: String?"), locals);
    }

    @Language("java")
    private static final String SMART_CASTS = """
            package a.b;
            import java.util.Objects;
            class K {
                int checked(String s) { if (s == null) return 0; return s.length(); }
                int required(String r) { Objects.requireNonNull(r); return r.length(); }
            }
            """;

    @DisplayName("smart casts: only what Kotlin derives too; requireNonNull is Java's, not Kotlin's")
    @Test
    public void smartCasts() {
        Report report = run("a.b.K", SMART_CASTS);
        io.codelaser.maddi.cst.api.info.MethodInfo checked = parsed.findUniqueMethod("checked", 1);
        io.codelaser.maddi.cst.api.statement.Statement ret = checked.methodBody().statements().getLast();
        assertEquals(true, report.useSites().nonNullAt(ret, checked.parameters().getFirst()));
        assertEquals(true, report.smartCasts().nonNullAt(ret, checked.parameters().getFirst()));
        io.codelaser.maddi.cst.api.info.MethodInfo required = parsed.findUniqueMethod("required", 1);
        io.codelaser.maddi.cst.api.statement.Statement ret2 = required.methodBody().statements().getLast();
        ParameterInfo r = required.parameters().getFirst();
        // the JDK hint: requireNonNull's parameter demands non-null, so Java knows; Kotlin does not smart-cast
        assertEquals(true, report.useSites().nonNullAt(ret2, r));
        assertEquals(false, report.smartCasts().nonNullAt(ret2, r));
    }

    @Language("java")
    private static final String PROPERTY_SMART_CASTS = """
            package a.b;
            class SP {
                static class Cl {
                    final Cl superClass;
                    Cl next;
                    final String name = "n";
                    Cl(Cl superClass) { this.superClass = superClass; }
                }
                int depth(Cl cl) {
                    if (cl.superClass == null) return 0;
                    return cl.superClass.name.length();
                }
                int moved(Cl cl, Cl other) {
                    if (cl.superClass == null) return 0;
                    cl = other;
                    return cl.superClass.name.length();
                }
                int mutable(Cl cl) {
                    if (cl.next == null) return 0;
                    return cl.next.name.length();
                }
            }
            """;

    @DisplayName("smart casts: a captured local is not smart-cast by a correlated check or by requireNonNull's own argument")
    @Test
    public void correlatedCapturedLocal() {
        Report report = run("a.b.TF", """
                package a.b;
                import java.util.Objects;
                import java.util.concurrent.atomic.AtomicLong;
                class TF {
                    interface Factory { String make(Runnable r); }
                    static Factory build(String format) {
                        String nameFormat = format;
                        AtomicLong count = (nameFormat != null) ? new AtomicLong(0) : null;
                        return new Factory() {
                            public String make(Runnable r) {
                                String name = "t";
                                if (nameFormat != null) {
                                    name = String.format(nameFormat, Objects.requireNonNull(count).getAndIncrement());
                                }
                                return name;
                            }
                        };
                    }
                }
                """, Policy.KOTLIN);
        MethodInfo build = parsed.findUniqueMethod("build", 1);
        MethodInfo[] make = {null};
        build.methodBody().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.ConstructorCall cc && cc.anonymousClass() != null) {
                make[0] = cc.anonymousClass().findUniqueMethod("make", 1);
            }
            return true;
        });
        io.codelaser.maddi.cst.api.statement.Statement ifStatement = make[0].methodBody().statements().get(1);
        io.codelaser.maddi.cst.api.statement.Statement assignment = ifStatement.block().statements().getFirst();
        io.codelaser.maddi.cst.api.variable.Variable[] count = {null};
        List<io.codelaser.maddi.cst.api.expression.Expression> calls = new java.util.ArrayList<>();
        assignment.expression().visit(e -> {
            if (e instanceof VariableExpression ve && "count".equals(ve.variable().simpleName())) count[0] = ve.variable();
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc) calls.add(mc);
            return true;
        });
        StringBuilder sb = new StringBuilder("statement=" + report.smartCasts().nonNullAt(assignment, count[0]));
        for (var call : calls) sb.append("; ").append(call).append("=").append(report.smartCasts().nonNullAt(call, count[0]));
        Map<String, String> l = new java.util.TreeMap<>();
        report.locals().forEach((local, pt) -> l.put(local.methodInfo().name() + "." + local.name(), k(pt)));
        assertEquals("AtomicLong?", l.get("build.count"), l + " " + sb);
        assertEquals(false, report.smartCasts().nonNullAt(assignment, count[0]), sb.toString());
        for (var call : calls) {
            if (call.toString().startsWith("Objects.requireNonNull")) {
                assertEquals(false, report.smartCasts().nonNullAt(call, count[0]), sb.toString());
            }
        }
    }

    @DisplayName("smart casts: a null check inside synchronized does not smart-cast after it, guava's ListenerCallQueue")
    @Test
    public void synchronizedNoSmartCast() {
        Report report = run("a.b.LQ", """
                package a.b;
                import java.util.ArrayDeque;
                import java.util.Queue;
                class LQ {
                    final Queue<Runnable> waitQueue = new ArrayDeque<>();
                    void run() {
                        while (true) {
                            Runnable next;
                            synchronized (this) {
                                next = waitQueue.poll();
                                if (next == null) {
                                    break;
                                }
                            }
                            next.run();
                        }
                    }
                }
                """, Policy.KOTLIN);
        MethodInfo run = parsed.findUniqueMethod("run", 0);
        io.codelaser.maddi.cst.api.statement.Statement loop = run.methodBody().statements().getFirst();
        io.codelaser.maddi.cst.api.statement.Statement call = loop.block().statements().getLast();
        io.codelaser.maddi.cst.api.variable.Variable[] next = {null};
        call.expression().visit(e -> {
            if (e instanceof VariableExpression ve && "next".equals(ve.variable().simpleName())) next[0] = ve.variable();
            return true;
        });
        assertEquals(false, report.smartCasts().nonNullAt(call, next[0]));
        assertEquals(true, report.useSites().nonNullAt(call, next[0]));
    }

    @DisplayName("smart casts: a dereference in super(...) does not smart-cast the constructor body, guava's FinalizableWeakReference")
    @Test
    public void superCallArgumentNoSmartCast() {
        Report report = run("a.b.FW", """
                package a.b;
                class FW {
                    static class Queue { final Object queue = new Object(); void cleanUp() { } }
                    static class Base { Base(Object referent, Object queue) { } }
                    static class Ref extends Base {
                        Ref(Object referent, Queue queue) {
                            super(referent, queue.queue);
                            queue.cleanUp();
                        }
                    }
                    static Ref make() { return new Ref("x", null); }
                }
                """, Policy.KOTLIN);
        MethodInfo[] ctor = {null};
        parsed.subTypes().forEach(st -> {
            if ("Ref".equals(st.simpleName())) ctor[0] = st.findConstructor(2);
        });
        io.codelaser.maddi.cst.api.statement.Statement cleanUp = ctor[0].methodBody().statements().get(1);
        ParameterInfo queue = ctor[0].parameters().get(1);
        assertEquals(false, report.smartCasts().nonNullAt(cleanUp, queue));
        assertEquals(true, report.useSites().nonNullAt(cleanUp, queue));
    }

    @DisplayName("smart casts: a final field of a parameter or local is a val property Kotlin smart-casts")
    @Test
    public void propertySmartCasts() {
        Report report = run("a.b.SP", PROPERTY_SMART_CASTS, Policy.KOTLIN);
        assertEquals(true, smartCastAtReturn(report, "depth", 1, "superClass"), "'cl.superClass' after the check");
        assertEquals(false, smartCastAtReturn(report, "moved", 2, "superClass"), "'cl' was assigned in between");
        assertEquals(false, smartCastAtReturn(report, "mutable", 1, "next"), "a var property is not smart-cast");
    }

    private boolean smartCastAtReturn(Report report, String method, int n, String field) {
        MethodInfo mi = parsed.findUniqueMethod(method, n);
        io.codelaser.maddi.cst.api.statement.Statement ret = mi.methodBody().statements().getLast();
        io.codelaser.maddi.cst.api.variable.Variable[] found = {null};
        ret.expression().visit(e -> {
            if (found[0] == null && e instanceof VariableExpression ve
                && ve.variable() instanceof io.codelaser.maddi.cst.api.variable.FieldReference fr
                && field.equals(fr.fieldInfo().name())) found[0] = fr;
            return true;
        });
        return report.smartCasts().nonNullAt(ret, found[0]);
    }

    @Language("java")
    private static final String ANNOTATION_PRESENT = """
            package a.b;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.reflect.Method;
            class AP {
                @Retention(RetentionPolicy.RUNTIME)
                @interface Secured { String action(); }
                static String enableAuth(Secured secured) { return secured.action(); }
                static String unchecked(Secured secured) { return secured.action(); }
                static String reassigned(Secured secured) { return secured.action(); }
                static String checked(Method method) {
                    if (method.isAnnotationPresent(Secured.class)) return enableAuth(method.getAnnotation(Secured.class));
                    return "";
                }
                static String plain(Method method) { return unchecked(method.getAnnotation(Secured.class)); }
                static String moved(Method method, Method other) {
                    if (!method.isAnnotationPresent(Secured.class)) return "";
                    method = other;
                    return reassigned(method.getAnnotation(Secured.class));
                }
            }
            """;

    @Language("java")
    private static final String READ_LOOP = """
            package a.b;
            import java.io.BufferedReader;
            import java.io.IOException;
            class RL {
                static int count(BufferedReader reader) throws IOException {
                    int n = 0;
                    String line;
                    while ((line = reader.readLine()) != null) {
                        n += line.length();
                    }
                    return n;
                }
            }
            """;

    @DisplayName("isAnnotationPresent(X.class) makes getAnnotation(X.class) on the same element non-null (diagnose, nacos)")
    @Test
    public void annotationPresent() {
        Report report = run("a.b.AP", ANNOTATION_PRESENT);
        System.out.println(explain(report));
        Map<String, String> byName = byName(report);
        assertEquals("Secured?", byName.get("a.b.AP.unchecked(a.b.AP.Secured):0:secured"),
                "the JDK's getAnnotation may return null: the test needs that hint");
        assertEquals("Secured", byName.get("a.b.AP.enableAuth(a.b.AP.Secured):0:secured"),
                "nacos AbstractProtocolAuthService.enableAuth: checked by the caller");
        assertEquals("Secured?", byName.get("a.b.AP.reassigned(a.b.AP.Secured):0:secured"), "the element was reassigned");
    }

    @DisplayName("a read loop: '(line = reader.readLine()) != null' makes line non-null in the body (diagnose)")
    @Test
    public void readLoop() {
        Report report = run("a.b.RL", READ_LOOP);
        MethodInfo count = parsed.findUniqueMethod("count", 1);
        io.codelaser.maddi.cst.api.statement.WhileStatement loop = (io.codelaser.maddi.cst.api.statement.WhileStatement)
                count.methodBody().statements().get(2);
        io.codelaser.maddi.cst.api.statement.Statement body = loop.block().statements().getFirst();
        io.codelaser.maddi.cst.api.variable.Variable line = ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation)
                count.methodBody().statements().get(1)).localVariable();
        assertEquals(true, report.useSites().nonNullAt(body, line), "inside the loop");
        assertEquals(false, report.smartCasts().nonNullAt(body, line), "not a Kotlin smart cast");
    }

    @Language("java")
    private static final String IN_STATEMENT = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            class S {
                private final Map<String, String> map = new HashMap<>();
                private String lazy;
                private final String fin;
                S(String fin) { this.fin = fin; }
                boolean both(String k) { String v = map.get(k); return v != null && accept(v); }
                boolean accept(String a) { return a.isEmpty(); }
                int either(String k) { String w = map.get(k); return w == null ? 0 : measure(w); }
                int measure(String m) { return m.length(); }
                String getLazy() {
                    if (lazy == null) {
                        lazy = "computed";
                    }
                    return lazy;
                }
                String afterCall() {
                    if (lazy == null) return "none";
                    accept("x");
                    return lazy;
                }
                private String cached;
                String create() { return "fresh"; }
                String cached() { String result = cached; return result == null ? cached = create() : result; }
            }
            """;

    @DisplayName("inside a statement (&&, ?:) and fields of this (forgotten at a call)")
    @Test
    public void inStatementAndFields() {
        Report report = run("a.b.S", IN_STATEMENT);
        System.out.println(explain(report));
        Map<String, String> byLabel = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> label(e.getKey()), e -> k(e.getValue())));
        assertEquals("String", byLabel.get("accept(0:a)"), "'v != null && accept(v)'");
        assertEquals("String", byLabel.get("measure(0:m)"), "'w == null ? 0 : measure(w)'");
        assertEquals("String?", byLabel.get("lazy"));
        assertEquals("String", byLabel.get("getLazy()"), "the field is non-null after 'if (lazy == null) lazy = ...'");
        assertEquals("String?", byLabel.get("afterCall()"), "a call in between may have reset the field");
        // round 2: create() was found non-null in round 1, so 'cached = create()' assigns a non-null value
        assertEquals("String", byLabel.get("cached()"), "the lazy getter with a factory method");
        assertEquals("String?", byLabel.get("cached"));
        // the printer's view: in 'v != null && accept(v)', the call has v; Kotlin smart-casts it too
        MethodInfo both = parsed.findUniqueMethod("both", 1);
        io.codelaser.maddi.cst.api.statement.Statement ret = both.methodBody().statements().getLast();
        java.util.List<io.codelaser.maddi.cst.api.expression.MethodCall> calls = new java.util.ArrayList<>();
        ret.expression().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc
                && "accept".equals(mc.methodInfo().name())) calls.add(mc);
            return true;
        });
        io.codelaser.maddi.cst.api.expression.MethodCall accept = calls.getFirst();
        io.codelaser.maddi.cst.api.variable.Variable v = ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation)
                both.methodBody().statements().getFirst()).localVariable();
        assertEquals(true, report.smartCasts().nonNullAt(accept, v));
    }

    @DisplayName("use sites: a loop condition sees what the loop assigns; an old-style case does not see the one before")
    @Test
    public void loopsAndCases() {
        Report report = run("a.b.N", """
                package a.b;
                class N {
                    N parent;
                    String name = "";
                    static String top(N n) {
                        n.name.length();
                        while (n.parent != null) { n = n.parent; }
                        return n.name;
                    }
                    static int cases(int k, String s) {
                        switch (k) {
                            case 0: s.length(); break;
                            case 2: return s.length();
                        }
                        return 0;
                    }
                    static int arms(int k, String s) {
                        return switch (k) { case 0 -> s.length(); case 1 -> s.hashCode(); default -> 0; };
                    }
                }
                """);
        TypeInfo n = parsed;
        MethodInfo top = n.findUniqueMethod("top", 1);
        ParameterInfo np = top.parameters().getFirst();
        io.codelaser.maddi.cst.api.statement.Statement loop = top.methodBody().statements().get(1);
        assertEquals(false, report.useSites().nonNullAt(loop, np), "the loop assigns n: not known at its condition");
        MethodInfo cases = n.findUniqueMethod("cases", 2);
        ParameterInfo s = cases.parameters().get(1);
        io.codelaser.maddi.cst.api.statement.Statement sw = cases.methodBody().statements().getFirst();
        io.codelaser.maddi.cst.api.statement.Statement returnInCase2 = sw.subBlockStream().findFirst().orElseThrow()
                .statements().get(2);
        assertEquals(false, report.useSites().nonNullAt(returnInCase2, s), "case 2 is not reached through case 0");
        MethodInfo arms = n.findUniqueMethod("arms", 2);
        ParameterInfo armsS = arms.parameters().get(1);
        List<io.codelaser.maddi.cst.api.expression.MethodCall> calls = new java.util.ArrayList<>();
        arms.methodBody().statements().getFirst().expression().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc) calls.add(mc);
            return true;
        });
        assertEquals("hashCode", calls.get(1).methodInfo().name());
        assertEquals(false, report.smartCasts().nonNullAt(calls.get(1), armsS), "an arm does not see the one before");
    }

    @DisplayName("guards: a null-checked conditional with a call branch; a local passed to a constructor once assigned")
    @Test
    public void guards() {
        Report report = run("a.b.G", """
                package a.b;
                import java.util.*;
                class G {
                    static class Holder {
                        private final List<String> items;
                        Holder(List<String> items) { this.items = items == null ? Collections.emptyList() : items; }
                    }
                    static class Box {
                        private final Holder holder;
                        Box(Holder holder) { this.holder = holder; }
                    }
                    static Holder make(boolean b) {
                        if (b) return new Holder(null);
                        return new Holder(new ArrayList<>());
                    }
                    static Box box(boolean b) {
                        Holder h = null;
                        Box box = null;
                        if (b) {
                            h = make(b);
                            box = new Box(h);
                        }
                        return box;
                    }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("List<String>", byName.get("a.b.G.Holder.items"));
        assertEquals("Holder", byName.get("a.b.G.Box.holder"));
    }

    @DisplayName("a map lookup whose key is known present is not the absent key's null (issue #22 gap 1)")
    @Test
    public void keyPresence() {
        Report report = run("a.b.K", """
                package a.b;
                import java.util.*;
                class K {
                    String guarded(Map<String, String> m, String k) { if (m.containsKey(k)) { return m.get(k); } return ""; }
                    String early(Map<String, String> m, String k) { if (!m.containsKey(k)) return ""; return m.get(k); }
                    String unguarded(Map<String, String> m, String k) { return m.get(k); }
                    String afterPut(Map<String, String> m, String k) { m.put(k, "v"); return m.get(k); }
                    String afterPutNull(Map<String, String> m, String k) { m.put(k, null); return m.get(k); }
                    String loop(Map<String, String> m) { for (String k : m.keySet()) { return m.get(k); } return ""; }
                    String cleared(Map<String, String> m, String k) { if (m.containsKey(k)) { m.clear(); return m.get(k); } return ""; }
                    String otherCall(Map<String, String> m, String k, List<String> l) { if (m.containsKey(k)) { l.add(k); return m.get(k); } return ""; }
                    String reassigned(Map<String, String> m, String k) { if (m.containsKey(k)) { k = k + "x"; return m.get(k); } return ""; }
                    String passed(Map<String, String> m, String k) { if (m.containsKey(k)) { use(m); return m.get(k); } return ""; }
                    static void use(Map<String, String> m) { m.clear(); }
                    String peeked(Map<String, String> m, String k) { if (m.containsKey(k)) { peek(m); return m.get(k); } return ""; }
                    static int peek(Map<String, String> m) { return m.size(); }
                    String removedAll(Map<String, String> m, String k) { if (m.containsKey(k)) { removeAllKeys(m, List.of(k)); return m.get(k); } return ""; }
                    static void removeAllKeys(Map<String, String> map, Collection<?> keys) {
                        Iterator<Map.Entry<String, String>> it = map.entrySet().iterator();
                        while (it.hasNext()) { if (keys.contains(it.next().getKey())) it.remove(); }
                    }
                    String removed(Map<String, String> m, String k) { if (m.containsKey(k)) { return m.remove(k); } return ""; }
                    int length(Map<String, String> m, String k) { if (m.containsKey(k)) { return m.get(k).length(); } return 0; }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String", byName.get("a.b.K.guarded(java.util.Map,String)"));
        assertEquals("String", byName.get("a.b.K.early(java.util.Map,String)"));
        assertEquals("String?", byName.get("a.b.K.unguarded(java.util.Map,String)"));
        assertEquals("String", byName.get("a.b.K.afterPut(java.util.Map,String)"));
        assertEquals("String?", byName.get("a.b.K.afterPutNull(java.util.Map,String)"), "the value put is null");
        assertEquals("String", byName.get("a.b.K.loop(java.util.Map)"));
        assertEquals("String?", byName.get("a.b.K.cleared(java.util.Map,String)"), "a call on the map");
        assertEquals("String", byName.get("a.b.K.otherCall(java.util.Map,String,java.util.List)"));
        assertEquals("String?", byName.get("a.b.K.reassigned(java.util.Map,String)"), "the key is assigned");
        assertEquals("String?", byName.get("a.b.K.passed(java.util.Map,String)"), "the map is handed to a call");
        assertEquals("String", byName.get("a.b.K.peeked(java.util.Map,String)"), "handed to a call that does not modify it");
        // CodeLaser/maddi-mod#54: removal through the entry set's iterator modifies the parameter
        assertEquals("String?", byName.get("a.b.K.removedAll(java.util.Map,String)"), "removal through a view");
        assertEquals("String", byName.get("a.b.K.removed(java.util.Map,String)"));
    }

    // CodeLaser/maddi-mod#29 C (timefold TreeMultiSet): a key the navigable map hands out is one of its keys
    @DisplayName("key presence: a key from the map's own navigation methods (higherKey, firstKey, navigableKeySet)")
    @Test
    public void keyPresenceNavigated() {
        Report report = run("a.b.KN", """
                package a.b;
                import java.util.*;
                class KN {
                    private final TreeMap<String, Integer> backing = new TreeMap<>();
                    void add(String k) { backing.merge(k, 1, Integer::sum); }
                    int first() {
                        String k = backing.isEmpty() ? null : backing.firstKey();
                        return k != null ? backing.get(k) : 0;
                    }
                    int next(String current) {
                        String k = backing.higherKey(current);
                        if (k == null) throw new NoSuchElementException();
                        return backing.get(k) - 1;
                    }
                    int other(TreeMap<String, Integer> elsewhere, String current) {
                        String k = elsewhere.higherKey(current);
                        if (k == null) throw new NoSuchElementException();
                        return backing.get(k) - 1;
                    }
                    int view() { int s = 0; for (String k : backing.descendingKeySet()) s += backing.get(k); return s; }
                    Iterator<String> iterator() {
                        return new Iterator<>() {
                            String currentKey = backing.isEmpty() ? null : backing.firstKey();
                            int remaining = currentKey != null ? backing.get(currentKey) : 0;
                            public boolean hasNext() { return remaining > 0 || backing.higherKey(currentKey) != null; }
                            public String next() {
                                if (remaining > 0) { remaining--; return currentKey; }
                                currentKey = backing.higherKey(currentKey);
                                if (currentKey == null) throw new NoSuchElementException();
                                remaining = backing.get(currentKey) - 1;
                                return currentKey;
                            }
                        };
                    }
                }
                """, Policy.NULL_MARKED_FLOW_ONLY.withoutContracts().withWorld(World.CLOSED));
        System.out.println(explain(report));
        Map<String, Boolean> present = new java.util.TreeMap<>();
        List<MethodInfo> methods = new java.util.ArrayList<>(parsed.methods());
        parsed.findUniqueMethod("iterator", 0).methodBody().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.ConstructorCall cc && cc.anonymousClass() != null) {
                methods.addAll(cc.anonymousClass().methods());
            }
            return true;
        });
        methods.forEach(m -> {
            if (m.methodBody() == null) return;
            m.methodBody().visit(e -> {
                if (e instanceof MethodCall mc && mc.methodInfo() != null && "get".equals(mc.methodInfo().name())) {
                    present.merge((m.typeInfo().isAnonymous() ? "anonymous." : "") + m.name(),
                            report.useSites().keyPresentWhenCalled(mc), Boolean::logicalAnd);
                }
                return true;
            });
        });
        assertEquals("{anonymous.next=true, first=true, next=true, other=false, view=true}", present.toString());
    }

    // CodeLaser/maddi-mod#48 A4 (nacos IstioCrdUtil) and B6 (nacos ClientBasicParamUtil)
    @DisplayName("key presence: the key of an entry of the same map, and a put of a defaulted Properties lookup")
    @Test
    public void keyPresenceEntriesAndDefaults() {
        Report report = run("a.b.KE", """
                package a.b;
                import java.util.*;
                class KE {
                    int entries(Map<String, Integer> m) {
                        int s = 0;
                        for (Map.Entry<String, Integer> e : m.entrySet()) s += m.get(e.getKey());
                        return s;
                    }
                    int otherMap(Map<String, Integer> a, Map<String, Integer> b) {
                        int s = 0;
                        for (Map.Entry<String, Integer> e : a.entrySet()) s += b.get(e.getKey());
                        return s;
                    }
                    int entryAsKey(Map<Object, Integer> m) {
                        int s = 0;
                        for (Map.Entry<Object, Integer> e : m.entrySet()) s += m.get(e);
                        return s;
                    }
                    String defaulted(Map<String, String> m, Properties p, String k) {
                        m.put(k, p.getProperty(k, ""));
                        return m.get(k);
                    }
                    String system(Map<String, String> m, String k) {
                        m.put(k, System.getProperty(k, "none"));
                        return m.get(k);
                    }
                    String notDefaulted(Map<String, String> m, Properties p, String k) {
                        m.put(k, p.getProperty(k));
                        return m.get(k);
                    }
                }
                """, Policy.NULL_MARKED_FLOW_ONLY.withoutContracts().withWorld(World.CLOSED));
        Map<String, Boolean> present = new java.util.TreeMap<>();
        parsed.methods().forEach(m -> m.methodBody().visit(e -> {
            if (e instanceof MethodCall mc && mc.methodInfo() != null && "get".equals(mc.methodInfo().name())) {
                present.merge(m.name(), report.useSites().keyPresentWhenCalled(mc), Boolean::logicalAnd);
            }
            return true;
        }));
        assertEquals("{defaulted=true, entries=true, entryAsKey=false, notDefaulted=false, otherMap=false, system=true}",
                present.toString());
    }

    /*
     CodeLaser/maddi-mod#48 B6, nacos HttpLoginProcessor: 'bodyMap.put(PASSWORD, properties.getProperty(PASSWORD, ""));
     try { postForm(url, ..., bodyMap, ...); ... } catch (Exception e) { desensitise(bodyMap.get(PASSWORD)); }'. A
     call handed the map to a parameter the modification analysis finds unmodified cannot remove the key. One that
     modifies it can, in the try as on the straight path.
     */
    @DisplayName("key presence: a call handed the map to an unmodified parameter keeps it, in a try's catch too")
    @Test
    public void keyPresenceAcrossUnmodifiedCall() {
        Map<String, String> s = new java.util.TreeMap<>();
        run("a.b.KU", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Properties;
                class KU {
                    static final String PASSWORD = "password";
                    static int post(Map<String, String> body) { return body.size(); }
                    static void clear(Map<String, String> body) { body.clear(); }
                    static int kept(String s) { return s.length(); }
                    static int caught(String s) { return s.length(); }
                    static int cleared(String s) { return s == null ? 0 : s.length(); }
                    static int clearedInTry(String s) { return s == null ? 0 : s.length(); }
                    static int login(Properties p) {
                        Map<String, String> body = new HashMap<>();
                        body.put(PASSWORD, p.getProperty(PASSWORD, ""));
                        post(body);
                        int n = kept(body.get(PASSWORD));
                        try {
                            n += post(body);
                        } catch (RuntimeException e) {
                            n += caught(body.get(PASSWORD));
                        }
                        return n;
                    }
                    static int direct(String s) { return s.length(); }
                    static int directKey(String s) { return s.length(); }
                    static int noCall(Properties p, String k) {
                        Map<String, String> body = new HashMap<>();
                        body.put(PASSWORD, p.getProperty(PASSWORD, ""));
                        Map<String, String> other = new HashMap<>();
                        other.put(k, p.getProperty(k, ""));
                        return direct(body.get(PASSWORD)) + directKey(other.get(k));
                    }
                    static int logout(Properties p) {
                        Map<String, String> body = new HashMap<>();
                        body.put(PASSWORD, p.getProperty(PASSWORD, ""));
                        clear(body);
                        int n = cleared(body.get(PASSWORD));
                        body.put(PASSWORD, "x");
                        try {
                            clear(body);
                        } catch (RuntimeException e) {
                            n += clearedInTry(body.get(PASSWORD));
                        }
                        return n;
                    }
                }
                """, Policy.NULL_MARKED_FLOW_ONLY.withoutContracts()
                        .withWorld(World.CLOSED)).verdicts()
                .forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("String", s.get("direct(0:s)"), s.toString());
        assertEquals("String", s.get("directKey(0:s)"), s.toString());
        assertEquals("String", s.get("kept(0:s)"), s.toString());
        assertEquals("String", s.get("caught(0:s)"), s.toString());
        assertEquals("String?", s.get("cleared(0:s)"), s.toString());
        assertEquals("String?", s.get("clearedInTry(0:s)"), "clear(body) in the try: " + s);
    }

    @DisplayName("a java.util lookup's Object argument is not known non-null after the call, for Kotlin")
    @Test
    public void lookupArguments() {
        Report report = run("a.b.L2", """
                package a.b;
                import java.util.*;
                class L2 {
                    static class Block { List<Block> preds = new ArrayList<>(); }
                    static int count(Set<Block> blocks, List<Block> lst) {
                        int n = 0;
                        for (int i = 0; i < lst.size(); i++) {
                            Block child = lst.get(i);
                            if (!blocks.contains(child)) {
                                n += child.preds.size();
                            }
                        }
                        return n;
                    }
                }
                """, Policy.KOTLIN);
        MethodInfo count = parsed.findUniqueMethod("count", 2);
        List<io.codelaser.maddi.cst.api.expression.VariableExpression> derefs = new java.util.ArrayList<>();
        List<Statement> inner = new java.util.ArrayList<>();
        count.methodBody().visit(e -> {
            if (e instanceof IfElseStatement ifElse) inner.add(ifElse.block().statements().getFirst());
            return true;
        });
        Statement use = inner.getFirst();
        io.codelaser.maddi.cst.api.variable.Variable child = report.smartCasts().before(use).stream()
                .filter(v -> v.simpleName().equals("child")).findFirst().orElse(null);
        System.out.println("SMART " + report.smartCasts().before(use) + " JAVA " + report.useSites().before(use));
        assertEquals(null, child, "contains(Object) accepts null: Kotlin knows nothing about child");
    }

    @DisplayName("a try-with-resources resource is a declaration: its initializer's null reaches what it is passed to")
    @Test
    public void tryResources() {
        List<Policy> policies = List.of(Policy.NULL_MARKED, Policy.KOTLIN);
        for (int p = 0; p < policies.size(); p++) {
            Policy policy = policies.get(p);
            String name = "W" + p; // one parse per type and test
            Report report = run("a.b." + name, """
                    package a.b;
                    import java.io.*;
                    import java.util.*;
                    class %s {
                        private final Map<String, byte[]> links = new HashMap<>();
                        InputStream open(String name) {
                            byte[] data = links.get(name);
                            return data == null ? null : new ByteArrayInputStream(data);
                        }
                        static int create(InputStream in) throws IOException { return in.read(); }
                        int reload(String name) throws IOException {
                            try (InputStream in = open(name)) {
                                return create(in);
                            }
                        }
                    }
                    """.formatted(name), policy);
            Map<String, String> byName = report.verdicts().entrySet().stream()
                    .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
            assertEquals("InputStream?", byName.get("a.b." + name + ".open(String)"), policy.toString());
            assertEquals("InputStream?", byName.get("a.b." + name + ".create(java.io.InputStream):0:in"),
                    "fernflower ContextUnit.reload, " + policy);
        }
    }

    @DisplayName("a null-checked array element (constant index) guards the argument it is passed as")
    @Test
    public void checkedElements() {
        Report report = run("a.b.E2", """
                package a.b;
                import java.util.*;
                class E2 {
                    static class Op { final List<String> operands = new ArrayList<>(); Op(String o) { operands.add(o); } }
                    static Object[] find(boolean b) { Object[] r = new Object[2]; if (b) r[0] = "x"; return r; }
                    static Op guarded(boolean b) {
                        Object[] res = find(b);
                        if (res[0] != null) return new Op((String) res[0]);
                        return null;
                    }
                    static Op afterCall(boolean b) {
                        Object[] res = find(b);
                        if (res[0] != null) { find(!b); return new Op2((String) res[0]).op; }
                        return null;
                    }
                    static class Op2 { final Op op; Op2(String s) { op = new Op("y"); take(s); } }
                    static void take(String s) { }
                }
                """, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String", byName.get("a.b.E2.Op.<init>(String):0:o"), "fernflower AssertProcessor:186");
        assertEquals("List<String>", byName.get("a.b.E2.Op.operands"));
        assertEquals("String?", byName.get("a.b.E2.Op2.<init>(String):0:s"), "a call in between may write the array");
    }

    @DisplayName("statements in a switch expression's block arms are statements: their seeds, links and fills")
    @Test
    public void switchExpressionArms() {
        Report report = run("a.b.SA", """
                package a.b;
                class SA {
                    static String[] arm(int k, int n) {
                        return switch (k) {
                            case 0 -> {
                                String[] out = new String[n];
                                for (int i = 0; i < n; i++) out[i] = "x";
                                yield out;
                            }
                            default -> new String[0];
                        };
                    }
                    static void hole(int k) {
                        String r = switch (k) {
                            case 0 -> {
                                String s = null;
                                yield s;
                            }
                            default -> "x";
                        };
                        take(r);
                    }
                    static void take(String s) { }
                }
                """);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("String[]", byName.get("a.b.SA.arm(int,int)"), "fernflower StructTypeAnnotationAttribute:52");
        assertEquals("String?", byName.get("a.b.SA.take(String):0:s"), "a null in a block arm");

        Report report2 = run("a.b.SB", """
                package a.b;
                class SB {
                    interface Info {
                        class Local implements Info {
                            private final Offsets[] table;
                            public Local(Offsets[] table) { this.table = table; }
                            public Offsets[] getTable() { return table; }
                            public static class Offsets {
                                private final int at;
                                public Offsets(int at) { this.at = at; }
                            }
                        }
                        class Empty implements Info { }
                    }
                    static Info parse(int k, int n) {
                        Info info = switch (k) {
                            case 0 -> new Info.Empty();
                            case 1 -> {
                                Info.Local.Offsets[] offsets = new Info.Local.Offsets[n];
                                for (int i = 0; i < n; i++) {
                                    offsets[i] = new Info.Local.Offsets(i);
                                }
                                yield new Info.Local(offsets);
                            }
                            default -> throw new RuntimeException("k " + k);
                        };
                        return info;
                    }
                }
                """);
        System.out.println(explain(report2));
        Map<String, String> byName2 = report2.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("Offsets[]", byName2.get("a.b.SB.Info.Local.table"), "fernflower TargetInfo.LocalvarTarget.table");
        // locals of a block arm have their own verdict: the printer declared 'offsets' as Array<Offsets?> without one
        assertEquals("""
                parse.i: int
                parse.info: Info
                parse.offsets: Offsets[]""", locals(report2));
        assertEquals("""
                arm.i: int
                arm.out: String[]
                hole.r: String?
                hole.s: String?""", locals(report));
    }

    @Language("java")
    private static final String LOOKUP = """
            package a.b;
            import java.util.*;
            class LK {
                static class Keyed<E> {
                    private final List<E> items = new ArrayList<>();
                    private final List<Integer> keys = new ArrayList<>();
                    E getWithKey(int key) {
                        int index = keys.indexOf(key);
                        if (index < 0) return null;
                        return items.get(index);
                    }
                    void addWithKey(E e, int key) { items.add(e); keys.add(key); }
                    E get(int i) { return items.get(i); }
                    int size() { return items.size(); }
                }
                static class Stmt {
                    int id;
                    Stmt first;
                    final Keyed<Stmt> stats = new Keyed<>();
                    Stmt() { }
                    Stmt(Stmt head) {
                        first = head;
                        stats.addWithKey(head, head.id);
                    }
                    Stmt(Stmt head, int kind) {
                        first = head;
                        stats.addWithKey(first, first.id);
                    }
                    void initSimpleCopy() {
                        if (stats.size() > 0) first = stats.get(0);
                    }
                    void replaceStatement(Stmt oldstat, Stmt newstat) {
                        stats.addWithKey(newstat, newstat.id);
                        if (first == oldstat) first = newstat;
                    }
                }
                static Stmt general(Stmt root, int headId) {
                    Stmt head = root.stats.getWithKey(headId);
                    return new Stmt(head);
                }
                static Stmt root(Stmt root, int headId) {
                    return new Stmt(root.stats.getWithKey(headId), 1);
                }
            }
            """;

    @DisplayName("'stats.addWithKey(first, first.id)': a later argument dereferences a field of this before the write")
    @Test
    public void dereferencedFieldOfThis() {
        Report report = run("a.b.LK", LOOKUP, Policy.KOTLIN);
        System.out.println(explain(report));
        Map<String, String> byName = report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("E?", byName.get("a.b.LK.Keyed.getWithKey(int)"));
        assertEquals("Stmt?", byName.get("a.b.LK.Stmt.first"), "the value itself is still nullable");
        assertEquals("Keyed<Stmt>", byName.get("a.b.LK.Stmt.stats"), "fernflower RootStatement(head, dummyExit)");

        Report java = run("a.b.LK2", LOOKUP.replace("class LK ", "class LK2 "));
        Map<String, String> javaByName = java.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
        assertEquals("E?", javaByName.get("a.b.LK2.Keyed.getWithKey(int)"));
    }

    // langchain4j ValidationUtils: 'if (i == null || i < 0) throw illegalArgument("...%s", name, i)' hands the
    // vararg a value that is null on that branch. 'i' is nullable only through the comparison (indirect evidence),
    // which under assertContentWrites does not reach a slot; but a vararg's elements are not stored content: the
    // call builds the array, and asserting at the call ('i!!') would throw where Java formats "null"
    @DisplayName("a value null on the throwing branch passed as a vararg element")
    @Test
    public void varargElementAfterNullTest() {
        Report report = run("a.b.VA", """
                package a.b;
                class VA {
                    static IllegalArgumentException illegalArgument(String format, Object... args) {
                        return new IllegalArgumentException(String.format(format, args));
                    }
                    static RuntimeException runtime(String format, Object... args) {
                        return new RuntimeException(String.format(format, args));
                    }
                    static int ensureNotNegative(Integer i, String name) {
                        if (i == null || i < 0) throw illegalArgument("%s must not be negative, but is: %s", name, i);
                        return i;
                    }
                    static Object plain(Integer j) { return runtime("%s", j); }
                    static void caller() { plain(null); }
                }
                """, Policy.KOTLIN);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Integer?", s.get("ensureNotNegative(0:i)"), s.toString());
        assertEquals("Object?[]", s.get("illegalArgument(1:args)"), s.toString());
        assertEquals("Object?[]", s.get("runtime(1:args)"), "a null argument, as before: " + s);
    }

    // CodeLaser/maddi-mod#22 gap 8 (nacos ConfigCacheService): the same non-modifying call, after its own null test
    @DisplayName("gap 8: a repeated pure call after its own null check is not the library's null")
    @Test
    public void repeatedPureCall() {
        Report report = run("a.b.G8", """
                package a.b;
                import java.io.IOException;
                class G8 {
                    static boolean checked(IOException ioe) {
                        if (ioe.getMessage() != null) {
                            String errMsg = ioe.getMessage();
                            return errMsg.contains("x");
                        }
                        return false;
                    }
                    static boolean unchecked(IOException ioe) {
                        String errMsg = ioe.getMessage();
                        return errMsg != null && errMsg.isEmpty();
                    }
                    static boolean changed(IOException ioe) {
                        if (ioe.getMessage() != null) {
                            ioe.initCause(new RuntimeException());
                            String errMsg = ioe.getMessage();
                            return errMsg != null;
                        }
                        return false;
                    }
                }
                """);
        String l = locals(report);
        assertTrue(l.contains("checked.errMsg: String\n"), l);
        assertTrue(l.contains("unchecked.errMsg: String?"), l);
        assertTrue(l.contains("changed.errMsg: String?"), "initCause modifies the receiver: " + l);
    }

    // CodeLaser/maddi-mod#22 gap 7 (nacos MysqlHealthCheckProcessor): a local initialized null for the finally, assigned
    // a value no null reaches, then dereferenced: known non-null at that use, though its declaration stays nullable
    @DisplayName("gap 7: a local is non-null after an assignment no null reaches, whatever its other assignments")
    @Test
    public void reassignedLocalUseSite() {
        Report report = run("a.b.G7", """
                package a.b;
                class G7 {
                    interface Stmt { void timeout(int t); void close(); }
                    interface Conn { Stmt create(); }
                    static Stmt maybe(boolean b) { return b ? null : new Stmt() {
                        public void timeout(int t) { }
                        public void close() { }
                    }; }
                    static void health(Conn connection) {
                        Stmt statement = null;
                        try {
                            statement = connection.create();
                            statement.timeout(1);
                        } finally {
                            if (statement != null) statement.close();
                        }
                    }
                    static void risky(boolean b) {
                        Stmt statement = null;
                        statement = maybe(b);
                        statement.timeout(1);
                    }
                }
                """);
        assertTrue(locals(report).contains("health.statement: Stmt?"), locals(report));
        assertTrue(timeoutCallNonNull(report, "health"), "after 'statement = connection.create()'");
        assertFalse(timeoutCallNonNull(report, "risky"), "maybe(b) may return null");
    }

    private boolean timeoutCallNonNull(Report report, String method) {
        MethodInfo mi = parsed.methods().stream().filter(m -> m.name().equals(method)).findFirst().orElseThrow();
        boolean[] found = {false, false};
        mi.methodBody().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.statement.ExpressionAsStatement eas
                && eas.expression() instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc
                && mc.methodInfo() != null && "timeout".equals(mc.methodInfo().name())
                && mc.object() instanceof io.codelaser.maddi.cst.api.expression.VariableExpression ve) {
                found[0] = true;
                found[1] = report.useSites().nonNullAt(eas, ve.variable());
            }
            return true;
        });
        assertTrue(found[0], "no 'statement.timeout(1)' in " + method);
        return found[1];
    }

    // CodeLaser/maddi-mod#22 gap 1, the residue in nacos' 2026-10-10 sample: the key made present by computeIfAbsent, by a
    // containsKey-or-throw, by a helper that puts it; a ThreadLocal with an initial value
    @DisplayName("gap 1 residue: computeIfAbsent, containsKey-or-throw, a putting helper, ThreadLocal.withInitial")
    @Test
    public void keyPresentResidue() {
        Report report = run("a.b.G1", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                class G1 {
                    private final Map<String, StringBuilder> cache = new HashMap<>();
                    private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);
                    int computed(String k) {
                        cache.computeIfAbsent(k, x -> new StringBuilder());
                        StringBuilder sb = cache.get(k);
                        return sb.length();
                    }
                    int orThrow(String k) {
                        if (!cache.containsKey(k)) throw new IllegalArgumentException(k);
                        StringBuilder sb = cache.get(k);
                        return sb.length();
                    }
                    void ensure(String k) {
                        if (!cache.containsKey(k)) cache.put(k, new StringBuilder());
                    }
                    int helper(String k) {
                        ensure(k);
                        StringBuilder sb = cache.get(k);
                        return sb.length();
                    }
                    int threadLocal() {
                        StringBuilder sb = BUFFER.get();
                        return sb.length();
                    }
                    int absent(String k) {
                        StringBuilder sb = cache.get(k);
                        return sb == null ? 0 : sb.length();
                    }
                }
                """);
        String l = locals(report);
        assertEquals("""
                absent.sb: StringBuilder?
                computed.sb: StringBuilder
                helper.sb: StringBuilder
                orThrow.sb: StringBuilder
                threadLocal.sb: StringBuilder""", l.lines().filter(x -> x.contains(".sb:"))
                .collect(Collectors.joining("\n")), l);
    }

    /*
     Gap 8 for a library outside the JDK, which has no hints: a getter's unannotated default is modifying, so the
     repeated call never had the fact. Spring 'coreContext = null == ctx.getParent() ? ctx : ctx.getParent()' (nacos
     AbstractNacosDuplicateBeanPostProcessor), here on opentest4j's AssertionFailedError.getExpected(), null when no expected value was given. A call that is not a getter still forgets it.
     */
    @DisplayName("gap 8: a library getter repeated after its own null test")
    @Test
    public void libraryGetterRepeated() {
        Report report = run("a.b.LG", """
                package a.b;
                import org.opentest4j.AssertionFailedError;
                import org.opentest4j.ValueWrapper;
                class LG {
                    private ValueWrapper expected;
                    void conditional(AssertionFailedError e, ValueWrapper none) {
                        expected = null == e.getExpected() ? none : e.getExpected();
                    }
                    void interrupted(AssertionFailedError e) {
                        if (e.getExpected() != null) {
                            e.addSuppressed(new RuntimeException());
                            expected = e.getExpected();
                        }
                    }
                }
                """);
        List<io.codelaser.maddi.cst.api.expression.MethodCall> conditional = calls("conditional", "getExpected");
        assertEquals(2, conditional.size());
        assertFalse(report.useSites().pureCallNonNullWhenCalled(conditional.get(0)), "the test itself");
        assertTrue(report.useSites().pureCallNonNullWhenCalled(conditional.get(1)), "the else branch");
        List<io.codelaser.maddi.cst.api.expression.MethodCall> interrupted = calls("interrupted", "getExpected");
        assertEquals(2, interrupted.size());
        assertFalse(report.useSites().pureCallNonNullWhenCalled(interrupted.get(1)), "after a modifying call");
    }

    // nacos Base64.readResults / ensureBufferSize (the diagnose session): a guarded field of this stays non-null
    // across a library call not handed this ('Math.min', 'System.arraycopy') and across an array creation
    @DisplayName("use sites: a call that cannot reach this keeps the facts on its fields")
    @Test
    public void callsThatCannotReachThis() {
        Report report = run("a.b.BB", """
                package a.b;
                import java.util.Objects;
                class BB {
                    private byte[] buffer;
                    private int pos;
                    void copy(byte[] b, int n) {
                        if (buffer != null) {
                            int len = Math.min(pos, n);
                            System.arraycopy(buffer, 0, b, 0, len);
                        }
                    }
                    void grow() {
                        if (buffer == null) {
                            buffer = new byte[8];
                        } else {
                            byte[] b = new byte[buffer.length * 2];
                            System.arraycopy(buffer, 0, b, 0, buffer.length);
                            buffer = b;
                        }
                    }
                    void handed(byte[] b) {
                        if (buffer != null) {
                            int h = Objects.hash(this);
                            System.arraycopy(buffer, 0, b, 0, h);
                        }
                    }
                    void own(byte[] b) {
                        if (buffer != null) {
                            reset();
                            System.arraycopy(buffer, 0, b, 0, 1);
                        }
                    }
                    void reset() { buffer = null; }
                    void plain(byte[] b) {
                        if (buffer != null) {
                            System.arraycopy(buffer, 0, b, 0, 1);
                        }
                    }
                }
                """);
        assertEquals(true, bufferKnownAtArraycopy(report, parsed.findUniqueMethod("plain", 1).methodBody()
                .statements().getFirst().block()), "no call in between");
        assertEquals(true, bufferKnownAtArraycopy(report, parsed.findUniqueMethod("copy", 2).methodBody()
                .statements().getFirst().block()), "after Math.min");
        IfElseStatement grow = (IfElseStatement) parsed.findUniqueMethod("grow", 0).methodBody().statements().getFirst();
        assertEquals(true, bufferKnownAtArraycopy(report, grow.elseBlock()), "after 'new byte[...]'");
        assertEquals(false, bufferKnownAtArraycopy(report, parsed.findUniqueMethod("handed", 1).methodBody()
                .statements().getFirst().block()), "Objects.hash is handed this");
        assertEquals(false, bufferKnownAtArraycopy(report, parsed.findUniqueMethod("own", 1).methodBody()
                .statements().getFirst().block()), "an analysed method may change it");
    }

    // the facts on 'buffer' when the block's 'System.arraycopy(buffer, ...)' starts
    private static boolean bufferKnownAtArraycopy(Report report, Block block) {
        Statement copy = block.statements().stream()
                .filter(st -> st.expression() instanceof MethodCall mc && "arraycopy".equals(mc.methodInfo().name()))
                .findFirst().orElseThrow();
        MethodCall mc = (MethodCall) copy.expression();
        Variable buffer = ((VariableExpression) mc.parameterExpressions().getFirst()).variable();
        return report.useSites().nonNullAt(copy, buffer);
    }

    // nacos Base64.encode (the diagnose session): 'ensureBufferSize(n); buffer[pos++] = b' after a private helper that
    // leaves the field non-null on every normal exit
    @DisplayName("use sites: a field a private helper leaves non-null is non-null after the call")
    @Test
    public void fieldNonNullAtExit() {
        Report report = run("a.b.EB", """
                package a.b;
                class EB {
                    private byte[] buffer;
                    private int pos;
                    private void ensure(int size) {
                        if (buffer == null || buffer.length < pos + size) {
                            if (buffer == null) {
                                buffer = new byte[8];
                            } else {
                                byte[] b = new byte[buffer.length * 2];
                                System.arraycopy(buffer, 0, b, 0, buffer.length);
                                buffer = b;
                            }
                        }
                    }
                    void encode(byte v) {
                        ensure(1);
                        buffer[pos++] = v;
                    }
                    void grow(int size) { if (buffer == null) buffer = new byte[size]; }
                    void open(byte v) {
                        grow(1);
                        buffer[pos++] = v;
                    }
                    void reset() { buffer = null; }
                }
                """);
        FieldInfo buffer = parsed.getFieldByName("buffer", true);
        assertEquals(true, report.useSites().nonNullAtExit(parsed.findUniqueMethod("ensure", 1), buffer));
        assertEquals(false, report.useSites().nonNullAtExit(parsed.findUniqueMethod("reset", 0), buffer));
        assertEquals(true, bufferKnownAtWrite(report, parsed.findUniqueMethod("encode", 1)), "after ensure(1)");
        assertEquals(false, bufferKnownAtWrite(report, parsed.findUniqueMethod("open", 1)), "grow is overridable");
    }

    /*
     CodeLaser/maddi-mod#48 A5, nacos CredentialWatcher: 'loadPropertyPath(init); InputStream is =
     loadPropertyPathToStream();', where the first private helper leaves 'propertyPath' non-null and the second
     dereferences it. A private method's fields of this are non-null at its entry when they are at each of its calls;
     not when one call lacks it, when a method reference, a field initializer or an anonymous class calls it too.
     */
    @DisplayName("use sites: a field non-null at every call of a private method is non-null at its entry")
    @Test
    public void fieldNonNullAtEntry() {
        Report report = run("a.b.FE", """
                package a.b;
                import java.util.function.IntSupplier;
                class FE {
                    private String path;
                    private String other;
                    private final int initial = initialLength();
                    private void loadPath(boolean init) {
                        if (path == null) {
                            path = init ? "a" : "b";
                        }
                    }
                    private int read() { return path.length(); }
                    private int readOther() { return other.length(); }
                    private int referenced() { return path.length(); }
                    private int initialLength() { return path.length(); }
                    private int chained() { return path.length(); }
                    private int viaChain() { return chained(); }
                    private int anonymous() { return path.length(); }
                    private java.io.InputStream looped() {
                        java.io.InputStream is = null;
                        do {
                            try {
                                is = new java.io.FileInputStream(path);
                            } catch (java.io.FileNotFoundException e) {
                                if (path.equals("a")) {
                                    path = "b";
                                    continue;
                                }
                            }
                            break;
                        } while (true);
                        return is;
                    }
                    void load(boolean init) {
                        loadPath(init);
                        int n = read() + viaChain() + initialLength();
                        if (other != null) readOther();
                        readOther();
                        IntSupplier s = this::referenced;
                        n += referenced();
                        n += anonymous();
                        Runnable r = new Runnable() { public void run() { anonymous(); } };
                        loadPath(init);
                        java.io.InputStream is = looped();
                    }
                    void reset() { path = null; }
                }
                """);
        assertTrue(receiverKnownAtReturn(report, parsed.findUniqueMethod("read", 0)), "only called after loadPath");
        assertTrue(receiverKnownAtReturn(report, parsed.findUniqueMethod("chained", 0)), "through viaChain");
        assertFalse(receiverKnownAtReturn(report, parsed.findUniqueMethod("readOther", 0)), "the unguarded call");
        assertFalse(receiverKnownAtReturn(report, parsed.findUniqueMethod("referenced", 0)), "this::referenced");
        assertFalse(receiverKnownAtReturn(report, parsed.findUniqueMethod("initialLength", 0)), "the initializer");
        assertFalse(receiverKnownAtReturn(report, parsed.findUniqueMethod("anonymous", 0)), "the anonymous class");
        io.codelaser.maddi.cst.api.expression.MethodCall equals = calls("looped", "equals").getFirst();
        assertTrue(report.useSites().nonNullAt(equals,
                ((io.codelaser.maddi.cst.api.expression.VariableExpression) equals.object()).variable()),
                "in the catch of the loop, as CredentialWatcher.loadPropertyPathToStream");
        assertTrue(report.useSites().nonNullAtExit(parsed.findUniqueMethod("looped", 0),
                parsed.getFieldByName("path", true)), "the loop assigns it only non-null values");
    }

    /*
     CodeLaser/maddi-mod#48 A3, nacos CredentialService: 'String key = appName != null ? appName : NO_APP_NAME;
     INSTANCES.remove(key)', with 'freeInstance()' passing null. The use site knows key non-null: each branch of the
     ternary is evaluated with its condition's facts. Also 'custom == null ? fallback() : custom'
     (EnvUtil.getApplicationConfFileResource), under the diagnostics' policy.
     */
    @DisplayName("use sites: a ternary that replaces null is non-null by its condition")
    @Test
    public void ternaryReplacingNull() {
        Report report = run("a.b.TR", """
                package a.b;
                import java.io.InputStream;
                import java.util.Map;
                import java.util.concurrent.ConcurrentHashMap;
                class TR {
                    interface Res { InputStream in(); }
                    static class Fixed implements Res { public InputStream in() { return InputStream.nullInputStream(); } }
                    static final Map<String, String> INSTANCES = new ConcurrentHashMap<>();
                    static String free() { return free(null); }
                    static String free(String app) {
                        String key = app != null ? app : "none";
                        String instance = INSTANCES.remove(key);
                        return instance;
                    }
                    static String unguarded() { return unguarded(null, true); }
                    static String unguarded(String app, boolean b) {
                        String key = b ? app : "none";
                        return INSTANCES.remove(key);
                    }
                    static Res conf(String p) {
                        Res custom = custom(p);
                        return custom == null ? fallback() : custom;
                    }
                    private static Res custom(String p) {
                        if (p.isEmpty()) return null;
                        return new Fixed();
                    }
                    private static Res fallback() { return new Fixed(); }
                    static class Reader {
                        private final InputStream in;
                        Reader(Res res) { this.in = res.in(); }
                    }
                    static Reader load(String p) { return new Reader(conf(p)); }
                }
                """, Policy.NULL_MARKED_FLOW_ONLY.withoutContracts()
                        .withWorld(World.CLOSED).withoutTestNulls());
        assertTrue(keyKnownAtRemove(report, "free"), "app != null ? app : \"none\"");
        assertFalse(keyKnownAtRemove(report, "unguarded"), "b ? app : \"none\"");
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Res", s.get("conf()"), s.toString());
        assertEquals("Res", s.get("<init>(0:res)"), s.toString());
    }

    /*
     CodeLaser/maddi-mod#48 A2, nacos InstanceOperatorClientImpl.getHeartBeatInterval: 'if (metadata.isPresent() &&
     metadata.get().getExtendData().containsKey(HEART_BEAT_INTERVAL)) return toLong(metadata.get().getExtendData()
     .get(HEART_BEAT_INTERVAL));'. The map is a chain of pure calls, the key a constant; a modifying call in between
     forgets it.
     */
    @DisplayName("key presence: a constant key in a map reached through pure calls")
    @Test
    public void keyPresenceConstantKey() {
        Report report = run("a.b.KC", """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                import java.util.Optional;
                class KC {
                    static final String KEY = "interval";
                    static class Meta {
                        private final Map<String, Object> data = new HashMap<>();
                        Map<String, Object> getData() { return data; }
                        void reset() { data.clear(); }
                    }
                    static long guarded(Object val) { return val.hashCode(); }
                    static long cleared(Object val) { return val == null ? 0 : val.hashCode(); }
                    static long chained(Object val) { return val.hashCode(); }
                    static long unguarded(Object val) { return val == null ? 0 : val.hashCode(); }
                    static long interval(Optional<Meta> metadata, Meta m) {
                        if (metadata.isPresent() && metadata.get().getData().containsKey(KEY)) {
                            return chained(metadata.get().getData().get(KEY));
                        }
                        if (m.getData().containsKey(KEY)) return guarded(m.getData().get(KEY));
                        if (metadata.get().getData().containsKey(KEY)) {
                            metadata.get().reset();
                            return cleared(metadata.get().getData().get(KEY));
                        }
                        return unguarded(m.getData().get(KEY));
                    }
                }
                """);
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(label(info), k(pt)));
        assertEquals("Object", s.get("guarded(0:val)"), s.toString());
        assertEquals("Object", s.get("chained(0:val)"), s.toString());
        assertEquals("Object?", s.get("unguarded(0:val)"), s.toString());
        assertEquals("Object?", s.get("cleared(0:val)"), "reset() may remove the key: " + s);
    }

    /*
     CodeLaser/maddi-mod#48 B8, nacos RequestHandlerRegistry: 'while (!clazz.getSuperclass().equals(H.class)) { if
     (clazz.getSuperclass().equals(Object.class)) break; clazz = clazz.getSuperclass(); }'. The loop assigns clazz only
     the repeated pure call its condition dereferenced (gap 8), so clazz is non-null at every start of the loop. Not
     when the loop assigns it a value that may be null.
     */
    @DisplayName("loops: a variable non-null at the loop's entry and at every way back")
    @Test
    public void loopBackEdges() {
        Report report = run("a.b.LB", """
                package a.b;
                class LB {
                    static abstract class H { }
                    static boolean superclasses(H h) {
                        Class<?> clazz = h.getClass();
                        while (!clazz.getSuperclass().equals(H.class)) {
                            if (clazz.getSuperclass().equals(Object.class)) {
                                return false;
                            }
                            clazz = clazz.getSuperclass();
                        }
                        return true;
                    }
                    static boolean unchecked(H h) {
                        Class<?> clazz = h.getClass();
                        while (!clazz.getName().isEmpty()) {
                            clazz = clazz.getSuperclass();
                        }
                        return true;
                    }
                }
                """);
        assertTrue(receiverKnownInCondition(report, "superclasses"), "clazz = clazz.getSuperclass(), dereferenced");
        assertFalse(receiverKnownInCondition(report, "unchecked"), "clazz = clazz.getSuperclass(), unchecked");
    }

    // the facts on clazz at the loop condition's first call, 'clazz.getX()'
    private boolean receiverKnownInCondition(Report report, String method) {
        MethodInfo mi = parsed.methods().stream().filter(m -> m.name().equals(method)).findFirst().orElseThrow();
        Statement loop = mi.methodBody().statements().get(1);
        List<io.codelaser.maddi.cst.api.expression.MethodCall> calls = new java.util.ArrayList<>();
        loop.expression().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc && mc.object() != null
                && mc.object() instanceof io.codelaser.maddi.cst.api.expression.VariableExpression) calls.add(mc);
            return true;
        });
        io.codelaser.maddi.cst.api.expression.MethodCall first = calls.getFirst();
        Variable clazz = ((io.codelaser.maddi.cst.api.expression.VariableExpression) first.object()).variable();
        return report.useSites().nonNullAt(loop, clazz) || report.useSites().nonNullAt(first, clazz);
    }

    // the facts on key at 'INSTANCES.remove(key)', the method's second statement
    private boolean keyKnownAtRemove(Report report, String method) {
        MethodInfo mi = parsed.methods().stream()
                .filter(m -> m.name().equals(method) && m.parameters().size() >= 1).findFirst().orElseThrow();
        Statement st = mi.methodBody().statements().get(1);
        List<io.codelaser.maddi.cst.api.expression.MethodCall> removes = new java.util.ArrayList<>();
        st.visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc && mc.methodInfo() != null
                && "remove".equals(mc.methodInfo().name())) removes.add(mc);
            return true;
        });
        io.codelaser.maddi.cst.api.expression.MethodCall remove = removes.getFirst();
        Variable key = ((io.codelaser.maddi.cst.api.expression.VariableExpression) remove.parameterExpressions()
                .getFirst()).variable();
        return report.useSites().nonNullAt(st, key) || report.useSites().nonNullAt(remove, key);
    }

    // the facts on x when the method's 'return x.m();' starts
    private static boolean receiverKnownAtReturn(Report report, MethodInfo method) {
        Statement rs = method.methodBody().statements().getFirst();
        io.codelaser.maddi.cst.api.expression.MethodCall mc =
                (io.codelaser.maddi.cst.api.expression.MethodCall) rs.expression();
        Variable x = ((io.codelaser.maddi.cst.api.expression.VariableExpression) mc.object()).variable();
        return report.useSites().nonNullAt(rs, x);
    }

    // the facts on 'buffer' when the method's 'buffer[pos++] = v' starts
    private static boolean bufferKnownAtWrite(Report report, MethodInfo method) {
        Statement write = method.methodBody().statements().getLast();
        io.codelaser.maddi.cst.api.expression.Assignment a =
                (io.codelaser.maddi.cst.api.expression.Assignment) write.expression();
        Variable buffer = ((io.codelaser.maddi.cst.api.variable.DependentVariable) a.variableTarget()).arrayVariable();
        return report.useSites().nonNullAt(write, buffer);
    }

    // timefold ReflectionHelper (the diagnose session, CodeLaser/maddi-mod#27 b): the try body starts with the loop's
    // 'c != null'; the catch's assignment only matters after it
    @DisplayName("use sites: a try body starts with the facts at the try, a catch with those its body keeps")
    @Test
    public void tryBodyKeepsLoopCondition() {
        Report report = run("a.b.RH", """
                package a.b;
                import java.lang.reflect.Field;
                class RH {
                    static Field find(Class<?> start, String name) {
                        Class<?> c = start;
                        while (c != null) {
                            try {
                                return c.getDeclaredField(name);
                            } catch (NoSuchFieldException e) {
                                c = c.getSuperclass();
                            }
                        }
                        return null;
                    }
                    static String body(String s) {
                        String t = s;
                        if (t != null) {
                            try {
                                t = null;
                                return String.valueOf(Integer.parseInt(s));
                            } catch (NumberFormatException e) {
                                return t.trim();
                            }
                        }
                        return "";
                    }
                }
                """);
        TryStatement inLoop = (TryStatement) parsed.findUniqueMethod("find", 2).methodBody().statements().get(1)
                .block().statements().getFirst();
        Statement returned = inLoop.block().statements().getFirst();
        Variable c = ((VariableExpression) ((MethodCall) returned.expression()).object()).variable();
        assertEquals(true, report.useSites().nonNullAt(returned, c), "the try body: the loop's condition holds");
        assertEquals(true, report.useSites().nonNullAt(inLoop.catchClauses().getFirst().block().statements().getFirst(), c),
                "the catch: the body does not assign c");
        TryStatement assigning = (TryStatement) parsed.findUniqueMethod("body", 1).methodBody().statements().get(1)
                .block().statements().getFirst();
        Statement first = assigning.block().statements().getFirst();
        Variable t = ((io.codelaser.maddi.cst.api.expression.Assignment) first.expression()).variableTarget();
        assertEquals(true, report.useSites().nonNullAt(first, t), "the body's start");
        assertEquals(false, report.useSites().nonNullAt(assigning.catchClauses().getFirst().block().statements()
                .getFirst(), t), "the catch: the body may have assigned t");
    }

    // timefold ElementAwareLinkedList.ElementAwareListIterator (CodeLaser/maddi-mod#27 c): 'if (!hasNext()) throw ...;
    // next.value' with 'hasNext() { return next != null; }' in a final class
    @DisplayName("use sites: a no-argument test of a field of this, as a condition")
    @Test
    public void fieldTestingPredicate() {
        Report report = run("a.b.IT", """
                package a.b;
                final class IT {
                    static final class Node {
                        String value = "v";
                        Node following;
                    }
                    private Node next;
                    IT(Node first) { next = first; }
                    public boolean hasNext() { return next != null; }
                    public boolean done() { return next == null; }
                    public String take() {
                        if (!hasNext()) throw new IllegalStateException();
                        String v = next.value;
                        next = next.following;
                        return v;
                    }
                    public String skip() {
                        if (done()) return null;
                        return next.value;
                    }
                    public String plain() {
                        if (next.following == null) return null;
                        return next.value;
                    }
                    void reset() { next = null; }
                }
                """);
        Statement read = parsed.findUniqueMethod("take", 0).methodBody().statements().get(1);
        io.codelaser.maddi.cst.api.variable.FieldReference value = (io.codelaser.maddi.cst.api.variable.FieldReference)
                ((VariableExpression) ((io.codelaser.maddi.cst.api.statement.LocalVariableCreation) read).localVariable()
                        .assignmentExpression()).variable();
        Variable next = ((VariableExpression) value.scope()).variable();
        assertEquals(true, report.useSites().nonNullAt(read, next), "after 'if (!hasNext()) throw'");
        Statement returned = parsed.findUniqueMethod("skip", 0).methodBody().statements().get(1);
        assertEquals(true, report.useSites().nonNullAt(returned, next), "after 'if (done()) return'");
    }

    @DisplayName("a local assigned a value known non-null takes no null through the closure (jenkins CLIRegisterer.main)")
    @Test
    public void assignedNonNullLocal() {
        Report report = run("a.b.CR", """
                package a.b;
                import java.util.*;
                class CR {
                    interface Binder { Object call(Object o); }
                    static void consume(int x) { }
                    static int main(List<Binder> binders) {
                        Object instance = null;
                        for (Binder binder : binders) instance = binder.call(instance);
                        Integer exitCode = (instance instanceof Integer) ? (Integer) instance : 0;
                        consume(exitCode);
                        return exitCode;
                    }
                    static Object maybe(List<Binder> binders, boolean b) {
                        Object instance = null;
                        for (Binder binder : binders) instance = binder.call(instance);
                        Object kept = b ? instance : "x";
                        return kept;
                    }
                }
                """);
        String locals = locals(report);
        // the loop may run zero times: 'instance' keeps its null (CodeLaser/maddi-mod#72 4a), and the closure links
        // 'exitCode ← instance ← null'. Neither branch can be null. Gate NOASSIGNEDNONNULL.
        assertTrue(locals.contains("main.instance: Object?"), locals);
        assertTrue(locals.contains("main.exitCode: Integer\n"), locals);
        // the control: an unguarded branch keeps the null
        assertTrue(locals.contains("maybe.kept: Object?"), locals);
    }
}
