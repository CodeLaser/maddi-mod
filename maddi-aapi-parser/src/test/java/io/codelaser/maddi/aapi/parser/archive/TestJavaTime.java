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
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoLocalDate;
import java.time.chrono.ChronoLocalDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAdjuster;
import java.time.temporal.TemporalAmount;
import java.time.temporal.TemporalUnit;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_FIELD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaTime extends CommonTest {

    // The whole java.time family is immutable value types whose methods return new instances and never
    // modify a parameter -> @ImmutableContainer. Final classes/enums land deep @Immutable; the interfaces
    // and abstract ZoneId are extensible, so they land as @Immutable(hc=true). All are containers.
    @Test
    public void testJavaTimeImmutableContainers() {
        for (Class<?> c : new Class<?>[]{
                DayOfWeek.class, Instant.class, LocalDate.class, LocalDateTime.class, ZoneId.class,
                ZonedDateTime.class, ChronoLocalDate.class, ChronoLocalDateTime.class, ChronoZonedDateTime.class,
                ChronoUnit.class, Temporal.class, TemporalAccessor.class, TemporalAdjuster.class,
                TemporalAmount.class, TemporalUnit.class}) {
            TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(c);
            Value.Immutable imm = (Value.Immutable) typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE);
            assertTrue(imm.isAtLeastImmutableHC(), () -> c.getSimpleName() + " should be (at least) @Immutable(hc=true)");
            assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE),
                    () -> c.getSimpleName() + " should be a @Container");
        }
    }

    // A field of an immutable type is itself immutable, derived from the owner: ZoneId.SHORT_IDS is a
    // static final Map constant (formally mutable) inside the immutable ZoneId, and must come out immutable.
    @Test
    public void testZoneIdShortIdsFieldImmutable() {
        TypeInfo zoneId = compiledTypesManager().typeIfLoaded(ZoneId.class);
        FieldInfo shortIds = zoneId.getFieldByName("SHORT_IDS", true);
        Value.Immutable imm = (Value.Immutable) shortIds.analysis().getOrDefault(IMMUTABLE_FIELD, MUTABLE);
        assertTrue(imm.isAtLeastImmutableHC(),
                "SHORT_IDS (a Map constant inside the immutable ZoneId) must itself be immutable");
    }

    // Final concrete value types are deep @Immutable (not the hidden-content variant).
    @Test
    public void testFinalTimeTypesAreDeepImmutable() {
        for (Class<?> c : new Class<?>[]{
                DayOfWeek.class, Instant.class, LocalDate.class, LocalDateTime.class, ChronoUnit.class}) {
            TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(c);
            assertSame(IMMUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE),
                    () -> c.getSimpleName() + " should be deep @Immutable");
        }
    }

    @Test
    public void testDurationOfMillis() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(Duration.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("ofMillis", 1);
        assertFalse(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());
        assertSame(INDEPENDENT, methodInfo.analysis().getOrDefault(INDEPENDENT_METHOD, DEPENDENT));
        assertSame(IMMUTABLE, methodInfo.analysis().getOrDefault(IMMUTABLE_METHOD, MUTABLE));
    }

}
