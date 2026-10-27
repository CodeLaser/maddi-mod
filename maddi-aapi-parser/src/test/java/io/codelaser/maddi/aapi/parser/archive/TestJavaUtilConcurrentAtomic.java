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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.CONTAINER_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_PARAMETER;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_TYPE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.TRUE;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.DEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT;
import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.IndependentImpl.INDEPENDENT_HC;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestJavaUtilConcurrentAtomic extends CommonTest {

    // AtomicBoolean/AtomicInteger hold an immutable primitive: @Container + deep @Independent.
    // get() reads and must be non-modifying (set()/getAndSet()/incrementAndGet() do modify).
    @Test
    public void testAtomicPrimitiveHolders() {
        for (Class<?> c : new Class<?>[]{AtomicBoolean.class, AtomicInteger.class}) {
            TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(c);
            assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE),
                    () -> c.getSimpleName() + " should be a @Container");
            assertSame(INDEPENDENT, typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT),
                    () -> c.getSimpleName() + " should be deep @Independent");
            assertFalse(typeInfo.findUniqueMethod("get", 0).isModifying(),
                    () -> c.getSimpleName() + ".get() must be non-modifying");
            assertTrue(typeInfo.findUniqueMethod("set", 1).isModifying(),
                    () -> c.getSimpleName() + ".set() modifies");
        }
    }

    // AtomicReference<V> holds hidden content: @Container + @Independent(hc=true), and that type-level
    // independence propagates INDEPENDENT_HC to the V parameters (e.g. set(V)).
    @Test
    public void testAtomicReference() {
        TypeInfo typeInfo = compiledTypesManager().typeIfLoaded(AtomicReference.class);
        assertSame(TRUE, typeInfo.analysis().getOrDefault(CONTAINER_TYPE, FALSE));
        assertSame(INDEPENDENT_HC, typeInfo.analysis().getOrDefault(INDEPENDENT_TYPE, DEPENDENT));

        // set(V) mutates the holder (isModifying), but does NOT modify V's content, and the V
        // parameter shares hidden content with the holder -> @Independent(hc=true).
        assertFalse(typeInfo.findUniqueMethod("get", 0).isModifying(), "AtomicReference.get() must be non-modifying");
        MethodInfo set = typeInfo.findUniqueMethod("set", 1);
        ParameterInfo p0 = set.parameters().getFirst();
        assertFalse(p0.isModified());
        assertSame(INDEPENDENT_HC, p0.analysis().getOrDefault(INDEPENDENT_PARAMETER, DEPENDENT));
    }
}
