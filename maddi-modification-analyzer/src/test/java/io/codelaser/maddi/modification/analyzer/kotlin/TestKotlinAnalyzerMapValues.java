package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.info.TypeInfo;

/*
 Kotlin's `m.values` is Java's `m.values()`, and modifying its elements modifies the map, as in Java. The K2 converter
 typed the call with the getter's declared `Collection<V>`, not the use site's `Collection<U>`, so the elements were not
 the map's and `for (unit in units.values) unit.save()` left `units` unmodified (fernflower's StructContext.saveContext,
 maddi-mod#145).
 */
public class TestKotlinAnalyzerMapValues extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            class U(private val own: Boolean) { private var n: Int = 0; fun isOwn(): Boolean = own; fun save() { n++ } }
            class Ctx {
                private val units: MutableMap<String, U> = HashMap()
                fun add(s: String, u: U) { units.put(s, u) }
                fun viaField() { for (unit in units.values) { if (unit.isOwn()) { unit.save() } } }
                fun viaParameter(m: MutableMap<String, U>) { for (unit in m.values) { unit.save() } }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.*;
            final class U { private final boolean own; private int n; U(boolean own) { this.own = own; } boolean isOwn() { return own; } void save() { n++; } }
            final class Ctx {
                private final Map<String, U> units = new HashMap<>();
                void add(String s, U u) { units.put(s, u); }
                void viaField() { for (U unit : units.values()) { if (unit.isOwn()) { unit.save(); } } }
                void viaParameter(Map<String, U> m) { for (U unit : m.values()) { unit.save(); } }
            }
            """;

    private static String verdicts(TypeInfo ctx) {
        return "viaField nonModifying=" + ctx.findUniqueMethod("viaField", 0).isNonModifying()
               + ", viaParameter m unmodified=" + ctx.findUniqueMethod("viaParameter", 1).parameters().getFirst().isUnmodified();
    }

    private static String valuesType(TypeInfo ctx) {
        StringBuilder sb = new StringBuilder();
        ctx.findUniqueMethod("viaParameter", 1).methodBody().visit(e -> {
            if (e instanceof MethodCall mc && mc.methodInfo().name().equals("values")) sb.append(mc.concreteReturnType().simpleString());
            return true;
        });
        return sb.toString();
    }

    @Test
    public void modifyingTheValuesModifiesTheMap() {
        Analyzed a = analyze(List.of("X.kt", KOTLIN), List.of("X.java", JAVA), true);
        assertEquals("viaField nonModifying=false, viaParameter m unmodified=false", verdicts(a.javaType("Ctx")));
        assertEquals("viaField nonModifying=false, viaParameter m unmodified=false", verdicts(a.kotlinType("Ctx")));
        assertEquals("Collection<U>", valuesType(a.kotlinType("Ctx")));
    }
}
