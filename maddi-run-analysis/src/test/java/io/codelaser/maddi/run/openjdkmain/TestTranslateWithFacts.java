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

package io.codelaser.maddi.run.openjdkmain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code --translate kotlin|csharp} as the CLI runs it: the modification analysis with nullability, the nullability
 * pass ({@code AnalysisEngine.nullabilityFacts}), and the translators with its facts. A field that is never assigned
 * may hold null; one assigned from a constructor parameter that every caller gives a value is non-null.
 */
public class TestTranslateWithFacts {

    private static Path project(Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("p"));
        Files.writeString(src.resolve("p/Box.java"), """
                package p;
                public class Box {
                    private final String name;
                    private String label;
                    public Box(String name) { this.name = name; }
                    public String name() { return name; }
                    public String label() { return label; }
                    public void setLabel(String label) { this.label = label; }
                    public static int use() {
                        Box box = new Box("box");
                        box.setLabel(null);
                        return box.name().length();
                    }
                }
                """);
        return src;
    }

    private static int translate(Path src, String language, Path out) throws Exception {
        return Main.execute(new String[]{
                "--" + Main.SOURCE, src.toString(),
                "--" + Main.JMOD, "java.base",
                "--" + Main.TRANSLATE, language,
                "--" + Main.TRANSLATE_OUTPUT, out.toString()});
    }

    @Test
    public void kotlin(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("kotlin");
        assertEquals(Main.EXIT_OK, translate(project(tmp), Main.TRANSLATE_KOTLIN, out));
        String box = Files.readString(out.resolve("p/Box.kt"));
        assertTrue(box.contains("label: String?"), box);
        assertTrue(box.contains("name: String") && !box.contains("name: String?"), box);
    }

    @Test
    public void csharp(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("csharp");
        assertEquals(Main.EXIT_OK, translate(project(tmp), Main.TRANSLATE_CSHARP, out));
        String box = Files.readString(out.resolve("p/Box.cs"));
        // the accessor pair is a property
        assertTrue(box.contains("string? Label { get; set; }"), box);
        assertTrue(box.contains("string Name { get; }"), box);
        assertTrue(Files.readString(out.resolve("Translated.csproj")).contains("<Nullable>enable</Nullable>"));
    }
}
