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

package io.codelaser.maddi.run.kotlinmain;

import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives the {@link Main} CLI through {@code --input-configuration} on a tiny mixed Java+Kotlin project written to
 * disk: it must derive an {@link InputConfiguration}, run the prep-only mixed analysis and exit cleanly.
 */
public class TestMixedMain {

    @Test
    public void runsMixedPrepFromAnInputConfiguration(@TempDir Path tmp) throws Exception {
        Path kDir = tmp.resolve("src/main/kotlin");
        Path jDir = tmp.resolve("src/main/java");
        Files.createDirectories(kDir.resolve("a"));
        Files.createDirectories(jDir.resolve("b"));
        Files.writeString(kDir.resolve("a/Foo.kt"), "package a\nclass Foo(val id: Int)\n");
        Files.writeString(jDir.resolve("b/UseFoo.java"),
                "package b;\npublic class UseFoo {\n    public a.Foo foo;\n}\n");

        SourceSet kotlinSet = new SourceSetImpl.Builder().setName("kotlin/main")
                .setSourceDirectories(List.of(kDir)).setUri(kDir.toUri()).build();
        SourceSet javaSet = new SourceSetImpl.Builder().setName("java/main")
                .setSourceDirectories(List.of(jDir)).setUri(jDir.toUri())
                .setDependencies(List.of(kotlinSet)).build();
        InputConfiguration config = new InputConfigurationImpl.Builder()
                .addSourceSets(kotlinSet).addSourceSets(javaSet).build();

        File configFile = tmp.resolve("input-configuration.json").toFile();
        JsonStreaming.objectMapper().writerFor(InputConfigurationImpl.class).writeValue(configFile, config);

        int exit = Main.execute(new String[]{"--input-configuration", configFile.getAbsolutePath()});

        assertEquals(Main.EXIT_OK, exit);
    }

    /**
     * ⭐ The option that was REFUSED until 2026-09-22 (`--analysis-results-dir` was on the mixed CLI's
     * unsupported list, exit {@value io.codelaser.maddi.run.main.ExitCode#UNSUPPORTED_OPTION}). Now the mixed
     * runner writes through {@code LinkCodec}, and this asserts the CLI actually produces a file — the
     * content's readability is {@code TestKotlinAnalysisRoundTrip}'s job.
     *
     * <p>⚠ The negative control is the important half: a run WITHOUT the option must write nothing, or this
     * would pass on a directory something else filled.
     */
    @Test
    public void writesAnalysisResultsWhenAskedTo(@TempDir Path tmp) throws Exception {
        Path kDir = tmp.resolve("src/main/kotlin");
        Path jDir = tmp.resolve("src/main/java");
        Files.createDirectories(kDir.resolve("a"));
        Files.createDirectories(jDir.resolve("b"));
        Files.writeString(kDir.resolve("a/Foo.kt"), "package a\nclass Foo(val id: Int)\n");
        Files.writeString(jDir.resolve("b/UseFoo.java"),
                "package b;\npublic class UseFoo {\n    public a.Foo foo;\n}\n");

        SourceSet kotlinSet = new SourceSetImpl.Builder().setName("kotlin/main")
                .setSourceDirectories(List.of(kDir)).setUri(kDir.toUri()).build();
        SourceSet javaSet = new SourceSetImpl.Builder().setName("java/main")
                .setSourceDirectories(List.of(jDir)).setUri(jDir.toUri())
                .setDependencies(List.of(kotlinSet)).build();
        InputConfiguration config = new InputConfigurationImpl.Builder()
                .addSourceSets(kotlinSet).addSourceSets(javaSet).build();
        File configFile = tmp.resolve("input-configuration.json").toFile();
        JsonStreaming.objectMapper().writerFor(InputConfigurationImpl.class).writeValue(configFile, config);

        Path control = tmp.resolve("control");
        Files.createDirectories(control);
        assertEquals(0, Main.execute(new String[]{"--input-configuration", configFile.getAbsolutePath()}));
        try (var walk = Files.walk(control)) {
            assertEquals(List.of(), walk.filter(Files::isRegularFile).toList(),
                    "a run without --analysis-results-dir must write no results at all");
        }

        Path out = tmp.resolve("results");
        int exit = Main.execute(new String[]{"--input-configuration", configFile.getAbsolutePath(),
                "--analysis-results-dir", out.toString()});
        assertEquals(0, exit, "--analysis-results-dir used to be refused; it must now be honoured");
        try (var walk = Files.walk(out)) {
            List<Path> written = walk.filter(Files::isRegularFile).toList();
            assertEquals(2, written.size(), "expected one file per package (a, b), got " + written);
        }
    }
}
