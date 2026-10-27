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

package io.codelaser.maddi.aapi.parser.archive;

import io.codelaser.maddi.aapi.parser.CommonTest;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestJavaUtilRegex extends CommonTest {

    // A compiled Pattern is a final, thread-safe immutable value.
    @Test
    public void testPatternImmutable() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Pattern.class);
        assertSame(IMMUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE));
        assertSame(INDEPENDENT, typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT));
        assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }

    // MatchResult is a read-only view: a @Container, but not immutable (Matcher implements it).
    @Test
    public void testMatchResultContainer() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(MatchResult.class);
        assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }

    // Matcher is deliberately NOT a @Container: appendReplacement/appendTail modify their
    // StringBuffer/StringBuilder argument.
    @Test
    public void testMatcherNotContainer() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Matcher.class);
        assertSame(FALSE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }
}
