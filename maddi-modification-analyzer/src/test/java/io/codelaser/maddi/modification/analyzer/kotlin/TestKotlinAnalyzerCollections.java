package io.codelaser.maddi.modification.analyzer.kotlin;

import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 Defensive copies. A Kotlin class that keeps `xs.toList()` keeps a copy, but not an immutable one: toList/toSet/toMap
 return a fresh ArrayList/LinkedHashSet/LinkedHashMap, which the accessor hands out. Its Java twin is
 `new ArrayList<>(xs)`, not `List.copyOf(xs)`. Both Kotlin classes initialize the field in a property initializer, so
 CodeLaser/maddi#85 applies to their constructors alike; the type and field verdicts isolate the copy.
 */
public class TestKotlinAnalyzerCollections extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            class Snap(xs: List<StringBuilder>) { private val items = xs.toList(); fun items(): List<StringBuilder> = items }
            class SnapJ(xs: List<StringBuilder>) { private val items = java.util.List.copyOf(xs); fun items(): List<StringBuilder> = items }
            class Lines(lines: List<String>) { val lines: List<String> = lines.toList() }
            class Entries(entries: Map<String, String>) { private val entries: Map<String, String> = entries.toMap(); fun find(k: String): String? = entries[k] }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.List;
            final class Snap { private final List<StringBuilder> items; Snap(List<StringBuilder> xs) { this.items = List.copyOf(xs); } List<StringBuilder> items() { return items; } }
            final class SnapJ { private final List<StringBuilder> items; SnapJ(List<StringBuilder> xs) { this.items = List.copyOf(xs); } List<StringBuilder> items() { return items; } }
            final class Lines { private final List<String> lines; Lines(List<String> lines) { this.lines = new java.util.ArrayList<>(lines); } List<String> getLines() { return lines; } }
            """;

    private static Analyzed a;

    @BeforeAll
    public static void beforeAll() {
        a = analyze(KOTLIN, JAVA);
    }

    /* the Java spelling in Kotlin: same type, field and accessor verdicts as Java */
    @Test
    public void listCopyOf() {
        a.assertSameAsJava("SnapJ", "items");
        assertEquals("type SnapJ: @Immutable(hc=true) @Independent(hc=true)", a.kotlin("SnapJ").lines().findFirst().orElseThrow());
    }

    /* by design: `toList()` is a mutable copy, and the accessor hands it out (the Java `Snap` keeps an immutable one) */
    @Test
    public void defensiveCopy() {
        assertEquals("""
                type Snap: @Immutable(hc=true) @Independent(hc=true)
                field items: final=true unmodified=true @Independent(hc=true)
                method items: nonModifying=true @Independent(hc=true)""", a.java("Snap", "items"));
        assertEquals("""
                type Snap: @FinalFields @Dependent
                field items: final=true unmodified=true @Dependent
                method items: nonModifying=true @Dependent""", a.kotlin("Snap", "items"));
    }

    /* The demo's Snapshot and Lookup: a mutable copy is no immutable wrapper after some mark either. The parameter is
       named like the property, so the initializer reads the parameter (maddi 317b6c225); fieldWrapperProvablyImmutable
       counted toList/toMap as immutable copies and gave @Immutable(hc=true, after="lines") while the type is
       @FinalFields @Dependent. */
    @Test
    public void mutableCopyIsNotEventual() {
        assertEquals("type Lines: @FinalFields @Dependent", a.java("Lines").lines().findFirst().orElseThrow());
        assertEquals("type Lines: @FinalFields @Dependent", a.kotlin("Lines").lines().findFirst().orElseThrow());
        assertEquals("type Entries: @FinalFields @Dependent", a.kotlin("Entries").lines().findFirst().orElseThrow());
        for (String name : new String[]{"Lines", "Entries"}) {
            assertEquals(ValueImpl.EventuallyImmutableImpl.NOT_EVENTUAL, a.kotlinType(name).analysis()
                    .getOrDefault(PropertyImpl.EVENTUALLY_IMMUTABLE_TYPE, ValueImpl.EventuallyImmutableImpl.NOT_EVENTUAL), name);
        }
    }
}
