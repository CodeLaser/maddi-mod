package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/*
 fernflower's NestedClassProcessor.insertLocalVars: `iterateExprents(graph, object : ExprentIteratorWithReplace {
 override fun processExprent(exprent) { … return replaceExprent(exprent) ?: exprent } fun replaceExprent(…) { …
 exprent.replaceExprent(expr, retExpr) … } })`. Java modifies the argument through the helper; Kotlin did not, because
 the K2 front end converted each method of an `object :` before its later siblings existed, and `return r(e)` lost its
 call (maddi-mod#145; maddi's ObjectLiteralSiblingCallTest). G, a direct modification, always agreed.
 */
public class TestKotlinAnalyzerObjectSiblingCall extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            import java.util.ArrayList
            abstract class E {
                open fun all(): MutableList<E?> = ArrayList()
                open fun replace(o: E?, n: E?) { }
            }
            class F : E() {
                private val kids: MutableList<E?> = ArrayList()
                override fun all(): MutableList<E?> = ArrayList(kids)
                override fun replace(o: E?, n: E?) { kids.set(kids.indexOf(o), n) }
            }
            class W { val ext: MutableList<Int> = ArrayList() }
            interface A { fun p(e: E?): E? }
            interface B { fun p(e: E?): E? }
            interface C { fun p(e: E?): E? }
            interface D { fun p(e: E?): E? }
            interface G { fun p(e: E?): E? }
            class N {
                fun a(es: MutableList<E?>) {
                    for (x in es) object : A {
                        override fun p(e: E?): E? { e!!.all(); return r(e) }
                        fun r(e: E?): E? { e!!.replace(null, null); return null }
                    }.p(x)
                }
                fun b(es: MutableList<E?>) {
                    for (x in es) object : B {
                        override fun p(e: E?): E? { return r(e) }
                        fun r(e: E?): E? { e!!.replace(null, null); return null }
                    }.p(x)
                }
                fun c(es: MutableList<E?>, w: W) {
                    for (x in es) object : C {
                        override fun p(e: E?): E? { w.ext.add(1); return r(e) }
                        fun r(e: E?): E? { e!!.replace(null, null); return null }
                    }.p(x)
                }
                fun d(es: MutableList<E?>) {
                    for (x in es) object : D {
                        override fun p(e: E?): E? { if (e!!.all().isEmpty()) return null; return r(e) }
                        fun r(e: E?): E? { e!!.replace(null, null); return null }
                    }.p(x)
                }
                fun g(es: MutableList<E?>) {
                    for (x in es) object : G {
                        override fun p(e: E?): E? { e!!.replace(null, null); return null }
                    }.p(x)
                }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.ArrayList;
            import java.util.List;
            abstract class E {
                List<E> all() { return new ArrayList<>(); }
                void replace(E o, E n) { }
            }
            class F extends E {
                private final List<E> kids = new ArrayList<>();
                @Override List<E> all() { return new ArrayList<>(kids); }
                @Override void replace(E o, E n) { kids.set(kids.indexOf(o), n); }
            }
            class W { final List<Integer> ext = new ArrayList<>(); }
            interface A { E p(E e); }
            interface B { E p(E e); }
            interface C { E p(E e); }
            interface D { E p(E e); }
            interface G { E p(E e); }
            class N {
                void a(List<E> es) {
                    for (E x : es) new A() {
                        public E p(E e) { e.all(); return r(e); }
                        E r(E e) { e.replace(null, null); return null; }
                    }.p(x);
                }
                void b(List<E> es) {
                    for (E x : es) new B() {
                        public E p(E e) { return r(e); }
                        E r(E e) { e.replace(null, null); return null; }
                    }.p(x);
                }
                void c(List<E> es, W w) {
                    for (E x : es) new C() {
                        public E p(E e) { w.ext.add(1); return r(e); }
                        E r(E e) { e.replace(null, null); return null; }
                    }.p(x);
                }
                void d(List<E> es) {
                    for (E x : es) new D() {
                        public E p(E e) { if (e.all().isEmpty()) return null; return r(e); }
                        E r(E e) { e.replace(null, null); return null; }
                    }.p(x);
                }
                void g(List<E> es) {
                    for (E x : es) new G() {
                        public E p(E e) { e.replace(null, null); return null; }
                    }.p(x);
                }
            }
            """;

    @Test
    public void theHelperModifiesTheArgument() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        StringBuilder sb = new StringBuilder();
        for (String t : new String[]{"A", "B", "C", "D", "G"}) {
            sb.append(t).append(" java=").append(a.javaType(t).findUniqueMethod("p", 1).parameters().getFirst().isUnmodified())
              .append(" kotlin=").append(a.kotlinType(t).findUniqueMethod("p", 1).parameters().getFirst().isUnmodified()).append('\n');
        }
        assertEquals("""
                A java=false kotlin=false
                B java=false kotlin=false
                C java=false kotlin=false
                D java=false kotlin=false
                G java=false kotlin=false
                """, sb.toString());
    }
}
