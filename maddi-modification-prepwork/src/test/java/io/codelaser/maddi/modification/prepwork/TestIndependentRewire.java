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

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.InfoMap;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code IndependentImpl} reads like a plain lattice level but is not one: {@code dependentExceptions} holds
 * {@code MethodInfo}. It is written from the annotated API only, and in practice for a single case — the
 * {@code remove()} of the {@code Iterator} returned by {@code java.lang.Iterable}, which enhanced for-loops depend
 * on. Those methods live in a library type and so are never rewired, but nothing in the value guarantees that, so
 * {@code rewire} maps them; and the empty case must keep returning the singletons themselves.
 */
public class TestIndependentRewire extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Iterator;
            class X {
                Iterator<String> iterator() { return null; }
                void remove() { }
            }
            """;

    @DisplayName("Independent.rewire re-points dependentExceptions, and keeps the singletons when empty")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse(ABX, INPUT);
        MethodInfo remove = x.findUniqueMethod("remove", 0);

        InfoMap infoMap = runtime.newInfoMap(Set.of(x));
        Set<TypeInfo> rewiredSet = infoMap.rewireAll();
        assertEquals(1, rewiredSet.size());
        TypeInfo x2 = rewiredSet.iterator().next();
        MethodInfo remove2 = x2.findUniqueMethod("remove", 0);
        assertNotSame(remove, remove2);

        // the real shape: an @Independent carrying a dependent-method exception
        Value.Independent independent = new ValueImpl.IndependentImpl(2, Map.of(), List.of(remove));
        assertEquals(List.of(remove), independent.dependentMethods());

        Value rewired = independent.rewire(infoMap);
        assertInstanceOf(Value.Independent.class, rewired);
        List<MethodInfo> after = ((Value.Independent) rewired).dependentMethods();
        assertEquals(1, after.size());
        assertSame(remove2, after.getFirst(), "the exception must point at the rewired method, not the replaced one");

        // empty dependentExceptions is the common case: the value is plain, and the singletons must survive intact
        assertSame(ValueImpl.IndependentImpl.INDEPENDENT, ValueImpl.IndependentImpl.INDEPENDENT.rewire(infoMap));
        assertSame(ValueImpl.IndependentImpl.DEPENDENT, ValueImpl.IndependentImpl.DEPENDENT.rewire(infoMap));
        assertSame(ValueImpl.IndependentImpl.INDEPENDENT_HC, ValueImpl.IndependentImpl.INDEPENDENT_HC.rewire(infoMap));

        // a method of a type that was not rewired passes through untouched -- the java.lang.Iterable case
        MethodInfo libraryMethod = javaInspector.compiledTypesManager()
                .type("java.util.Iterator", null).findUniqueMethod("remove", 0);
        Value.Independent viaLibrary = new ValueImpl.IndependentImpl(2, Map.of(), List.of(libraryMethod));
        assertSame(libraryMethod, ((Value.Independent) viaLibrary.rewire(infoMap)).dependentMethods().getFirst());
    }
}
