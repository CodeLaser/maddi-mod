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

import java.math.MathContext;
import java.math.RoundingMode;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestJavaMath extends CommonTest {

    // java.math is a package of immutable value types (operations return new instances, no array-output
    // methods) -> all four stubs are @ImmutableContainer. RoundingMode (enum) and MathContext (final class)
    // are deep @Immutable + @Container.
    // NOTE: BigInteger/BigDecimal are annotated the same way in JavaMath.java, but the openjdk class-file
    // scanner in this test harness cannot load them (AnalysisHintsParser logs "cannot load it", the same
    // limitation that affects TestCloneBench); they are therefore only exercised in production/composition,
    // not asserted here.
    @Test
    public void testRoundingModeAndMathContextImmutableContainer() {
        for (Class<?> c : new Class<?>[]{RoundingMode.class, MathContext.class}) {
            TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(c);
            assertSame(IMMUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE),
                    () -> c.getSimpleName() + " should be deep @Immutable");
            assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE),
                    () -> c.getSimpleName() + " should be a @Container");
        }
    }
}
