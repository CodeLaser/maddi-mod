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

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.run.config.util.JavaModules;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The CLI loads the analysis results maddi ships without being asked (CodeLaser/maddi-mod#15). The same project, run
 * twice through {@code bin/maddi-kotlin}'s entry point: by default, and with {@code --preload-analysis-results-dirs
 * none}. Without the results `xs.sum()` is an uncontracted call and modifies `xs`.
 */
public class TestShippedResultsLoaded {

    @Test
    public void byDefault(@TempDir Path tmp) throws Exception {
        // the stdlib's contracts make `xs.sum()` read-only; without any results it is an uncontracted call
        assertEquals("\"unmodifiedField\":1", fieldXs(run(tmp.resolve("default"))));
        assertEquals("", fieldXs(run(tmp.resolve("none"), "--preload-analysis-results-dirs", "none")));
    }

    private static String fieldXs(String results) {
        int i = results.indexOf("\"Fxs(0)\", \"data\":{");
        String data = results.substring(i, results.indexOf('}', i));
        return data.contains("\"unmodifiedField\":1") ? "\"unmodifiedField\":1" : "";
    }

    private static String run(Path dir, String... extra) throws Exception {
        Path kDir = dir.resolve("src/main/kotlin");
        Files.createDirectories(kDir.resolve("a"));
        Files.writeString(kDir.resolve("a/Foo.kt"), "package a\nclass Foo(private val xs: List<Int>) { fun total(): Int = xs.sum() }\n");
        // the stdlib as a build tool hands it over: a library the source set depends on, and a class path part
        Path jar = Path.of(kotlinStdlibJar());
        SourceSet stdlib = new SourceSetImpl.Builder().setName(jar.getFileName().toString())
                .setExternalLibrary(true).setLibrary(true).setUri(jar.toUri()).build();
        SourceSet kotlinSet = new SourceSetImpl.Builder().setName("kotlin/main")
                .setSourceDirectories(List.of(kDir)).setUri(kDir.toUri()).setDependencies(List.of(stdlib)).build();
        File configFile = dir.resolve("input-configuration.json").toFile();
        JsonStreaming.objectMapper().writerFor(InputConfigurationImpl.class).writeValue(configFile,
                new InputConfigurationImpl.Builder().addSourceSets(kotlinSet)
                        .addClassPathParts(JavaModules.javaModuleSourceSets("java.base")).addClassPathParts(stdlib).build());
        Path out = dir.resolve("results");
        String[] args = Stream.concat(Stream.of("--input-configuration", configFile.getAbsolutePath(),
                "--analysis-steps", "modification", "--analysis-results-dir", out.toString()), Stream.of(extra))
                .toArray(String[]::new);
        assertEquals(0, Main.execute(args));
        try (Stream<Path> walk = Files.walk(out)) {
            return walk.filter(Files::isRegularFile).map(p -> {
                try { return Files.readString(p); } catch (Exception e) { throw new RuntimeException(e); }
            }).filter(s -> s.contains("a.Foo")).collect(Collectors.joining("\n"));
        }
    }

    private static String kotlinStdlibJar() {
        String cp = System.getProperty("maddi.k2.classpath", "");
        return Stream.of(cp.split(File.pathSeparator))
                .filter(p -> Path.of(p).getFileName().toString().matches("kotlin-stdlib-\\d[^-]*\\.jar"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no kotlin-stdlib jar on -Dmaddi.k2.classpath (" + cp + ")"));
    }
}
