package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;

/*
 fernflower's IfHelper and MergeHelper: a companion whose reorderIf/matchWhile trips the work ceiling, and is taken as
 modifying. Two things made that reach every @JvmStatic facade, where Java's static twins stayed non-modifying
 (maddi-mod#145):
 - maddi's K2 converter wrote a bare call inside the companion, `deg(s)`, as `H.Companion.deg(s)`: a call on a static
   field of the outer type, so a modifying callee modified H's own static state. kotlinc calls it on `this`, as the
   converter already did inside a named `object`.
 - the facade's body IS such a call, `H.Companion.top(s)`, and when top's verdict came in late (it waits on the
   abandoned method), cycle breaking counted the call as modifying. The Companion object has no state to modify: a
   final type with no instance field (its properties are static fields of H). C keeps a companion WITH state
   modifying.
 */
public class TestKotlinAnalyzerDegradedCompanion extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            class H {
                companion object {
                    @JvmStatic fun top(s: StringBuilder): Boolean = mid(s)
                    fun mid(s: StringBuilder): Boolean = deg(s)
                    fun deg(s: StringBuilder): Boolean = s.length > 0
                }
            }
            class C {
                companion object {
                    private val seen: MutableList<String> = ArrayList()
                    @JvmStatic fun see(s: String) { record(s) }
                    fun record(s: String) { seen.add(s) }
                }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.ArrayList;
            import java.util.List;
            class H {
                static boolean top(StringBuilder s) { return mid(s); }
                static boolean mid(StringBuilder s) { return deg(s); }
                static boolean deg(StringBuilder s) { return s.length() > 0; }
            }
            class C {
                private static final List<String> seen = new ArrayList<>();
                static void see(String s) { record(s); }
                static void record(String s) { seen.add(s); }
            }
            """;

    @Test
    public void anAbandonedCompanionMethodDoesNotReachTheFacade() {
        Analyzed a;
        LinkComputerImpl.DEGRADE_FOR_TESTING = mi -> mi.name().equals("deg");
        try {
            a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        } finally {
            LinkComputerImpl.DEGRADE_FOR_TESTING = null;
        }
        assertEquals("{return mid(s);}", a.kotlinType("H.Companion").findUniqueMethod("top", 1).methodBody().toString());
        StringBuilder sb = new StringBuilder();
        for (String[] tm : new String[][]{{"H", "top"}, {"H", "mid"}, {"H", "deg"}, {"C", "see"}, {"C", "record"}}) {
            boolean facade = tm[1].equals("top") || tm[1].equals("see");
            var k = (facade ? a.kotlinType(tm[0]) : a.kotlinType(tm[0] + ".Companion")).findUniqueMethod(tm[1], 1);
            var j = a.javaType(tm[0]).findUniqueMethod(tm[1], 1);
            sb.append(tm[0]).append('.').append(tm[1]).append(" java=").append(j.isNonModifying())
              .append(" kotlin=").append(k.isNonModifying()).append('\n');
        }
        assertEquals("""
                H.top java=true kotlin=true
                H.mid java=true kotlin=true
                H.deg java=false kotlin=false
                C.see java=false kotlin=false
                C.record java=false kotlin=false
                """, sb.toString());
    }
}
