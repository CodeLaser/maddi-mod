package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.util.corpus.Corpora;
import ch.qos.logback.classic.Level;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Tag;

/**
 * Guava core (~350k lines, 20k elements): the generics/immutability-depth corpus — recursive self-bounded
 * generics, deep hidden-content structures. Green since 2026-07-17 (certified fixpoint, ~72s at PARALLEL
 * defaults). Config generated from the whole reactor's compile log (maddi's corpus/catalogue/guava.yml;
 * `task corpus:catalogue:config NAME=guava`); the corpus-root copy carries an absolute workingDirectory.
 */
@Tag("slow")
public class TestGuava {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.shallow")).setLevel(Level.DEBUG);
        ((ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger("io.codelaser.maddi.modification.link.impl.linkgraph.RedundantLinks")).setLevel(Level.ERROR);
    }

    private static final Path CONFIG = Corpora.oss("guava").config();

    @Test
    public void test() throws IOException, ParseException {
        // Ask the locator, do NOT test the file here: only the locator honours
        // -Dmaddi.corpus.required, which slowTest sets so that an absent corpus FAILS instead of
        // skipping. A hand-written assumeTrue reported the same green as a run that analysed the
        // whole corpus -- TestGuava skipped in 9 ms with no guava on disk and the build said
        // SUCCESSFUL (measured 2026-09-30).
        Corpora.oss("guava").requireConfig();
        // GUAVA_RESULTS_DIR=<dir> writes the results too: the source run that libs/guava's hints are composed from
        // (GUAVA.md §Regenerating), at the work ceiling it names. Read before the fixpoint engine's class loads.
        String resultsDir = System.getenv("GUAVA_RESULTS_DIR");
        if (resultsDir != null) System.setProperty("maddi.workCeiling", "30000000");
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "--input-configuration=" + CONFIG
                , "--analysis-steps=modification"
                , "--preload-analysis-results-dirs=../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk"
                // ⚠ REQUIRED SINCE THE CONFIG COVERS guava-tests (corpus commit b1a95656f). That module holds
                // MacHashFunctionTest, which imports sun.security.jca.ProviderList/Providers -- a non-exported
                // java.base package. Without this flag the unit is dropped and the run ends at
                // EXIT_PARSER_ERROR before the analyzer is reached at all, so the test would be guarding the
                // parse and nothing else.
                , "--jdk-internals"));
        if (resultsDir != null) args.add("--analysis-results-dir=" + resultsDir);
        int exitValue = Main.execute(args.toArray(new String[0]));
        assertEquals(Main.EXIT_OK, exitValue);
    }
}
