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

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.runtime.RuntimeImpl;
import io.codelaser.maddi.inspection.kotlin.KotlinInspector;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The third Kotlin/JVM corpus, for VOLUME in a style detekt does not write.
 *
 * <p>Exposed 1.5.0 (JetBrains' SQL library) is, like detekt, a plain multi-module Gradle build configured through
 * the {@code --compile-log} route: 24 kotlinc invocations, 24 source sets, 309 files / ~50k lines. Where detekt
 * is visitor code over PSI, Exposed is a DSL: operator overloads on column expressions, extension functions on
 * tables and transactions, deep generic column types ({@code Column<T>}, {@code ExpressionWithColumnType<T>}).
 * Its one Java file is the Maven plugin's generated HelpMojo, so exactly one source set is mixed.
 */
@Tag("slow")
public class TestExposedCorpus {
    private static final Logger LOGGER = LoggerFactory.getLogger(TestExposedCorpus.class);

    private static final String CORPUS = "exposed";

    /** Floors, not exact counts, so an Exposed version bump does not make this brittle. */
    private static final int PRIMARY_TYPE_FLOOR = 500;
    private static final int SOURCE_SET_FLOOR = 20;

    private static Path config() {
        return Corpora.oss(CORPUS).requireCompleteConfig();
    }

    private static InputConfigurationImpl read(Path config) throws IOException {
        return JsonStreaming.objectMapper().readValue(config.toFile(), InputConfigurationImpl.class);
    }

    /** The pure-Kotlin path over the whole project, independent of the Java-stub machinery. */
    @Test
    public void parsesViaTheKotlinInspector() throws IOException {
        KotlinInspector inspector = new KotlinInspector(new RuntimeImpl());
        inspector.initialize(read(config()));

        Map<SourceSet, List<TypeInfo>> bySourceSet = inspector.parseFromConfiguration();
        int primaryTypes = bySourceSet.values().stream().mapToInt(List::size).sum();
        LOGGER.info("exposed: {} primary type(s) over {} source set(s)", primaryTypes, bySourceSet.size());
        assertTrue(bySourceSet.size() >= SOURCE_SET_FLOOR,
                "expected at least " + SOURCE_SET_FLOOR + " source sets, got " + bySourceSet.size());
        assertTrue(primaryTypes >= PRIMARY_TYPE_FLOOR,
                "expected at least " + PRIMARY_TYPE_FLOOR + " primary types, got " + primaryTypes);
    }

    /** The mixed parse, which is what the shipping CLI runs. */
    @Test
    public void parsesViaTheMixedProjectInspector() throws IOException {
        MixedProjectInspector.Result result = new MixedProjectInspector().parse(read(config()));
        LOGGER.info("exposed, mixed parse: {} Kotlin + {} Java type(s)",
                result.getKotlinTypes().size(), result.getJavaTypes().size());
        assertTrue(result.getKotlinTypes().size() >= PRIMARY_TYPE_FLOOR,
                "expected at least " + PRIMARY_TYPE_FLOOR + " Kotlin types, got " + result.getKotlinTypes().size());
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
        LOGGER.info("exposed modification: {} primary type(s), analysis order {}, {} isolated by prep,"
                    + " {} immutable type(s), {} placeholder(s)", summary.primaryTypes(),
                summary.analysisOrderSize(), summary.prepErrors(), summary.immutableTypes(), summary.placeholders());
        assertTrue(summary.primaryTypes() >= PRIMARY_TYPE_FLOOR,
                "expected at least " + PRIMARY_TYPE_FLOOR + " primary types, got " + summary.primaryTypes());
        assertTrue(summary.immutableTypes() > 0,
                "no immutable types at all; that means the annotated APIs were not loaded");

        // Measured 2026-10-01 at the pin, on the first run that parsed at all -- after seven front-end fixes the corpus
        // found (a member extension beside a same-named top-level one; a packed `vararg xs: T`; a `suspend` override
        // under class delegation; a mixed set downstream of a Kotlin-only set; a non-trailing vararg in a stub; an
        // override's boxed return; kotlinc's bridges and DefaultImpls forwarders in stubs): 763 primary types, analysis order 15,817,
        // 243 placeholders, 0 isolated by prep, 248 immutable types. The placeholders are the backlog, as detekt's
        // 6,057 were.
        CensusRatchet.noWorseThan("exposed placeholders", summary.placeholders(), 243);
        CensusRatchet.noWorseThan("exposed elements isolated by prep", summary.prepErrors(), 0);
        CensusRatchet.noWorseThanAtLeast("exposed immutable types", summary.immutableTypes(), 248);
    }
}
