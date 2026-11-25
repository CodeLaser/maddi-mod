/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.aapi.parser;

import static io.codelaser.maddi.modification.common.CommonTest.javaInspectorFactory;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CodeLaser/maddi#216: a hint for a method the type only inherits matches nothing in the type and is dropped; the
 * parser reports it in {@link AnalysisHintsParser#droppedHints()}, which CompileAnalysisHints fails on.
 */
public class TestAnalysisHintsParserDroppedHints {

    @Language("java")
    private static final String SHADOW = """
            package x.hints;
            import java.util.Iterator;
            import java.util.function.Consumer;
            import io.codelaser.maddi.annotation.Modified;
            public class JavaNioFileProbe {
                public static final String PACKAGE_NAME = "java.nio.file";
                class DirectoryStream$<T> {
                    @Modified Iterator<T> iterator() { return null; }
                    // inherited from Iterable, not declared by DirectoryStream
                    @Modified void forEach(Consumer<? super T> action) { }
                }
            }
            """;

    @Test
    public void inheritedMethodIsReported(@TempDir Path hints) throws IOException {
        Path dir = Files.createDirectories(hints.resolve("x/hints"));
        Files.writeString(dir.resolve("JavaNioFileProbe.java"), SHADOW);
        AnalysisHintsParser parser = new AnalysisHintsParser(javaInspectorFactory());
        parser.go(new AnalysisHints.Builder()
                .setLibraryName("probe")
                .setAnalysisResultsDir(hints.resolve("out"))
                .setHintsPath(hints)
                .setPackagePrefix("x.hints")
                .build());
        List<String> dropped = parser.droppedHints();
        assertEquals(1, dropped.size(), dropped.toString());
        assertTrue(dropped.getFirst().contains("forEach") && dropped.getFirst().contains("java.nio.file.DirectoryStream"),
                dropped.getFirst());
        assertEquals(1, parser.getWarnings());
    }
}
