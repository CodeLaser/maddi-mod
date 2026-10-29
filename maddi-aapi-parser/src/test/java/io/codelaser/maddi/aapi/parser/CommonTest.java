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

package io.codelaser.maddi.aapi.parser;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.codelaser.maddi.modification.common.defaults.ShallowAnalyzer;
import io.codelaser.maddi.annotation.Immutable;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.integration.JavaInspectorFactory;
import io.codelaser.maddi.inspection.api.resource.CompiledTypesManager;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.graph.G;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.JUnitException;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CommonTest {
    private static CompiledTypesManager compiledTypesManager;

    static List<TypeInfo> allTypes;
    static AnalysisHintsParser hintsParser;
    static List<TypeInfo> sorted;
    static G<TypeInfo> graph;
    private static Runtime runtime;
    private static JavaInspector javaInspector;

    public static CompiledTypesManager compiledTypesManager() {
        return compiledTypesManager;
    }

    public static Runtime runtime() {
        return runtime;
    }

    public static SourceSet mainSources() {
        return javaInspector.mainSources();
    }

    @BeforeAll
    public static void beforeAll() throws IOException {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((Logger) LoggerFactory.getLogger("io.codelaser.maddi.aapi")).setLevel(Level.DEBUG);

        AnalysisHintsParser analysisHintsParser = createAnalysisHintsParser();
        hintsParser = analysisHintsParser;
        AnalysisHints test = new AnalysisHints.Builder()
                .setLibraryName("test")
                .setAnalysisResultsDir(Path.of("build/"))
                .setHintsPath(Path.of("../../maddi/maddi-aapi-archive/src/main/java"))
                .setPackagePrefix("io.codelaser.maddi.aapi.archive")
                .build();

        javaInspector = analysisHintsParser.go(test);
        runtime = javaInspector.runtime();
        compiledTypesManager = javaInspector.compiledTypesManager();

        ShallowAnalyzer shallowAnalyzer = new ShallowAnalyzer(runtime, analysisHintsParser, true);
        ShallowAnalyzer.Result sr = shallowAnalyzer.go(analysisHintsParser.types());

        sorted = sr.sorted();
        graph = sr.typeGraph();
        allTypes = sr.allTypes();
    }

    static @NonNull AnalysisHintsParser createAnalysisHintsParser() {
        // the AAPI archive covers all JDK modules, including java.desktop (swing/awt) and java.net.http, so this
        // compiler needs them on the classpath (the lean default only carries java.base)
        JavaInspectorFactory javaInspectorFactory = io.codelaser.maddi.modification.common.CommonTest
                .javaInspectorFactory("java.desktop", "java.net.http", "java.logging");
        return new AnalysisHintsParser(javaInspectorFactory);
    }

    protected void testImmutableContainer(TypeInfo typeInfo, boolean hcImmutable) {
        Value.Immutable immutable = typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
        Value.Immutable expectImmutable = hcImmutable
                ? ValueImpl.ImmutableImpl.IMMUTABLE_HC : ValueImpl.ImmutableImpl.IMMUTABLE;
        assertSame(expectImmutable, immutable);

        Value.Independent independent = typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT);
        assertSame(ValueImpl.IndependentImpl.INDEPENDENT, independent);

        boolean container = typeInfo.analysis().getOrDefault(CONTAINER_TYPE, ValueImpl.BoolImpl.FALSE).isTrue();
        assertTrue(container);
    }
}
