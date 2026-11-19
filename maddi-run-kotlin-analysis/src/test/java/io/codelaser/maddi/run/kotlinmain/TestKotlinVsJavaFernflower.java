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
import io.codelaser.maddi.run.config.ShippedAnalysisResults;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.util.corpus.Corpora;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 * {@code report.txt}. A measurement, not yet a ratchet.
 */
@Tag("slow")
public class TestKotlinVsJavaFernflower {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestKotlinVsJavaFernflower.class);

    private static final String CORPUS = "fernflower";
    private static final String MAIN_SET = "fernflower/main";
    private static final List<String> JDK_ANNOTATED_APIS = ShippedAnalysisResults.available(TestKotlinVsJavaFernflower.class);

    @Test
    public void verdictsAgree() throws IOException {
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
        InputConfigurationImpl kotlin = mapper.treeToValue(tree, InputConfigurationImpl.class);

        RunMixedPrepAnalyzer.Summary javaSummary = run(java, out, "java");
        RunMixedPrepAnalyzer.Summary kotlinSummary = run(kotlin, out, "kotlin");

        List<String> report = new ArrayList<>();
        report.add("java:   " + javaSummary);
        report.add("kotlin: " + kotlinSummary);
        compare(read(out.resolve("java-types.txt"), true), read(out.resolve("kotlin-types.txt"), true), "T", report);
        Map<String, String> javaMembers = read(out.resolve("java-members.txt"), false);
        Map<String, String> kotlinMembers = read(out.resolve("kotlin-members.txt"), false);
        for (String kind : List.of("F", "M", "P")) compare(only(javaMembers, kind), only(kotlinMembers, kind), kind, report);
        Files.write(out.resolve("report.txt"), report);
        report.stream().filter(l -> !l.startsWith("  ")).forEach(l -> LOGGER.info("{}", l));
    }

    private static RunMixedPrepAnalyzer.Summary run(InputConfigurationImpl config, Path out, String side) throws IOException {
        System.setProperty("maddi.verdictDump", out.resolve(side + "-types.txt").toString());
        System.setProperty("maddi.memberVerdictDump", out.resolve(side + "-members.txt").toString());
        try {
            return new RunMixedPrepAnalyzer().go(config, true, JDK_ANNOTATED_APIS);
        } finally {
            System.clearProperty("maddi.verdictDump");
            System.clearProperty("maddi.memberVerdictDump");
        }
    }

    /** `<verdict> <fqn>` (types) or `<kind> <verdict> <key>` (members), by key; a member's kind stays in its key. */
    private static Map<String, String> read(Path dump, boolean types) throws IOException {
        Map<String, String> byKey = new TreeMap<>();
        for (String line : Files.readAllLines(dump)) {
            String[] parts = line.split(" ", types ? 2 : 3);
            if (types) byKey.put(parts[1], parts[0]);
            else byKey.put(parts[0] + " " + parts[2], parts[1]);
        }
        return byKey;
    }

    private static Map<String, String> only(Map<String, String> members, String kind) {
        Map<String, String> result = new TreeMap<>();
        members.forEach((k, v) -> { if (k.startsWith(kind + " ")) result.put(k.substring(2), v); });
        return result;
    }

    private static void compare(Map<String, String> java, Map<String, String> kotlin, String kind, List<String> report) {
        int same = 0;
        List<String> differ = new ArrayList<>();
        List<String> javaOnly = new ArrayList<>();
        for (Map.Entry<String, String> e : java.entrySet()) {
            String k = kotlin.get(e.getKey());
            if (k == null) javaOnly.add(e.getKey());
            else if (k.equals(e.getValue())) same++;
            else differ.add(e.getValue() + " -> " + k + "  " + e.getKey());
        }
        List<String> kotlinOnly = kotlin.keySet().stream().filter(key -> !java.containsKey(key)).toList();
        report.add(kind + ": " + same + " agree, " + differ.size() + " differ, " + javaOnly.size() + " Java only, "
                   + kotlinOnly.size() + " Kotlin only");
        differ.forEach(d -> report.add("  differ " + d));
        javaOnly.forEach(d -> report.add("  java-only " + d));
        kotlinOnly.forEach(d -> report.add("  kotlin-only " + d));
    }
}
