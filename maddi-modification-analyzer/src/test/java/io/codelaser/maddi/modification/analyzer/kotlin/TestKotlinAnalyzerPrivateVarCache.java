package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

/*
 fernflower's VarVersion.hashCode: a hash code cached in `private var hashCode`. Java's lazily cached field leaves
 hashCode() non-modifying; Kotlin's did not, because the K2 front end gave the private `var` a setter kotlinc never
 emits, which assigns the field outside construction (maddi-mod#145). W and X: the field's name and the method's are
 not what matters.
 */
public class TestKotlinAnalyzerPrivateVarCache extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            open class V(@JvmField val a: Int) {
                private var hashCode: Int = -1
                override fun hashCode(): Int {
                    if (hashCode == -1) { hashCode = a * 3 }
                    return hashCode
                }
            }
            open class W(@JvmField val a: Int) {
                private var h: Int = -1
                override fun hashCode(): Int {
                    if (h == -1) { h = a * 3 }
                    return h
                }
            }
            open class X(@JvmField val a: Int) {
                private var h: Int = -1
                fun cached(): Int {
                    if (h == -1) { h = a * 3 }
                    return h
                }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            class V {
                public final int a;
                private int hashCode = -1;
                V(int a) { this.a = a; }
                @Override public int hashCode() { if (hashCode == -1) { hashCode = a * 3; } return hashCode; }
            }
            class W {
                public final int a;
                private int h = -1;
                W(int a) { this.a = a; }
                @Override public int hashCode() { if (h == -1) { h = a * 3; } return h; }
            }
            class X {
                public final int a;
                private int h = -1;
                X(int a) { this.a = a; }
                int cached() { if (h == -1) { h = a * 3; } return h; }
            }
            """;

    @Test
    public void aLazilyCachedFieldDoesNotModify() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        for (String t : new String[]{"V", "W", "X"}) a.assertSameAsJava(t);
    }
}
