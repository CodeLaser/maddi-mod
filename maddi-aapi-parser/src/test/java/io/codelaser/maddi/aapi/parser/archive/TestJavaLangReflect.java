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
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaLangReflect extends CommonTest {

    @Test
    public void testArrayGet() {
        TypeInfo typeInfo = compiledTypesManager().type(Array.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("get", 2);
        assertFalse(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());

        ParameterInfo p0 = methodInfo.parameters().get(0);
        assertFalse(p0.isModified());
    }

    @Test
    public void testArraySet() {
        TypeInfo typeInfo = compiledTypesManager().type(Array.class);
        MethodInfo methodInfo = typeInfo.findUniqueMethod("set", 3);
        assertFalse(methodInfo.allowsInterrupts());
        assertFalse(methodInfo.isModifying());

        ParameterInfo p0 = methodInfo.parameters().get(0);
        assertTrue(p0.isModified());

        ParameterInfo p2 = methodInfo.parameters().get(2);
        assertFalse(p2.isModified());
    }

}
