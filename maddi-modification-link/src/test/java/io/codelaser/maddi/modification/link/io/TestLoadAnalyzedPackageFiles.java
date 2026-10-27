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

package io.codelaser.maddi.modification.link.io;

import ch.qos.logback.classic.Level;
import io.codelaser.maddi.modification.common.CommonTest;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.NullabilityImpl.UNSPECIFIED;
import static io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults.ANALYZED_RESULTS;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.*;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static org.junit.jupiter.api.Assertions.*;

public class TestLoadAnalyzedPackageFiles {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.shallow")).setLevel(Level.DEBUG);
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("io.codelaser.maddi.modification")).setLevel(Level.DEBUG);
    }

    @Test
    public void test1() throws IOException {
        SourceSet sourceSet = SourceSetImpl.testProtocolSourceSet();
        JavaInspector javaInspector = CommonTest.javaInspectorFactory().withSources(sourceSet);
        javaInspector.onlyPreload();
        LoadAnalysisResults lar = new LoadAnalysisResults(javaInspector.runtime(), sourceSet);
        lar.go(ANALYZED_RESULTS);

        TypeInfo object = javaInspector.compiledTypesManager().typeIfLoaded(Object.class);
        assertNotNull(object);
        MethodInfo objectToString = object.findUniqueMethod("toString", 0);
        // assertSame(TRUE, methodInfo.analysis().getOrDefault(CONTAINER_METHOD, FALSE));
        assertEquals("N", objectToString.analysis().getOrDefault(NULLABILITY_METHOD, UNSPECIFIED).toString());
        assertFalse(objectToString.isModifying());
        assertSame(IMMUTABLE, objectToString.analysis().getOrDefault(IMMUTABLE_METHOD, MUTABLE));
        assertSame(INDEPENDENT, objectToString.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));

        TypeInfo list = javaInspector.compiledTypesManager().typeIfLoaded(List.class);
        MethodInfo listIterator = list.findUniqueMethod("iterator", 0);
        assertEquals("java.lang.Iterable.iterator(), java.util.Collection.iterator()",
                listIterator.overrides().stream().map(Object::toString).sorted()
                        .collect(Collectors.joining(", ")));
        assertFalse(listIterator.allowsInterrupts());
        assertFalse(listIterator.isModifying());

    }
}
