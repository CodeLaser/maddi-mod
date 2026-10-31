package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import io.codelaser.maddi.cst.api.statement.IfElseStatement;
import io.codelaser.maddi.cst.api.statement.Statement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Preconditions}: a parameter its method answers null for with its own exception.
 */
public class TestPreconditions extends NullabilityFixtures {

    @Language("java")
    private static final String REJECTS = """
            package a.b;
            import java.util.Objects;
            class RJ {
                private final String text;
                RJ(String text) {
                    if (text == null) throw new IllegalArgumentException("text cannot be null");
                    this.text = text;
                }
                static String ensureNotBlank(String s, String name) {
                    if (s == null || s.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
                    return s;
                }
                static int helped(String s) { return ensureNotBlank(s, "s").length(); }
                static int dereferenced(String s) { return s.length(); }
                static int required(String s) { return Objects.requireNonNull(s).length(); }
                static RJ make() { return new RJ("x"); }
            }
            """;

    // CodeLaser/maddi-mod#28: reported by default, nullable only under Policy.withRejectionsNullable
    @DisplayName("a parameter whose method answers null with its own exception: the fact, and the opt-in policy")
    @Test
    public void rejectsNull() {
        Report report = run("a.b.RJ", REJECTS, Policy.KOTLIN);
        Set<String> rejecting = report.rejectsNull().stream().map(pi -> pi.methodInfo().name() + ":" + pi.name())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals("[<init>:text, ensureNotBlank:s, helped:s]", rejecting.toString());
        Map<String, String> s = new java.util.TreeMap<>();
        report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName(), k(pt)));
        // a null test in the method itself already makes the parameter nullable (Policy.nullTests); through a helper
        // it does not: that is the parameter the printer reports, and the opt-in makes nullable
        assertEquals("String?", s.get("a.b.RJ.<init>(String):0:text"), s.toString());
        assertEquals("String", s.get("a.b.RJ.helped(String):0:s"), s.toString());

        Report optIn = run("a.b.RJ2", REJECTS.replace("RJ", "RJ2"),
                Policy.KOTLIN.withRejectionsNullable());
        Map<String, String> o = new java.util.TreeMap<>();
        optIn.verdicts().forEach((info, pt) -> o.put(info.fullyQualifiedName(), k(pt)));
        assertEquals("String?", o.get("a.b.RJ2.helped(String):0:s"), o.toString());
        assertEquals("String", o.get("a.b.RJ2.dereferenced(String):0:s"), o.toString());
        assertEquals("String", o.get("a.b.RJ2.required(String):0:s"), o.toString());
    }

    @DisplayName("null-check predicates: an analysed one inferred, as a condition; not for Kotlin's smart casts")
    @Test
    public void predicates() {
        Report report = run("a.b.P", """
                package a.b;
                import java.util.*;
                class P {
                    static boolean isBlank(CharSequence cs) {
                        int len;
                        if (cs == null || (len = cs.length()) == 0) return true;
                        for (int i = 0; i < len; i++) { if (!Character.isWhitespace(cs.charAt(i))) return false; }
                        return true;
                    }
                    static boolean isNotBlank(String s) { return !isBlank(s); }
                    static boolean isEmpty(String s) { return s == null || s.length() == 0; }
                    static boolean hasText(String s) { return s != null && !s.isEmpty(); }
                    static String trimmed(String in) {
                        if (isNotBlank(in)) return in.trim();
                        return "";
                    }
                    static String other(String in) {
                        return isEmpty(in) ? "" : in.trim();
                    }
                    static int viaObjects(String in) {
                        if (Objects.isNull(in)) return 0;
                        return in.length();
                    }
                }
                """);
        TypeInfo p = parsed;
        MethodInfo trimmed = p.findUniqueMethod("trimmed", 1);
        ParameterInfo in = trimmed.parameters().getFirst();
        IfElseStatement ifElse = (IfElseStatement) trimmed.methodBody().statements().getFirst();
        Statement then = ifElse.block().statements().getFirst();
        assertEquals(true, report.useSites().nonNullAt(then, in), "isNotBlank is false for null");
        assertEquals(false, report.smartCasts().nonNullAt(then, in), "Kotlin does not see it");
        MethodInfo viaObjects = p.findUniqueMethod("viaObjects", 1);
        Statement last = viaObjects.methodBody().statements().getLast();
        assertEquals(true, report.useSites().nonNullAt(last, viaObjects.parameters().getFirst()),
                "Objects.isNull, from the table");
        MethodInfo other = p.findUniqueMethod("other", 1);
        List<io.codelaser.maddi.cst.api.expression.MethodCall> calls = new java.util.ArrayList<>();
        other.methodBody().statements().getFirst().expression().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc) calls.add(mc);
            return true;
        });
        io.codelaser.maddi.cst.api.expression.MethodCall trim = calls.stream()
                .filter(c -> "trim".equals(c.methodInfo().name())).findFirst().orElseThrow();
        assertEquals(true, report.useSites().nonNullAt(trim, other.parameters().getFirst()), "isEmpty is true for null");
    }

    // timefold SolutionDescriptor.findEntityDescriptorOrFail (the diagnose session): 'var d = find(c); if (d == null)
    // { throw ...; } return d;' returns no null, though find does
    @DisplayName("a throw guard before the return: the OrFail wrapper returns no null")
    @Test
    public void throwGuardBeforeReturn() {
        String src = """
                package a.b;
                import java.util.HashMap;
                import java.util.Map;
                class FD {
                    private static final String NONE = "none";
                    private final Map<Class<?>, String> cache = new HashMap<>();
                    public String findOrFail(Class<?> c) {
                        var d = find(c);
                        if (d == null) {
                            throw new IllegalArgumentException(
                                    "No descriptor for %s (%s)".formatted(c, c.getSimpleName()));
                        }
                        return d;
                    }
                    public String find(Class<?> c) {
                        var cached = cache.get(c);
                        if (cached == NONE) {
                            return null;
                        } else if (cached != null) {
                            return cached;
                        }
                        var created = c.getSimpleName().isEmpty() ? null : c.getSimpleName();
                        if (created == null) {
                            cache.put(c, NONE);
                            return null;
                        } else {
                            cache.put(c, created);
                            return created;
                        }
                    }
                }
                """;
        StringBuilder out = new StringBuilder();
        int[] round = {0};
        for (String variant : List.of(src, src.replace("var d = find(c);", "String d = find(c);"))) {
            for (Policy policy : List.of(Policy.KOTLIN, Policy.NULL_MARKED,
                    Policy.CAUTIOUS)) {
                String name = "FD" + (++round[0]);
                Report report = run("a.b." + name, variant.replace("class FD ", "class " + name + " "),
                        policy);
                Map<String, String> s = new java.util.TreeMap<>();
                report.verdicts().forEach((info, pt) -> s.put(info.fullyQualifiedName().replace(name, "FD"), k(pt)));
                if (!"String?".equals(s.get("a.b.FD.find(Class)")) || "String?".equals(s.get("a.b.FD.findOrFail(Class)"))) {
                    out.append(variant.contains("var d") ? "var " : "typed ").append(policy.world()).append(' ')
                            .append(policy.callResults()).append(": ").append(s.get("a.b.FD.find(Class)")).append(' ')
                            .append(s.get("a.b.FD.findOrFail(Class)")).append(" | ").append(explain(report)).append('\n');
                }
            }
        }
        assertEquals("", out.toString());
    }


    @Language("java")
    private static final String PRECONDITIONS = """
            package a.b;
            import java.util.*;
            class PF {
                static class Node { Node next; String name = "x"; String label; }
                interface Visitor { void visit(Node n); }
                static class Printer implements Visitor { public void visit(Node n) { System.out.println(n.name); } }
                static class Counter implements Visitor {
                    int count;
                    public void visit(Node n) { count += n.name.length(); }
                }
                interface Sink { void accept(Node n); }
                static class Strict implements Sink { public void accept(Node n) { System.out.println(n.name); } }
                static class Lenient implements Sink { public void accept(Node n) { if (n != null) System.out.println(n.name); } }
                interface Echo { void echo(Node n); }
                static class Loud implements Echo { public void echo(Node n) { System.out.println(n.name); } }
                interface Quiet { void hush(Node n); }
                static class Hush implements Quiet { public void hush(Node n) { System.out.println(n.name); } }
                static class St {
                    St parent;
                    final List<St> stats = new ArrayList<>();
                    void replace(St oldstat, St newstat) {
                        stats.add(newstat);
                        newstat.parent = this;
                    }
                }
                static class IfSt extends St {
                    St ifs;
                    @Override
                    void replace(St oldstat, St newstat) {
                        super.replace(oldstat, newstat);
                        if (ifs == oldstat) ifs = newstat;
                    }
                }
                static void show(Node n) { System.out.println(n.name); }
                static void named(Node n) { System.out.println(n.name); }
                static void checked(Node n) {
                    if (n == null) throw new IllegalArgumentException("n");
                    System.out.println(n.name);
                }
                static boolean isNullOrEmpty(String s) { return s == null || s.isEmpty(); }
                static String ensureNotEmpty(String s) {
                    if (isNullOrEmpty(s)) throw new IllegalArgumentException("s");
                    return s;
                }
                static class Doc {
                    final String text;
                    Doc(String text) { this.text = ensureNotEmpty(text); }
                    static Doc from(String text) { return new Doc(text); }
                }
                static void use(Node node, Visitor v, Sink s, Echo e, Quiet q, St parent, St first) {
                    v.visit(node.next);
                    s.accept(node.next);
                    e.echo(node.next);
                    q.hush(node.next);
                    parent.replace(first, parent.parent);
                    named(null);
                    checked(node.next);
                    ensureNotEmpty(node.label);
                    Doc.from(node.label);
                    Echo shown = PF::show;
                    Quiet silent = n -> { };
                    shown.echo(node);
                    silent.hush(node);
                }
            }
            """;

    @DisplayName("Preconditions: a parameter every member of its override family throws on null (maddi-mod#41)")
    @Test
    public void preconditionFamilies() {
        Report report = run("a.b.PF", PRECONDITIONS, Policy.KOTLIN);
        System.out.println(explain(report));
        Set<String> preconditions = report.preconditions().stream().map(pi -> pi.methodInfo().typeInfo().simpleName()
                + "." + pi.methodInfo().name() + ":" + pi.name()).collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals("[Counter.visit:n, Doc.<init>:text, Doc.from:text, IfSt.replace:newstat, PF.checked:n, PF.ensureNotEmpty:s, PF.named:n, PF.show:n, PF.use:e, "
                     + "PF.use:node, PF.use:parent, PF.use:q, PF.use:s, PF.use:v, Printer.visit:n, St.replace:newstat, "
                     + "Visitor.visit:n]", preconditions.toString(),
                "Sink: Lenient accepts null; Echo: a method reference implements it; Quiet: a lambda that does not "
                + "throw; IfSt: through super.replace");
        Set<String> nullArguments = report.nullArguments().stream().map(a -> a.parameter().methodInfo().name() + "("
                + a.argument() + (a.literal() ? ", literal" : "") + ")").collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals("[checked(node.next), ensureNotEmpty(node.label), from(node.label), named(null, literal), replace(parent.parent), visit(node.next)]",
                nullArguments.toString());

        Map<String, String> byName = byName(report);
        assertEquals("Node?", byName.get("a.b.PF.Visitor.visit(a.b.PF.Node):0:n"), "the policy is off in KOTLIN");

        Report declared = run("a.b.PE", PRECONDITIONS.replace("PF", "PE"),
                Policy.KOTLIN.withPreconditionsNonNull());
        Map<String, String> declaredByName = byName(declared);
        assertEquals("Node", declaredByName.get("a.b.PE.Visitor.visit(a.b.PE.Node):0:n"), "the interface with its family");
        assertEquals("Node", declaredByName.get("a.b.PE.Printer.visit(a.b.PE.Node):0:n"));
        assertEquals("Node", declaredByName.get("a.b.PE.Counter.visit(a.b.PE.Node):0:n"));
        assertEquals("St", declaredByName.get("a.b.PE.IfSt.replace(a.b.PE.St,a.b.PE.St):1:newstat"));
        assertEquals("Node?", declaredByName.get("a.b.PE.Sink.accept(a.b.PE.Node):0:n"), "not a precondition");
        assertEquals("Node?", declaredByName.get("a.b.PE.named(a.b.PE.Node):0:n"), "a call passes the literal null");
        assertEquals("Node?", declaredByName.get("a.b.PE.checked(a.b.PE.Node):0:n"), "its answer to null is an IAE");
        assertEquals("String?", declaredByName.get("a.b.PE.ensureNotEmpty(String):0:s"),
                "its answer to null is an IAE, through isNullOrEmpty's comparison");
        assertEquals("String?", declaredByName.get("a.b.PE.Doc.from(String):0:text"),
                "the same through a constructor");
        assertEquals(report.nullArguments().size(), declared.nullArguments().size(), "decided before the policy");
        // what the printers report (CodeLaser/maddi#125): the declared ones that null reached without the policy
        Set<String> nonNull = declared.preconditionsNonNull().stream().map(pi -> pi.methodInfo().typeInfo().simpleName()
                + "." + pi.methodInfo().name() + ":" + pi.name()).collect(Collectors.toCollection(java.util.TreeSet::new));
        assertEquals("[Counter.visit:n, IfSt.replace:newstat, Printer.visit:n, St.replace:newstat, Visitor.visit:n]",
                nonNull.toString());
        assertTrue(report.preconditionsNonNull().isEmpty(), "the policy is off");

        // no null flows on from a declared precondition; under KOTLIN the content write would be asserted anyway
        assertEquals("List<St?>", byName(run("a.b.PG", PRECONDITIONS.replace("PF", "PG"))).get("a.b.PG.St.stats"));
        assertEquals("List<St>", byName(run("a.b.PH", PRECONDITIONS.replace("PF", "PH"),
                Policy.NULL_MARKED.withPreconditionsNonNull())).get("a.b.PH.St.stats"));
    }
}
