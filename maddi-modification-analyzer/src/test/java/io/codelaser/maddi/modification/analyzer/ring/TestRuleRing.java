package io.codelaser.maddi.modification.analyzer.ring;

import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/*
 CodeLaser/maddi-mod#158, the rule ring: the shape of Jenkins' LabelExpressionParser. Each rule method returns a
 context object whose field holds what the next rule returned, and wraps a value in a new object (Label.or, .not,
 .paren). The last rule calls the first again. A summary read by the caller gets one field prefixed; the creation
 sites inside the Label operators keep their name in every method.
 */
@EnabledIfEnvironmentVariable(named = "LINKCHURN", matches = ".+", disabledReason = "CodeLaser/maddi-mod#158: a reproduction that prints; run with LATESTWINSCOUNTS=1 LINKCHURN=1")
public class TestRuleRing extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class P {
                static abstract class Label {
                    Label or(Label rhs) { return new Or(this, rhs); }
                    Label and(Label rhs) { return new And(this, rhs); }
                    Label not() { return new Not(this); }
                    Label paren() { return new Paren(this); }
                }
                static class Atom extends Label {
                    final String name;
                    Atom(String name) { this.name = name; }
                }
                static class Or extends Label {
                    final Label lhs; final Label rhs;
                    Or(Label lhs, Label rhs) { this.lhs = lhs; this.rhs = rhs; }
                }
                static class And extends Label {
                    final Label lhs; final Label rhs;
                    And(Label lhs, Label rhs) { this.lhs = lhs; this.rhs = rhs; }
                }
                static class Not extends Label {
                    final Label base;
                    Not(Label base) { this.base = base; }
                }
                static class Paren extends Label {
                    final Label base;
                    Paren(Label base) { this.base = base; }
                }
                static class Ctx { RuntimeException exception; }
                static class Term1Context extends Ctx { Label l; Label r; Term2Context term2; }
                static class Term2Context extends Ctx { Label l; Label r; Term3Context term3; }
                static class Term3Context extends Ctx { Label l; Term4Context term4; }
                static class Term4Context extends Ctx { Label l; Term1Context term1; String atom; }

                private final String[] tokens;
                private int pos;
                P(String[] tokens) { this.tokens = tokens; }
                private boolean peek(String s) { return pos < tokens.length && tokens[pos].equals(s); }

