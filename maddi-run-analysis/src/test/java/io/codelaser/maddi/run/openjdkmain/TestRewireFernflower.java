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
import io.codelaser.maddi.util.corpus.Corpora;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewire driver on fernflower, with its measurement and its oracle: the corpus run of
 * {@code maddi/docs/design/incremental-and-overlay.md} §8. Each iteration edits a source file (a leading comment),
 * re-parses incrementally, preps, restores the file and re-parses again; every few iterations the warm state is
 * compared with a cold parse and prep of the same files ({@code ReparseOracle}). The log carries one
 * {@code REWIRE STATS} line per re-parse and a {@code REWIRE SUMMARY} at the end.
 * <p>
 * The driver writes to the files it parses, so it runs on a copy: the sources, the class output the test source set
 * resolves against, and the input configuration with every path re-pointed at the copy.
 * <p>
 * Size and frequency: {@code -Dmaddi.rewire.iterations} (default here 20) and {@code -Dmaddi.rewire.oracleEvery}
 * (default here 5).
 */
@Tag("slow")
public class TestRewireFernflower {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    }

    @Test
    public void test(@TempDir Path tmp) throws Exception {
        Corpora.Corpus fernflower = Corpora.oss("fernflower");
        fernflower.requireConfig();
        // ⛔ THE REAL PATH. The locator may answer through a symbolic link (a workspace links test-oss in), while the
        // configuration holds the real one: a replace on the linked path matched nothing, and the driver then edited
        // the shared corpus itself (2026-11-03; restored, but every touched file was newer than its class file).
        Path original = fernflower.dir().toRealPath();
        Path copy = tmp.resolve("fernflower");
        for (String part : List.of("src", "test", "build/classes/java/main", "build/classes/java/test")) {
            copyTree(original.resolve(part), copy.resolve(part));
        }
        String configuration = Files.readString(fernflower.config()).replace(original.toString(), copy.toString());
        // the driver writes to the files it parses: not one path of the original may survive in the copy's configuration
        assertFalse(configuration.contains(original.toString()), "the copied configuration still names " + original);
        assertTrue(configuration.contains(copy.toString()), "the copied configuration does not name the copy");
        Path copiedConfiguration = copy.resolve("inputConfiguration.json");
        Files.writeString(copiedConfiguration, configuration);

        String iterations = System.getProperty("maddi.rewire.iterations");
        String oracleEvery = System.getProperty("maddi.rewire.oracleEvery");
        System.setProperty("maddi.rewire.iterations", iterations == null ? "20" : iterations);
        System.setProperty("maddi.rewire.oracleEvery", oracleEvery == null ? "5" : oracleEvery);
        try {
            int exitValue = Main.execute(new String[]{
                    "--input-configuration=" + copiedConfiguration,
                    "--analysis-steps=rewire-tests"});
            assertEquals(Main.EXIT_OK, exitValue, "the rewire driver failed, or its oracle found a difference");
        } finally {
            restore("maddi.rewire.iterations", iterations);
            restore("maddi.rewire.oracleEvery", oracleEvery);
        }
    }

    private static void restore(String property, String value) {
        if (value == null) System.clearProperty(property);
        else System.setProperty(property, value);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
    }
}
