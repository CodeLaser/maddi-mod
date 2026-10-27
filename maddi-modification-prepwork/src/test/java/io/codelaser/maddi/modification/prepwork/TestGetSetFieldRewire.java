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

package io.codelaser.maddi.modification.prepwork;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.InfoMap;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The first {@code carryOnRewire} property: {@code GET_SET_FIELD}. It is set at parse time (record synthetics /
 * KotlinScan via {@code FactoryImpl.setGetSetField}); a REWIRE'd type is never re-parsed, so unless the value is
 * carried across the rewire it is lost. This pins that {@code MethodInfoImpl.rewirePhase3} now carries the opted-in
 * analysis, and that {@code GetSetValueImpl.rewire} re-points the field through the {@code InfoMap}. See
 * {@code docs/design/rewiring.md} / {@code docs/design/analysis-rewiring.md}.
 */
public class TestGetSetFieldRewire extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            record X(int value) {}
            """;

    @DisplayName("GET_SET_FIELD survives a rewire, re-pointed at the rewired field")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse(ABX, INPUT);
        MethodInfo accessor = x.findUniqueMethod("value", 0);
        ValueImpl.GetSetValueImpl before = accessor.analysis()
                .getOrNull(PropertyImpl.GET_SET_FIELD, ValueImpl.GetSetValueImpl.class);
        assertNotNull(before, "a record accessor carries GET_SET_FIELD at parse time");
        FieldInfo field = before.field();
        assertNotNull(field);
        assertSame(x, field.owner());

        InfoMap infoMap = runtime.newInfoMap(Set.of(x));
        Set<TypeInfo> rewiredSet = infoMap.rewireAll();
        assertEquals(1, rewiredSet.size());
        TypeInfo x2 = rewiredSet.iterator().next();
        assertNotSame(x, x2);
        MethodInfo accessor2 = x2.findUniqueMethod("value", 0);
        assertNotSame(accessor, accessor2);

        ValueImpl.GetSetValueImpl after = accessor2.analysis()
                .getOrNull(PropertyImpl.GET_SET_FIELD, ValueImpl.GetSetValueImpl.class);
        assertNotNull(after, "GET_SET_FIELD must survive the rewire (opted in via carryOnRewire)");
        assertNotSame(before.field(), after.field(), "it must not still point at the replaced field");
        assertSame(infoMap.fieldInfo(before.field()), after.field(), "it points at the rewired field");
        assertSame(x2, after.field().owner());
    }
}
