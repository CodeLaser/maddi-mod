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
