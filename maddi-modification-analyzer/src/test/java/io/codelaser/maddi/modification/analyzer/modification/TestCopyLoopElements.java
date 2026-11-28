package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#152: a list filled with {@code add(e)} in a loop over a parameter holds the parameter's
 * elements; modifying an element of the copy modifies the parameter's elements (deep-only). The copy's elements
 * share the parameter's ({@code ret.§$s ~ lst.§$s}), so an element of the copy is a tentative element of the
 * parameter ({@code e ∈? lst.§$s}), which carries the modification as {@code ∈} does. Modifying the copy itself
 * does not reach the parameter.
 */
public class TestCopyLoopElements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                static class E { int n; String render() { n++; return ""; } }
                static List<E> copy(List<E> lst) { List<E> ret = new ArrayList<>(); for (E e : lst) { ret.add(e); } return ret; }
                static int direct(List<E> lst) { int s = 0; for (E e : copy(lst)) { s += e.render().length(); } return s; }
                static int viaCopy(List<E> lst) { List<E> x = lst; x = copy(x); int s = 0; for (E e : x) { s += e.render().length(); } return s; }
                static int viaParam(List<E> lst) { lst = copy(lst); int s = 0; for (E e : lst) { s += e.render().length(); } return s; }
                // the copy itself modified: not lst
                static void clearCopy(List<E> lst) { List<E> c = copy(lst); c.clear(); }
                static void addToCopy(List<E> lst) { copy(lst).add(new E()); }
            }
            """;

    @Test
    public void reachability() {
        test(true);
    }

    @Test
    public void fixpoint() {
        test(false);
    }

    private void test(boolean reachability) {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(reachability)
                .build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("direct", "viaCopy", "viaParam", "clearCopy", "addToCopy")) {
            var p = X.findUniqueMethod(name, 1).parameters().getFirst();
            sb.append(name).append('=').append(p.isUnmodified()).append('/').append(p.isStructurallyUnmodified())
                    .append('\n');
        }
        // unmodified/structurally unmodified: an element modified reaches lst in its hidden content only
        assertEquals("""
                direct=false/true
                viaCopy=false/true
                viaParam=false/true
                clearCopy=true/true
                addToCopy=true/true
                """, sb.toString());
    }
}
