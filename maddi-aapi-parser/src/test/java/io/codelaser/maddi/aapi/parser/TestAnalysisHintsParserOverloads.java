/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.aapi.parser;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Overloads that share name, arity and erased parameter types. Kotlin's {@code @JvmName("minOrThrow")} puts three
 * on {@code CollectionsKt___CollectionsKt}: {@code double minOrThrow(Iterable<Double>)}, {@code float
 * minOrThrow(Iterable<Float>)} and {@code <T extends Comparable<? super T>> T minOrThrow(Iterable<? extends T>)}.
 * The parser used to give a contract to the first erasure match, so one of the three took it, depending on member
 * order. It now matches such a method on its full signature, and a shadow tells the overloads apart -- Java would
 * reject them as a name clash -- with a tag: {@code minOrThrow$Double} stands for {@code minOrThrow}.
 */
public class TestAnalysisHintsParserOverloads {

    @Language("java")
    private static final String SHADOW = """
            package x.hints;
            import io.codelaser.maddi.annotation.Independent;
            import io.codelaser.maddi.annotation.Modified;
            import io.codelaser.maddi.annotation.NotModified;
            public class KotlinCollectionsProbe {
                public static final String PACKAGE_NAME = "kotlin.collections";
                class CollectionsKt___CollectionsKt$ {
                    // tagged: one per overload
                    static double minOrThrow$Double(@NotModified Iterable<Double> receiver) { return 0d; }
                    static float minOrThrow$Float(@Modified Iterable<Float> receiver) { return 0f; }
                    @Independent(hc = true)
                    static <T extends Comparable<? super T>> T minOrThrow(Iterable<? extends T> receiver) { return null; }
                    // untagged, but its erasure matches three: the full signature picks one
                    static double maxOrThrow(@NotModified Iterable<Double> receiver) { return 0d; }
                    // tagged, matching none of the three on its full signature: not transferred, a warning
                    static int maxOrThrow$Int(@NotModified Iterable<Integer> receiver) { return 0; }
                }
            }
            """;

    private static MethodInfo target(TypeInfo part, String name, String returnType) {
        return part.methods().stream()
                .filter(m -> m.name().equals(name) && m.parameters().size() == 1
                             && (m.returnType().isTypeParameter() ? "T"
                : m.returnType().fullyQualifiedName()).equals(returnType))
                .findFirst().orElseThrow(() -> new AssertionError(name + " returning " + returnType));
    }

    @Test
    public void overloadsBySignature(@TempDir Path hints) throws IOException {
        Path dir = Files.createDirectories(hints.resolve("x/hints"));
        Files.writeString(dir.resolve("KotlinCollectionsProbe.java"), SHADOW);
        AnalysisHintsParser parser = new AnalysisHintsParser(CompileAnalysisHints.kotlinJavaInspectorFactory());
        JavaInspector javaInspector = parser.go(new AnalysisHints.Builder()
                .setLibraryName("probe")
                .setAnalysisResultsDir(hints.resolve("out"))
                .setHintsPath(hints)
                .setPackagePrefix("x.hints")
                .build());
        TypeInfo part = javaInspector.compiledTypesManager()
                .type("kotlin.collections.CollectionsKt___CollectionsKt", javaInspector.mainSources());
        assertNotNull(part);

        MethodInfo minDouble = target(part, "minOrThrow", "double");
        MethodInfo minFloat = target(part, "minOrThrow", "float");
        MethodInfo minT = target(part, "minOrThrow", "T");
        assertEquals("[@NotModified]", parser.annotations(minDouble.parameters().getFirst()).toString());
        assertEquals("[@Modified]", parser.annotations(minFloat.parameters().getFirst()).toString());
        assertEquals("[@Independent(hc=true)]", parser.annotations(minT).toString());
        assertEquals("[]", parser.annotations(minT.parameters().getFirst()).toString());

        MethodInfo maxDouble = target(part, "maxOrThrow", "double");
        assertEquals("[@NotModified]", parser.annotations(maxDouble.parameters().getFirst()).toString());
        assertNull(parser.data(target(part, "maxOrThrow", "float").parameters().getFirst()));
        assertNull(parser.data(target(part, "maxOrThrow", "T").parameters().getFirst()));

        assertEquals(1, parser.getWarnings(), "maxOrThrow$Int matches no overload");
    }

    @Language("java")
    private static final String CLASH = """
            package x.hints;
            import io.codelaser.maddi.annotation.Modified;
            import io.codelaser.maddi.annotation.NotModified;
            public class KotlinCollectionsProbe {
                public static final String PACKAGE_NAME = "kotlin.collections";
                class CollectionsKt___CollectionsKt$ {
                    static double minOrThrow(@NotModified Iterable<Double> receiver) { return 0d; }
                    static <T extends Comparable<? super T>> T minOrThrow(@Modified Iterable<? extends T> receiver) { return null; }
                    static <T> T firstOrNull(@NotModified Iterable<? extends T> receiver) { return null; }
                }
            }
            """;

    /*
    Without a tag, Java rejects the overloads as a name clash; javac does not enter the second method, and the
    scanner then fails on its type parameter. The refusal must name javac's error, not only the scanner's
    "Cannot find element 'T' on stack" -- which is what the first attempt at contracting minOrThrow reported.
    */
    @Test
    public void nameClash(@TempDir Path hints) throws IOException {
        Path dir = Files.createDirectories(hints.resolve("x/hints"));
        Files.writeString(dir.resolve("KotlinCollectionsProbe.java"), CLASH);
        AnalysisHintsParser parser = new AnalysisHintsParser(CompileAnalysisHints.kotlinJavaInspectorFactory());
        AnalysisHints analysisHints = new AnalysisHints.Builder()
                .setLibraryName("probe")
                .setAnalysisResultsDir(hints.resolve("out"))
                .setHintsPath(hints)
                .setPackagePrefix("x.hints")
                .build();
        RuntimeException e = assertThrows(RuntimeException.class, () -> parser.go(analysisHints));
        assertTrue(e.getMessage().contains("name clash"), e.getMessage());
        assertTrue(e.getMessage().contains("line 8"), e.getMessage());
    }
}
