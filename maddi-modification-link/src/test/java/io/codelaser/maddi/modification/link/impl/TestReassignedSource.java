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

package io.codelaser.maddi.modification.link.impl;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A source of an assignment group that is assigned a new value afterwards: {@code x = h.next; h.next = y},
 * {@code x = a[i]; a[i] = y}, guava's {@code bucketHead = hashTable[b]; .. hashTable[b] = newEntry}. The group keeps
 * the OLD value; the source leaves it (CodeLaser/maddi-mod#77, gate NOSRCEVICT). Before, a plain edge from the
 * group's rep to the new value made every member hold it too: {@code return x} read {@code ← 1:y}. A modification
 * through the old value still reaches the variable it was read from. A return value keeps accumulating (it is
 * assigned on several paths, TestIdentity), and so, for now, does a parameter: evicted, its new value would read
 * as the caller's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestReassignedSource extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class C {
                static class Entry { String e; Entry next; }
                static Entry swapSlot(Entry[] a, Entry y) { Entry x = a[0]; a[0] = y; return x; }
                static Entry swapLocalIndex(Entry[] a, Entry y, int k) { int i = k & 15; Entry x = a[i]; a[i] = y; return x; }
                static Entry swapField(Entry h, Entry y) { Entry x = h.next; h.next = y; return x; }
                static void modifyOld(Entry h, Entry y) { Entry x = h.next; h.next = y; x.e = "z"; }
                static void modifyOldParameter(Entry p, Entry y, Entry z) { Entry x = p; p = y; p = z; x.next = null; }
            }
            """;

    private Map<String, String> cache;

    private String mlv(String name) {
        if (cache == null) {
            TypeInfo c = javaInspector.parse("a.b.C", INPUT);
            new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(c);
            LinkComputerImpl lc = new LinkComputerImpl(javaInspector);
            cache = new HashMap<>();
            c.methodStream().forEach(m -> {
                MethodLinkedVariables mlv = lc.doMethod(m);
                cache.put(m.name(), mlv + "  MOD[" + mlv.sortedModifiedString() + "]");
            });
        }
        return cache.get(name);
    }

    @DisplayName("x = a[0]; a[0] = y; return x: the old element, not y")
    @Test
    public void swapSlot() {
        assertEquals("""
                [0:a*∋1:y,0:a*[0]←1:y, 1:y∈0:a*,1:y→0:a*[0]] --> swapSlot∈0:a*,swapSlot←0:a*[0]  MOD[a.b.C.swapSlot(a.b.C.Entry[],a.b.C.Entry):0:a]""", mlv("swapSlot"));
    }

    @DisplayName("x = a[i]; a[i] = y; return x, local index")
    @Test
    public void swapLocalIndex() {
        assertEquals("""
                [0:a*∋1:y, 1:y∈0:a*, -] --> swapLocalIndex∈0:a*  MOD[a.b.C.swapLocalIndex(a.b.C.Entry[],a.b.C.Entry,int):0:a]""", mlv("swapLocalIndex"));
    }

    @DisplayName("x = h.next; h.next = y; return x: the old next, not y")
    @Test
    public void swapField() {
        assertEquals("""
                [0:h*.next←1:y, 1:y→0:h*.next] --> swapField←0:h*.next  MOD[a.b.C.swapField(a.b.C.Entry,a.b.C.Entry):0:h]""", mlv("swapField"));
    }

    @DisplayName("a modification through the old next reaches h; y too, conservatively, as before")
    @Test
    public void modifyOld() {
        assertEquals("""
                [0:h.next*←1:y*, 1:y*→0:h.next*] --> -  MOD[a.b.C.modifyOld(a.b.C.Entry,a.b.C.Entry):0:h, a.b.C.modifyOld(a.b.C.Entry,a.b.C.Entry):1:y, h.next]""", mlv("modifyOld"));
    }

    @DisplayName("a modification through the old value of a twice reassigned parameter reaches the parameter (y, z as before)")
    @Test
    public void modifyOldParameter() {
        assertEquals("""
                [0:p*.next←$_ce0, 1:y*≈2:z*, 2:z*≈1:y*] --> -  MOD[a.b.C.modifyOldParameter(a.b.C.Entry,a.b.C.Entry,a.b.C.Entry):0:p, a.b.C.modifyOldParameter(a.b.C.Entry,a.b.C.Entry,a.b.C.Entry):1:y, a.b.C.modifyOldParameter(a.b.C.Entry,a.b.C.Entry,a.b.C.Entry):2:z]""", mlv("modifyOldParameter"));
    }
}
