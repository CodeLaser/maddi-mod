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

import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link RunMixedPrepAnalyzer} end to end on a tiny mixed Java+Kotlin project written to disk: a Kotlin
 * {@code Foo} and a Java {@code UseFoo} that references it. Proves the prep-only mixed pipeline parses both
 * languages and produces a non-empty analysis order.
 */
public class TestRunMixedPrepAnalyzer {

    @Test
    public void prepAnalyzesAMixedProject(@TempDir Path tmp) throws Exception {
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

        RunMixedPrepAnalyzer.Summary summary = new RunMixedPrepAnalyzer().go(config);

        assertTrue(summary.kotlinTypes() >= 1, "expected at least one Kotlin type, got " + summary);
        assertTrue(summary.javaTypes() >= 1, "expected at least one Java type, got " + summary);
        assertTrue(summary.analysisOrderSize() > 0, "expected a non-empty analysis order, got " + summary);
    }
}
