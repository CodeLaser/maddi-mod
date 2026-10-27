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

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A functional interface may INHERIT its single abstract method from a type that is not being rewired, a JDK
 * interface most simply. The info map then answers the method itself, which is right: there is no copy to
 * point at. {@code TypeInfoImpl.rewirePhase2} asserted that the rewired method always differs, and every
 * rewire of a project holding such an interface died on it (camel-core-model, 2026-09-21).
 */
public class TestInheritedSamRewire extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.function.Supplier;
            interface X extends Supplier<String> {
                default String twice() { return get() + get(); }
            }
            """;

    @DisplayName("a single abstract method inherited from outside the rewired set stays what it is")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse(ABX, INPUT);
        MethodInfo sam = x.singleAbstractMethod();
        assertNotNull(sam);
        assertEquals("java.util.function.Supplier", sam.typeInfo().fullyQualifiedName());
        Set<TypeInfo> rewired = runtime.newInfoMap(Set.of(x)).rewireAll();
        TypeInfo x2 = rewired.iterator().next();
        assertNotSame(x, x2);
        assertSame(sam, x2.singleAbstractMethod());
    }
}
