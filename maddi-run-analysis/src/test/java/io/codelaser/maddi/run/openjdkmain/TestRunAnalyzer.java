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

import ch.qos.logback.classic.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
public class TestRunAnalyzer {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.shallow")).setLevel(Level.DEBUG);
    }

    @Test
    public void test() throws IOException {
        Path cstApiPath = Path.of("../../maddi/maddi-cst-api/src/main/java").toRealPath();
        assertTrue(Files.isDirectory(cstApiPath));

        // located by glob rather than by version: a hard-coded "maddi-support-<version>.jar" breaks on
        // every release bump (see gradle.properties), and toRealPath() then fails with NoSuchFileException
        Path maddiSupportJar = findMaddiSupportJar();

        Main.main(new String[]{
                "--debug=classpath",
                "--jmod=java.base",
                "--classpath=" + maddiSupportJar,
                "--source=" + cstApiPath,
                "--analysis-steps=prep",
        });
    }

    private static Path findMaddiSupportJar() throws IOException {
        Path libs = Path.of("../../maddi/maddi-support/build/libs").toRealPath();
        try (Stream<Path> stream = Files.list(libs)) {
            return stream
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("maddi-support-") && n.endsWith(".jar")
                               && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
                    })
                    .findFirst()
                    .orElseThrow(() -> new IOException("no maddi-support jar in " + libs
                                                       + "; run :maddi-support:jar first"));
        }
    }
}
