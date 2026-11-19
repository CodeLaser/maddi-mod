package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/*
 An `object :` expression whose member modifies a captured parameter modifies that parameter, as a Java anonymous
 class does, and as a Kotlin lambda already did. The member's body read the captured `child` as an unresolved
 reference (maddi's K2 converter), so the parameter stayed unmodified in Kotlin only: fernflower's
 NestedClassProcessor, where an `object : ExprentIteratorWithReplace` sets `child.superInvocation` (maddi-mod#145).
 With the reachability pass, as production runs.
 */
public class TestKotlinAnalyzerObjectCapture extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            class Box { var n: Int = 0 }
            class Node { var sup: Box? = null }
            fun interface It { fun f(x: Box?): Box? }
            class Walk(private val xs: MutableList<Box>) {
                fun each(it: It) { for (i in xs.indices) { it.f(xs[i]) } }
            }
            class User {
                fun viaObject(w: Walk, child: Node) { w.each(object : It { override fun f(x: Box?): Box? { child.sup = x; return x } }) }
                fun viaLambda(w: Walk, child: Node) { w.each { x -> child.sup = x; x } }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.List;
            final class Box { int n; }
            final class Node { Box sup; }
            interface It { Box f(Box x); }
            final class Walk { private final List<Box> xs; Walk(List<Box> xs) { this.xs = xs; }
                void each(It it) { for (int i = 0; i < xs.size(); i++) it.f(xs.get(i)); } }
            final class User {
                void viaObject(Walk w, Node child) { w.each(new It() { public Box f(Box x) { child.sup = x; return x; } }); }
                void viaLambda(Walk w, Node child) { w.each(x -> { child.sup = x; return x; }); }
            }
            """;

    @Test
    public void theCapturedParameterIsModified() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        for (String m : new String[]{"viaObject", "viaLambda"}) {
            assertEquals(false, a.javaType("User").findUniqueMethod(m, 2).parameters().get(1).isUnmodified(), "java " + m);
            assertEquals(false, a.kotlinType("User").findUniqueMethod(m, 2).parameters().get(1).isUnmodified(), "kotlin " + m);
        }
    }
}
