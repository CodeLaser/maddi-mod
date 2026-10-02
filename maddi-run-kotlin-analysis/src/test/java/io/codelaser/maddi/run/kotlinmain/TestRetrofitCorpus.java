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

package io.codelaser.maddi.run.kotlinmain;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.mixed.MixedProjectInspector;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.util.corpus.Corpora;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MIXED corpus: Java and Kotlin inside the same source sets, which neither {@link TestDetektCorpus} nor
 * {@link TestCoilJvmSlice} has — both are pure Kotlin.
 *
 * <p>retrofit 3.0.0 is a Java library with one Kotlin file in its core module, and the calls cross that boundary
 * in both directions within ONE source set: Java's {@code HttpServiceMethod} calls the {@code @file:JvmName
 * ("KotlinExtensions")} facade ({@code await}, {@code awaitNullable}, {@code awaitUnit}, {@code awaitResponse},
 * {@code suspendAndThrow}), and those extension functions call back into Java's {@code Call} and
 * {@code Callback}. Three source sets are mixed (retrofit, retrofit-mock, samples), so
 * {@code MixedProjectInspector} takes its interleaved path here and nowhere else in the corpus.
 *
 * <p>The configuration comes from the {@code --compile-log} route (22 javac + 6 kotlinc invocations, 24 source
 * sets); see {@code corpus/catalogue/retrofit.yml} in maddi-mod, including the init script that moves
 * retrofit's JDK 8 / Azul 14 / Azul 16 toolchains to one installed JDK.
 */
@Tag("slow")
public class TestRetrofitCorpus {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestRetrofitCorpus.class);

    private static final String CORPUS = "retrofit";

    /** Floors, not exact counts, so a retrofit version bump does not make this brittle. */
    private static final int JAVA_TYPE_FLOOR = 100;
    private static final int KOTLIN_TYPE_FLOOR = 5;

    private static Path config() {
        return Corpora.oss(CORPUS).requireCompleteConfig();
    }

    private static InputConfigurationImpl read(Path config) throws IOException {
        return JsonStreaming.objectMapper().readValue(config.toFile(), InputConfigurationImpl.class);
    }

    /**
     * Both front ends contribute, and the two halves of the boundary are each parsed by their own: the Java
     * caller by javac, the facade it calls by K2. A configuration that classified retrofit/main as Java-only
     * would still parse — and lose the facade without a word.
     */
    @Test
    public void parsesBothLanguagesOfOneSourceSet() throws IOException {
        MixedProjectInspector.Result result = new MixedProjectInspector().parse(read(config()));
        LOGGER.info("retrofit, mixed parse: {} Kotlin + {} Java type(s)",
                result.getKotlinTypes().size(), result.getJavaTypes().size());
        assertTrue(result.getJavaTypes().size() >= JAVA_TYPE_FLOOR,
                "expected at least " + JAVA_TYPE_FLOOR + " Java types, got " + result.getJavaTypes().size());
        assertTrue(result.getKotlinTypes().size() >= KOTLIN_TYPE_FLOOR,
                "expected at least " + KOTLIN_TYPE_FLOOR + " Kotlin types, got " + result.getKotlinTypes().size());

        Set<String> java = names(result.getJavaTypes());
        Set<String> kotlin = names(result.getKotlinTypes());
        assertTrue(java.contains("retrofit2.HttpServiceMethod"), "the Java caller is missing");
        assertTrue(kotlin.contains("retrofit2.KotlinExtensions"),
                "the Kotlin facade the Java side calls is missing; Kotlin types: " + kotlin);
    }

    private static Set<String> names(List<TypeInfo> types) {
        return types.stream().map(TypeInfo::fullyQualifiedName).collect(Collectors.toSet());
    }

    /** As in {@link TestDetektCorpus}: without them nothing built on a library type can be concluded immutable. */
    private static final List<String> JDK_ANNOTATED_APIS = List.of(
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk",
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/libs/kotlin");

    /**
     * Prep and the modification analysis over the whole project. The floors say "did this run at all"; the
     * ratchets are the quantities a change moves — see {@link CensusRatchet}.
     */
    @Test
    public void runsModificationAnalysis() throws IOException {
        RunMixedPrepAnalyzer.Summary summary =
                new RunMixedPrepAnalyzer().go(read(config()), true, JDK_ANNOTATED_APIS);
        LOGGER.info("retrofit modification: {} Kotlin + {} Java type(s), {} primary, analysis order {}, {} isolated"
                    + " by prep, {} immutable type(s), {} placeholder(s)", summary.kotlinTypes(),
                summary.javaTypes(), summary.primaryTypes(), summary.analysisOrderSize(), summary.prepErrors(),
                summary.immutableTypes(), summary.placeholders());
        assertTrue(summary.javaTypes() >= JAVA_TYPE_FLOOR && summary.kotlinTypes() >= KOTLIN_TYPE_FLOOR,
                "expected both languages, got " + summary.javaTypes() + " Java + " + summary.kotlinTypes() + " Kotlin");
        assertTrue(summary.immutableTypes() > 0,
                "no immutable types at all; that means the annotated APIs were not loaded");

        // Measured 2026-10-01 at the pin, on the first run: 14 Kotlin + 168 Java types (180 primary), analysis
        // order 1,709, 3 placeholders, 0 isolated by prep, 62 immutable types.
        CensusRatchet.noWorseThan("retrofit placeholders", summary.placeholders(), 3);
        CensusRatchet.noWorseThan("retrofit elements isolated by prep", summary.prepErrors(), 0);
        CensusRatchet.noWorseThanAtLeast("retrofit immutable types", summary.immutableTypes(), 62);
    }
}
