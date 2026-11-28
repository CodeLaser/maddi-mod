package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/**
 * CodeLaser/maddi-mod#152, the Kotlin twin of {@code TestCopyLoopElements}: J2K copies a reassigned parameter into a
 * shadowing {@code var lst = lst}, so only the copy's link to the parameter's elements decides the verdict
 * (fernflower's {@code ExprProcessor.listToJava}).
 */
public class TestKotlinAnalyzerCopyLoopElements extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            class E { var n: Int = 0; fun render(): String { n++; return "" } }
            object U {
                fun copy(lst: List<E>): MutableList<E> { val ret: MutableList<E> = ArrayList<E>(); for (e in lst) { ret.add(e) } return ret }
                fun direct(lst: List<E>): Int { var s = 0; for (e in copy(lst)) { s += e.render().length }; return s }
                fun shadowed(lst: List<E>): Int { var lst = lst; lst = copy(lst); var s = 0; for (e in lst) { s += e.render().length }; return s }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.ArrayList;
            import java.util.List;
            class E { int n; String render() { n++; return ""; } }
            final class U {
                static List<E> copy(List<E> lst) { List<E> ret = new ArrayList<>(); for (E e : lst) { ret.add(e); } return ret; }
                static int direct(List<E> lst) { int s = 0; for (E e : copy(lst)) { s += e.render().length(); } return s; }
                static int shadowed(List<E> lst) { List<E> x = lst; x = copy(x); int s = 0; for (E e : x) { s += e.render().length(); } return s; }
            }
            """;

    @Test
    public void theCopysElementsAreTheParameters() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        StringBuilder sb = new StringBuilder();
        for (String m : new String[]{"direct", "shadowed"}) {
            var jm = a.javaType("U").methods().stream().filter(x -> x.name().equals(m)).findFirst().orElseThrow();
            var km = a.kotlinType("U").methods().stream().filter(x -> x.name().equals(m)).findFirst().orElseThrow();
            sb.append(m).append(" java=").append(jm.parameters().getFirst().isUnmodified())
              .append(" kotlin=").append(km.parameters().getFirst().isUnmodified()).append('\n');
        }
        assertEquals("""
                direct java=false kotlin=false
                shadowed java=false kotlin=false
                """, sb.toString());
    }
}
