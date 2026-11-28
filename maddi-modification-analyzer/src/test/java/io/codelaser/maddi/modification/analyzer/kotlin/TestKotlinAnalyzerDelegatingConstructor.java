package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/**
 * CodeLaser/maddi-mod#150, the Kotlin twin of {@code TestDelegatingConstructorLink}: a secondary constructor
 * delegating with {@code : this(..)}, and a subclass passing its argument to the superclass with {@code : Base(p)},
 * link the argument to the field it is stored in.
 */
public class TestKotlinAnalyzerDelegatingConstructor extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            class Proc { private var n: Int = 0; fun set(v: Int) { n = v } }
            class V(private val i: Int, private val p: Proc?) { fun fix() { p?.set(i) } }
            class W(i: Int, private val p: Proc?, x: Int) {
                constructor(i: Int, p: Proc?) : this(i, p, 0)
                fun fix() { p?.set(1) }
            }
            open class Base(protected val p: Proc?)
            class Sub(i: Int, p: Proc?) : Base(p) { fun fix() { p?.set(2) } }
            class User(private val proc: Proc) {
                fun mkV(): Int { V(1, proc).fix(); return 0 }
                fun mkW(): Int { W(1, proc).fix(); return 0 }
                fun mkSub(): Int { Sub(1, proc).fix(); return 0 }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            final class Proc { private int n; void set(int v) { n = v; } }
            final class V {
                private final int i;
                private final Proc p;
                V(int i, Proc p) { this.i = i; this.p = p; }
                void fix() { if (p != null) p.set(i); }
            }
            final class W {
                private final Proc p;
                W(int i, Proc p, int x) { this.p = p; }
                W(int i, Proc p) { this(i, p, 0); }
                void fix() { if (p != null) p.set(1); }
            }
            class Base {
                protected final Proc p;
                Base(Proc p) { this.p = p; }
            }
            final class Sub extends Base {
                Sub(int i, Proc p) { super(p); }
                void fix() { if (p != null) p.set(2); }
            }
            final class User {
                private final Proc proc;
                User(Proc proc) { this.proc = proc; }
                int mkV() { new V(1, proc).fix(); return 0; }
                int mkW() { new W(1, proc).fix(); return 0; }
                int mkSub() { new Sub(1, proc).fix(); return 0; }
            }
            """;

    @Test
    public void theArgumentReachesTheField() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        StringBuilder sb = new StringBuilder();
        for (String m : new String[]{"mkV", "mkW", "mkSub"}) {
            var jm = a.javaType("User").methods().stream().filter(x -> x.name().equals(m)).findFirst().orElseThrow();
            var km = a.kotlinType("User").methods().stream().filter(x -> x.name().equals(m)).findFirst().orElseThrow();
            sb.append(m).append(" java=").append(jm.isNonModifying())
              .append(" kotlin=").append(km.isNonModifying()).append('\n');
        }
        assertEquals("""
                mkV java=false kotlin=false
                mkW java=false kotlin=false
                mkSub java=false kotlin=false
                """, sb.toString());
    }
}