                public Term1Context term1() {
                    Term1Context _localctx = new Term1Context();
                    try {
                        _localctx.term2 = term2();
                        _localctx.l = _localctx.term2.l;
                        while (peek("|")) {
                            pos++;
                            _localctx.term2 = term2();
                            _localctx.r = _localctx.term2.l;
                            _localctx.l = _localctx.l.or(_localctx.r);
                        }
                    } catch (RuntimeException re) {
                        _localctx.exception = re;
                    }
                    return _localctx;
                }
                public Term2Context term2() {
                    Term2Context _localctx = new Term2Context();
                    try {
                        _localctx.term3 = term3();
                        _localctx.l = _localctx.term3.l;
                        while (peek("&")) {
                            pos++;
                            _localctx.term3 = term3();
                            _localctx.r = _localctx.term3.l;
                            _localctx.l = _localctx.l.and(_localctx.r);
                        }
                    } catch (RuntimeException re) {
                        _localctx.exception = re;
                    }
                    return _localctx;
                }
                public Term3Context term3() {
                    Term3Context _localctx = new Term3Context();
                    try {
                        if (peek("!")) {
                            pos++;
                            _localctx.term4 = term4();
                            _localctx.l = _localctx.term4.l;
                            _localctx.l = _localctx.l.not();
                        } else {
                            _localctx.term4 = term4();
                            _localctx.l = _localctx.term4.l;
                        }
                    } catch (RuntimeException re) {
                        _localctx.exception = re;
                    }
                    return _localctx;
                }
                public Term4Context term4() {
                    Term4Context _localctx = new Term4Context();
                    try {
                        if (peek("(")) {
                            pos++;
                            _localctx.term1 = term1();
                            pos++;
                            _localctx.l = _localctx.term1.l.paren();
                        } else {
                            _localctx.atom = tokens[pos++];
                            _localctx.l = new Atom(_localctx.atom);
                        }
                    } catch (RuntimeException re) {
                        _localctx.exception = re;
                    }
                    return _localctx;
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.P", INPUT);
        G<Info> graph = prepAnalyzer.doPrimaryTypesReturnGraph(Set.of(x));
        List<Info> ao = new ComputeAnalysisOrder().go(graph);
        IteratingAnalyzerImpl a = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(60).setNullability(true).build());
        a.analyze(ao, graph);
        System.out.println("RING certified=" + a.certifiedWithoutFrozenValues());
    }

    @Language("java")
    private static final String MINIMAL = """
            package a.b;
            public class Q {
                static class Label {
                    Label wrapA() { return new A(this); }
                    Label wrapB() { return new B(this); }
                    Label wrapC() { return new C(this); }
                }
                static class Atom extends Label { }
                static class A extends Label { final Label base; A(Label base) { this.base = base; } }
                static class B extends Label { final Label base; B(Label base) { this.base = base; } }
                static class C extends Label { final Label base; C(Label base) { this.base = base; } }
                static class C1 { Label l; C2 c2; }
                static class C2 { Label l; C3 c3; }
                static class C3 { Label l; C1 c1; }

                private final String[] tokens;
                private int pos;
                Q(String[] tokens) { this.tokens = tokens; }

                public C1 r1() {
                    C1 c = new C1();
                    c.c2 = r2();
                    c.l = c.c2.l.wrapA();
                    return c;
                }
                public C2 r2() {
                    C2 c = new C2();
                    c.c3 = r3();
                    c.l = c.c3.l.wrapB();
                    return c;
                }
                public C3 r3() {
                    C3 c = new C3();
                    if (pos < tokens.length && tokens[pos++].equals("(")) {
                        c.c1 = r1();
                        c.l = c.c1.l.wrapC();
                    } else {
                        c.l = new Atom();
                    }
                    return c;
                }
            }
            """;

    @Test
    public void minimal() {
        TypeInfo x = javaInspector.parse("a.b.Q", MINIMAL);
        G<Info> graph = prepAnalyzer.doPrimaryTypesReturnGraph(Set.of(x));
        List<Info> ao = new ComputeAnalysisOrder().go(graph);
        IteratingAnalyzerImpl a = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(60).setNullability(true).build());
        a.analyze(ao, graph);
        System.out.println("RING certified=" + a.certifiedWithoutFrozenValues());
    }

    // the minimal ring, with the body of r1 replaced: which ingredient makes it rotate?
    private void variant(String r1Body, String c1Fields) {
        String input = MINIMAL.replace("static class C1 { Label l; C2 c2; }", "static class C1 { " + c1Fields + " }")
                .replace("""
                                c.c2 = r2();
                                c.l = c.c2.l.wrapA();
                        """.indent(0), r1Body)
                .replace("Label wrapA() { return new A(this); }",
                        "Label wrapA() { return new A(this); }\n        Label or(Label rhs) { return new Or(this, rhs); }")
                .replace("static class Atom extends Label { }", "static class Atom extends Label { }\n"
                        + "    static class Or extends Label { final Label lhs; final Label rhs; "
                        + "Or(Label lhs, Label rhs) { this.lhs = lhs; this.rhs = rhs; } }");
        org.junit.jupiter.api.Assertions.assertTrue(input.contains(r1Body.strip()), "variant not applied");
        TypeInfo x = javaInspector.parse("a.b.Q", input);
        G<Info> graph = prepAnalyzer.doPrimaryTypesReturnGraph(Set.of(x));
        List<Info> ao = new ComputeAnalysisOrder().go(graph);
        IteratingAnalyzerImpl a = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(60).setNullability(true).build());
        a.analyze(ao, graph);
        System.out.println("RING certified=" + a.certifiedWithoutFrozenValues());
    }

    @Test
    public void variantBinary() {
        variant("""
                        c.c2 = r2();
                        c.l = c.c2.l;
                        c.c2 = r2();
                        c.r = c.c2.l;
                        c.l = c.l.or(c.r);
                """, "Label l; Label r; C2 c2;");
    }

    @Test
    public void variantLoop() {
        variant("""
                        c.c2 = r2();
                        c.l = c.c2.l;
                        while (pos < tokens.length) {
                            pos++;
                            c.c2 = r2();
                            c.r = c.c2.l;
                            c.l = c.l.or(c.r);
                        }
                """, "Label l; Label r; C2 c2;");
    }

    @Test
    public void variantLoopUnary() {
        variant("""
                        c.c2 = r2();
                        c.l = c.c2.l;
                        while (pos < tokens.length) {
                            pos++;
                            c.l = c.l.wrapA();
                        }
                """, "Label l; C2 c2;");
    }
}
