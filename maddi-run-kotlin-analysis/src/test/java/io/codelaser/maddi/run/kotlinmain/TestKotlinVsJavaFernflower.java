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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.modification.common.util.Culprits;
import io.codelaser.maddi.run.config.ShippedAnalysisResults;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.util.corpus.Corpora;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * <b>The Kotlin front end's precision, measured against Java's on the same program.</b> Fernflower's main sources
 * are analysed twice with the same runner and options: as the Java they are, and as maddi's Kotlin translation of them
 * (maddi-mod's TestJavaToKotlinFernflowerNullability, whose 199 files all compile to class files: a drop-in
 * replacement). The tests stay Java in both. The verdicts are dumped per element and compared.
 *
 * <p>A placeholder census sees only what the front end could not convert; it is blind to a conversion that is well
 * formed but says something the source does not. This sees those, wherever they change a verdict: a method that is
 * non-modifying in Java but modifying in Kotlin is the Kotlin side losing precision (or, now and then, the printer
 * translating to something that does modify). Elements present on one side only are the two front ends naming the same
 * member differently (a property's accessors, a companion's members), and are counted apart.
 *
 * <p>Needs {@code -Dmaddi.j2kSources=<dir>}: the translation's {@code src/} directory (build/j2k/fernflower-nullability
 * of that test). Without it, skipped. Writes {@code build/kotlin-vs-java/fernflower/}: both sides' dumps and
 * {@code report.txt}; {@link VerdictComparison} pairs the two front ends' names and sorts the differences.
 * {@code -Dmaddi.kotlinVsJava.dumps=<dir>} instead compares the four dumps of an earlier run in that directory,
 * without analysing anything (seconds, not half an hour). A measurement, not yet a ratchet.
 */
