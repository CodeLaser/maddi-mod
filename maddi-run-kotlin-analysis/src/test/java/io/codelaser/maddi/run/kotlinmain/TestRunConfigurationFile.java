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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.run.config.Configuration;
import io.codelaser.maddi.run.config.GeneralConfiguration;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.run.config.report.ExitCode;

/**
 * {@link RunConfigurationFile} is the entry point a build-tool plugin forks: it hands over the whole
 * {@link Configuration} it computed, as JSON, instead of re-encoding it as command-line options. This drives it on
 * the mixed fixture of {@link TestMixedMain} and checks that the run took the mixed path and honoured the general
 * configuration (the results directory), and that a file it cannot read is an IO error, not a crash.
 */
public class TestRunConfigurationFile {

    @Test
    public void runsTheMixedAnalysisFromAFullConfiguration(@TempDir Path tmp) throws Exception {
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
        Path out = tmp.resolve("results");
        Configuration configuration = new Configuration.Builder()
                .setGeneralConfiguration(new GeneralConfiguration.Builder()
                        .setAnalysisResultsDir(out.toString()).build())
                .setInputConfiguration(new InputConfigurationImpl.Builder()
                        .addSourceSets(kotlinSet).addSourceSets(javaSet).build())
                .build();
        File configFile = tmp.resolve("configuration.json").toFile();
        JsonStreaming.objectMapper().writerFor(Configuration.class).writeValue(configFile, configuration);

        assertEquals(ExitCode.OK, RunConfigurationFile.execute(new String[]{configFile.getAbsolutePath()}));
        try (var walk = Files.walk(out)) {
            List<Path> written = walk.filter(Files::isRegularFile).toList();
            assertEquals(2, written.size(), "expected one file per package (a, b), got " + written);
        }
    }

    @Test
    public void anUnreadableFileIsAnIoError(@TempDir Path tmp) {
        assertEquals(ExitCode.IO_EXCEPTION, RunConfigurationFile.execute(
                new String[]{tmp.resolve("absent.json").toString()}));
    }

    @Test
    public void aMissingArgumentIsRefused() {
        assertEquals(ExitCode.INTERNAL_EXCEPTION, RunConfigurationFile.execute(new String[0]));
    }
}
