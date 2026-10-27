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
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DecimalStyle;
import java.time.format.FormatStyle;
import java.time.format.ResolverStyle;
import java.time.format.SignStyle;
import java.time.format.TextStyle;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.IMMUTABLE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.ImmutableImpl.MUTABLE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaTimeFormat extends CommonTest {

    // DecimalStyle and the enums are immutable value types with no output-parameter method ->
    // deep @Immutable + @Container.
    @Test
    public void testImmutableContainerValueTypes() {
        for (Class<?> c : new Class<?>[]{DecimalStyle.class, FormatStyle.class, ResolverStyle.class,
                SignStyle.class, TextStyle.class}) {
            TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(c);
            assertSame(IMMUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE),
                    () -> c.getSimpleName() + " should be deep @Immutable");
            assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE),
                    () -> c.getSimpleName() + " should be a @Container");
        }
    }

    // DateTimeFormatter is immutable, but formatTo(...,Appendable)/parse(...,ParsePosition) write to
    // their argument, so it is @Immutable, NOT a @Container (the Charset/String pattern).
    @Test
    public void testDateTimeFormatterImmutableNotContainer() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(DateTimeFormatter.class);
        assertSame(IMMUTABLE, typeInfo.analysis().getOrDefault(IMMUTABLE_TYPE, MUTABLE));
        assertSame(FALSE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
    }

    // DateTimeFormatterBuilder is a mutable builder that never modifies a parameter -> @Container;
    // toFormatter() reads state (non-modifying) while append*() mutate the builder.
    @Test
    public void testDateTimeFormatterBuilder() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(DateTimeFormatterBuilder.class);
        assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
        MethodInfo toFormatter = typeInfo.methods().stream()
                .filter(m -> m.name().equals("toFormatter") && m.parameters().isEmpty()).findFirst().orElseThrow();
        assertFalse(toFormatter.isModifying(), "toFormatter() must be non-modifying");
        MethodInfo append = typeInfo.methods().stream()
                .filter(m -> m.name().equals("appendLiteral")).findFirst().orElseThrow();
        assertTrue(append.isModifying(), "appendLiteral() mutates the builder");
    }
}