@Tag("slow")
public class TestKotlinVsJavaFernflower {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestKotlinVsJavaFernflower.class);

    private static final String CORPUS = "fernflower";
    private static final String MAIN_SET = "fernflower/main";
    private static final String RATCHET = "/kotlin-vs-java/" + CORPUS + ".ratchet";
    private static final List<String> JDK_ANNOTATED_APIS = ShippedAnalysisResults.available(TestKotlinVsJavaFernflower.class);

    @Test
    public void verdictsAgree() throws IOException {
        String dumps = System.getProperty("maddi.kotlinVsJava.dumps");
        if (dumps != null && !dumps.isBlank()) {
            Path dir = Path.of(dumps);
            assertRatchet(dir, writeReport(dir, List.of(), List.of(), dir.resolve("report.txt")));
            return;
        }
        String sources = System.getProperty("maddi.j2kSources");
        Assumptions.assumeTrue(sources != null && !sources.isBlank(), "no -Dmaddi.j2kSources");
        Path out = Path.of("build/kotlin-vs-java", CORPUS);
        Files.createDirectories(out);
        Path config = Corpora.oss(CORPUS).requireCompleteConfig();
        ObjectMapper mapper = JsonStreaming.objectMapper();

        InputConfigurationImpl java = mapper.readValue(config.toFile(), InputConfigurationImpl.class);
        // the same configuration, the main set's sources replaced by their translation
        JsonNode tree = mapper.readTree(config.toFile());
        int replaced = 0;
        for (JsonNode set : tree.get("sourceSets")) {
            if (MAIN_SET.equals(set.path("name").asText())) {
                ArrayNode dirs = mapper.createArrayNode().add(Path.of(sources).toAbsolutePath().toString());
                ((ObjectNode) set).set("sourceDirectories", dirs);
                replaced++;
            }
        }
        if (replaced != 1) throw new IllegalStateException("expected one source set " + MAIN_SET + ", found " + replaced);
        addKotlinStdlib(mapper, tree);
        InputConfigurationImpl kotlin = mapper.treeToValue(tree, InputConfigurationImpl.class);

        // explain.txt: culprits on both sides; set here because the forked test JVM need not see the environment
        if (Boolean.getBoolean("maddi.kotlinVsJava.culprits")) Culprits.ENABLED = true;
        RunMixedPrepAnalyzer.Summary javaSummary = run(java, out, "java");
        RunMixedPrepAnalyzer.Summary kotlinSummary = run(kotlin, out, "kotlin");
        List<String> actual = writeReport(out, List.of("java:   " + javaSummary, "kotlin: " + kotlinSummary),
                List.of(side("java", javaSummary), side("kotlin", kotlinSummary)), out.resolve("report.txt"));
        assertRatchet(out, actual);
    }

    /** The per-side numbers the ratchet holds: the rest of a summary moves with unrelated work. */
    private static String side(String name, RunMixedPrepAnalyzer.Summary s) {
        return name + " primaryTypes=" + s.primaryTypes() + " placeholders=" + s.placeholders() + " prepErrors=" + s.prepErrors();
    }

    /**
     * Two-sided, as {@link CensusRatchet}: a new difference fails, and so does a recorded one that is gone, so that the
     * file is tightened in the commit that earns it. The actual lines are written next to the report, ready to copy.
     */
    private static void assertRatchet(Path dir, List<String> actual) throws IOException {
        Path written = dir.resolve(CORPUS + ".ratchet.actual");
        Files.write(written, actual);
        List<String> recorded;
        try (InputStream in = TestKotlinVsJavaFernflower.class.getResourceAsStream(RATCHET)) {
            if (in == null) throw new IllegalStateException("no " + RATCHET + " on the test class path");
            recorded = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        String diff = VerdictComparison.ratchetDiff(recorded, actual);
        if (!diff.isEmpty()) {
            fail("Kotlin vs Java on " + CORPUS + " moved from " + RATCHET + " (actual lines in " + written.toAbsolutePath()
                 + "):\n" + diff);
        }
    }

    /**
     * kotlin-stdlib on the Kotlin side's class path, as kotlinc had it when it compiled the translation. ⛔ The Java
     * configuration has none, and without it K2 resolves none of the stdlib: no `@JvmStatic` (so no static forwarders,
     * and the Java tests calling `ConstExprent.trimFloat` or `InterpreterUtil.getBytes` were dropped, taking their
     * modifications with them), no `use`/`apply`/`toInt` (placeholders, members without a verdict). The test set,
     * Java in both runs, gets it too, as a Gradle build would.
     */
    private static void addKotlinStdlib(ObjectMapper mapper, JsonNode tree) {
        String jar = Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(p -> p.matches(".*kotlin-stdlib-[0-9][^/]*\\.jar$")).findFirst()
                .orElseThrow(() -> new IllegalStateException("kotlin-stdlib jar not on the test classpath"));
        String name = Path.of(jar).getFileName().toString();
        ObjectNode part = mapper.createObjectNode();
        part.put("name", name);
        part.put("uri", Path.of(jar).toUri().toString());
        part.put("library", true);
        part.put("externalLibrary", true);
        part.set("restrictToPackages", mapper.createArrayNode());
        ((ArrayNode) tree.get("classPathParts")).add(part);
        for (JsonNode set : tree.get("sourceSets")) {
            if (set.path("name").asText().startsWith(CORPUS + "/")) ((ArrayNode) set.get("dependencies")).add(name);
        }
        LOGGER.info("Kotlin side: {} on the class path", name);
    }

    /** Writes the report; returns the lines the ratchet compares. */
    private static List<String> writeReport(Path dir, List<String> header, List<String> sides, Path target) throws IOException {
        Map<String, String> javaTypes = VerdictComparison.read(dir.resolve("java-types.txt"), true, false);
        Map<String, String> kotlinTypes = VerdictComparison.read(dir.resolve("kotlin-types.txt"), true, true);
        Map<String, String> javaMembers = VerdictComparison.read(dir.resolve("java-members.txt"), false, false);
        Map<String, String> kotlinMembers = VerdictComparison.read(dir.resolve("kotlin-members.txt"), false, true);
        Set<String> kotlinFields = VerdictComparison.only(kotlinMembers, "F").keySet();
        Set<String> javaTypeNames = javaTypes.keySet();
        List<VerdictComparison.Outcome> outcomes = new ArrayList<>();
        outcomes.add(VerdictComparison.compare("T", javaTypes, kotlinTypes, kotlinFields, javaTypeNames));
        for (String kind : List.of("F", "M", "P")) {
            outcomes.add(VerdictComparison.compare(kind, VerdictComparison.only(javaMembers, kind),
                    VerdictComparison.only(kotlinMembers, kind), kotlinFields, javaTypeNames));
        }
        List<String> report = new ArrayList<>(header);
        report.addAll(VerdictComparison.report(outcomes));
        Files.write(target, report);
        report.stream().filter(l -> !l.startsWith("  ")).forEach(l -> LOGGER.info("{}", l));
        writeExplanations(dir, outcomes);
        return VerdictComparison.ratchetLines(sides, outcomes);
    }

    /**
     * For every member whose verdict differs, the culprits of the side that judged it modified, both sides shown:
     * {@code explain.txt}. Present only when the run recorded culprits (environment {@code CULPRITS} set).
     */
    private static void writeExplanations(Path dir, List<VerdictComparison.Outcome> outcomes) throws IOException {
        Path javaFile = dir.resolve("java-culprits.txt");
        Path kotlinFile = dir.resolve("kotlin-culprits.txt");
        if (!Files.exists(javaFile) || !Files.exists(kotlinFile)) return;
        Map<String, List<String>> java = VerdictComparison.readCulprits(Files.readAllLines(javaFile), false);
        Map<String, List<String>> kotlin = VerdictComparison.readCulprits(Files.readAllLines(kotlinFile), true);
        List<String> lines = new ArrayList<>();
        for (VerdictComparison.Outcome o : outcomes) {
            if ("T".equals(o.kind())) continue;
            for (String c : List.of(VerdictComparison.STRONGER, VerdictComparison.WEAKER)) {
                for (String e : o.categories().get(c)) {
                    String key = o.kind() + " " + e.substring(e.lastIndexOf("  ") + 2);
                    lines.add(c + "  " + e);
                    lines.add("  java:");
                    java.getOrDefault(key, List.of("  (no culprit)")).forEach(st -> lines.add("  " + st));
                    lines.add("  kotlin:");
                    kotlin.getOrDefault(key, List.of("  (no culprit)")).forEach(st -> lines.add("  " + st));
                }
            }
        }
        Files.write(dir.resolve("explain.txt"), lines);
    }

    private static RunMixedPrepAnalyzer.Summary run(InputConfigurationImpl config, Path out, String side) throws IOException {
        System.setProperty("maddi.verdictDump", out.resolve(side + "-types.txt").toString());
        System.setProperty("maddi.memberVerdictDump", out.resolve(side + "-members.txt").toString());
        System.setProperty("maddi.memberCulpritDump", out.resolve(side + "-culprits.txt").toString());
        try {
            // with the reachability pass, as the analysis engine and the IDE daemon run (production verdicts)
            return new RunMixedPrepAnalyzer().go(config, new RunMixedPrepAnalyzer.Options(true, JDK_ANNOTATED_APIS,
                    false, false, null, false, false, true));
        } finally {
            System.clearProperty("maddi.verdictDump");
            System.clearProperty("maddi.memberVerdictDump");
            System.clearProperty("maddi.memberCulpritDump");
        }
    }
}
