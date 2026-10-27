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

package io.codelaser.maddi.modification.analyzer.integration;


import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static org.junit.jupiter.api.Assertions.*;

public class TestIndependentOfEnum extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            import java.io.Serializable;
            
            public enum E {
              A, B, C
            }
            """;

    @DisplayName("independent of serializable enum")
    @Test
    public void test1() {
        TypeInfo E = javaInspector.parse("E", INPUT1);
        assertFalse(E.isExtensible());
        FieldInfo A = E.getFieldByName("A", true);
        assertFalse(A.type().typeInfo().isExtensible());

        TypeInfo enumType = javaInspector.compiledTypesManager().type(Enum.class);
        assertTrue(enumType.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT).isIndependent());

        List<Info> ao = prepWork(E);
        analyzer.go(ao);

        assertSame(ValueImpl.IndependentImpl.INDEPENDENT, E.analysis().getOrNull(INDEPENDENT_TYPE,
                ValueImpl.IndependentImpl.class));
        assertSame(ValueImpl.ImmutableImpl.IMMUTABLE, E.analysis().getOrNull(IMMUTABLE_TYPE,
                ValueImpl.ImmutableImpl.class));
    }
}
