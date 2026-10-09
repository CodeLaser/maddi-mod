package io.codelaser.maddi.modification.prepwork.variable;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 The order of VariableData.variableInfoStream() is a function of the source, not of the JVM (CodeLaser/maddi-mod#4).
 Variables first met in a statement's sub-blocks enter the statement's VariableData in the order addMerge walks the
 sub-blocks; that walk went over a java.util immutable map, which iterates in a per-JVM salted order
 (ImmutableCollections.SALT: stable within one JVM, different in the next). With five blocks the chance that a salt
 happens to give source order is small, so this test failed in most JVMs before the fix.
 */
public class TestVariableDataOrder extends CommonTest {

    @Language("java")
    private static final String SWITCH = """
            package a.b;
            class X {
                int a, b, c, d, e;
                void m(int x) {
                    switch (x) {
                        case 1 -> a = 1;
                        case 2 -> b = 2;
                        case 3 -> c = 3;
                        case 4 -> d = 4;
                        default -> e = 5;
                    }
                }
                void old(int x) {
                    switch (x) {
                        case 1: a = 1; break;
                        case 2: b = 2; break;
                        case 3: c = 3; break;
                        case 4: d = 4; break;
                        default: e = 5;
                    }
                }
                void tryCatch(Object o) {
                    try { a = 1; } catch (IllegalStateException ex) { b = 2; } catch (RuntimeException re) { c = 3; }
                    finally { d = 4; }
                }
            }
            """;

    private static List<String> fields(Statement statement) {
        return VariableDataImpl.of(statement).knownVariableNames().stream()
                .filter(n -> n.matches("a\\.b\\.X\\.[a-e]")).toList();
    }

    @Test
    public void sourceOrder() {
        TypeInfo X = javaInspector.parse(ABX, SWITCH);
        for (MethodInfo m : X.methods()) new PrepAnalyzer(runtime).doMethod(m);
        String fields = "[a.b.X.a, a.b.X.b, a.b.X.c, a.b.X.d, a.b.X.e]";
        assertEquals(fields, fields(X.findUniqueMethod("m", 1).methodBody().statements().getFirst()).toString());
        assertEquals(fields, fields(X.findUniqueMethod("old", 1).methodBody().statements().getFirst()).toString());
        assertEquals("[a.b.X.a, a.b.X.b, a.b.X.c, a.b.X.d]",
                fields(X.findUniqueMethod("tryCatch", 1).methodBody().statements().getFirst()).toString());
    }
}
