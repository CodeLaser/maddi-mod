package io.codelaser.maddi.modification.analyzer.kotlin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/*
 A modifying call on an element read from a list field modifies the object that holds the list, in Kotlin as in
 Java. Fernflower's AnnotationExprent.toJava calls `parValues.get(i).toJava(…)` on a `MutableList<out Exprent>` field:
 modifying in Java, non-modifying in its Kotlin translation (maddi-mod#145, Kotlin claiming more than Java).
 */
public class TestKotlinAnalyzerElementModification extends CommonKotlinAnalyzerTest {

    @Language("kotlin")
    private static final String KOTLIN = """
            package k
            class Part { private var n: Int = 0; fun bump(): Int { n += 1; return n } }
            class Holder(parts: MutableList<out Part>) {
                private val parts: MutableList<out Part>
                init { this.parts = parts }
                fun viaGet(): Int = parts.get(0).bump()
                fun viaIndex(): Int = parts[0].bump()
                fun viaLoop(): Int { var i = 0; var s = 0; while (i < parts.size) { s += parts.get(i).bump(); i++ }; return s }
            }
            class ReadOnly(private val parts: List<Part>) {
                fun viaGet(): Int = parts.get(0).bump()
            }
            // fernflower's ExprProcessor.popNestedTypeAnnotation: each element modified in Kotlin's inline forEach
            object Pop {
                fun viaForEach(parts: List<Part>) { parts.forEach { p -> p.bump() } }
                fun viaFor(parts: List<Part>) { for (p in parts) p.bump() }
            }
            // Annotation is an Exp itself: its render() calls into the family it belongs to, a cycle (fernflower's
            // AnnotationExprent.toJava calling Exprent.toJava)
            abstract class Exp { abstract fun render(): String }
            class Counting : Exp() { private var n: Int = 0; override fun render(): String { n += 1; return "c" } }
            open class Annotation(names: MutableList<String>, values: MutableList<out Exp>) : Exp() {
                private val names: List<String>
                private val values: MutableList<out Exp>
                init { this.names = names; this.values = values }
                override fun render(): String {
                    val sb = StringBuilder()
                    var i = 0
                    while (i < names.size) { sb.append(names.get(i)); sb.append(values.get(i).render()); i++ }
                    return sb.toString()
                }
            }
            """;

    @Language("java")
    private static final String JAVA = """
            package j;
            import java.util.List;
            final class Part { private int n; int bump() { n += 1; return n; } }
            final class Holder {
                private final List<? extends Part> parts;
                Holder(List<? extends Part> parts) { this.parts = parts; }
                int viaGet() { return parts.get(0).bump(); }
                int viaIndex() { return parts.get(0).bump(); }
                int viaLoop() { int i = 0, s = 0; while (i < parts.size()) { s += parts.get(i).bump(); i++; } return s; }
            }
            final class ReadOnly {
                private final List<Part> parts;
                ReadOnly(List<Part> parts) { this.parts = parts; }
                int viaGet() { return parts.get(0).bump(); }
            }
            final class Pop {
                static void viaForEach(List<Part> parts) { parts.forEach(p -> p.bump()); }
                static void viaFor(List<Part> parts) { for (Part p : parts) p.bump(); }
            }
            abstract class Exp { abstract String render(); }
            final class Counting extends Exp { private int n; String render() { n += 1; return "c"; } }
            class Annotation extends Exp {
                private final List<String> names;
                private final List<? extends Exp> values;
                Annotation(List<String> names, List<? extends Exp> values) { this.names = names; this.values = values; }
                @Override String render() {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < names.size(); i++) { sb.append(names.get(i)); sb.append(values.get(i).render()); }
                    return sb.toString();
                }
            }
            """;

    private static Analyzed a;

    @BeforeAll
    public static void beforeAll() {
        a = analyze(KOTLIN, JAVA);
    }

    @Test
    public void javaSeesTheModification() {
        for (String m : new String[]{"viaGet", "viaIndex", "viaLoop"}) {
            assertEquals("method " + m + ": nonModifying=false", firstWord(a.java("Holder", m), m));
        }
        assertEquals("method viaGet: nonModifying=false", firstWord(a.java("ReadOnly", "viaGet"), "viaGet"));
        assertEquals("method render: nonModifying=false", firstWord(a.java("Annotation", "render"), "render"));
    }

    @Test
    public void kotlinSeesTheModification() {
        for (String m : new String[]{"viaGet", "viaIndex", "viaLoop"}) {
            assertEquals("method " + m + ": nonModifying=false", firstWord(a.kotlin("Holder", m), m),
                    () -> a.kotlin("Holder", "viaGet", "viaIndex", "viaLoop"));
        }
        assertEquals("method viaGet: nonModifying=false", firstWord(a.kotlin("ReadOnly", "viaGet"), "viaGet"));
        assertEquals("method render: nonModifying=false", firstWord(a.kotlin("Annotation", "render"), "render"),
                () -> a.kotlin("Annotation", "render") + "\n--- java\n" + a.java("Annotation", "render"));
    }

    // ⛔ UNSOUND TODAY (CodeLaser/maddi-mod#145): Kotlin's inline forEach drops the lambda's modification of the
    // elements; `for` and Java's forEach see it
    @Disabled("maddi-mod#145: a modification inside Kotlin's inline forEach does not reach the collection")
    @Test
    public void aLambdaElementModificationReachesTheParameter() {
        for (String m : new String[]{"viaFor", "viaForEach"}) {
            assertEquals(false, a.javaType("Pop").findUniqueMethod(m, 1).parameters().getFirst().isUnmodified(), "java " + m);
            assertEquals(false, a.kotlinType("Pop").findUniqueMethod(m, 1).parameters().getFirst().isUnmodified(), "kotlin " + m);
        }
    }

    /** The method's line, up to and including its nonModifying verdict. */
    private static String firstWord(String report, String method) {
        return report.lines().filter(l -> l.startsWith("method " + method + ":")).findFirst()
                .map(l -> l.replaceAll("(nonModifying=\\w+).*", "$1")).orElse("<no line for " + method + ">\n" + report);
    }
}
