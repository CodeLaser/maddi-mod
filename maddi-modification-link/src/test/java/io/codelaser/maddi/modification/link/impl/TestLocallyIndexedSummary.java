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
 * An array element at a local index ({@code a[index++]}, {@code arr[i]}) in a method's summary: the caller cannot
 * resolve the index, but it can resolve the array, so the link is lifted through {@code X[i] ∈ X} rather than dropped
 * (gate NOLIFTINDEX). Extraction prefers the element form and blocks the array form as implied by it, so dropping
 * lost the link altogether. CodeLaser/maddi-mod#72 4a exposed it for guava's RegularImmutableMultiset.create: once
 * the loop fork made {@code index} non-constant, {@code entries ∩ 0:in.§$s} disappeared.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestLocallyIndexedSummary extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class C {
                static class Entry { final String e; Entry(String e) { this.e = e; } }
                static Entry[] postInc(List<String> in) {
                    Entry[] a = new Entry[in.size()];
                    int index = 0;
                    for (String s : in) {
                        a[index++] = new Entry(s);
                    }
                    return a;
                }
                static void fill(Entry[] arr, List<String> in) {
                    for (int i = 0; i < arr.length; i++) arr[i] = new Entry(in.get(i));
                }
                static String pickE(Entry[] arr, int k) {
                    int i = k + 1;
                    return arr[i].e;
                }
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

    @DisplayName("a[index++] in a loop: the returned array shares the input's elements' content")
    @Test
    public void postInc() {
        // #72 4a's loop fork makes 'index' non-constant: the element is a vertex of its own, and the lifted link
        // reads on the return value (without the fork: '[0:in.§$s∩postInc] --> -')
        assertEquals("[-] --> postInc∩0:in.§$s  MOD[]", mlv("postInc"));
    }

    @DisplayName("arr[i] = .. in a for loop: the parameter array shares the input's elements' content")
    @Test
    public void fill() {
        assertEquals("[0:arr*∩1:in.§$s, 1:in.§$s∩0:arr*] --> -  MOD[a.b.C.fill(a.b.C.Entry[],java.util.List):0:arr]",
                mlv("fill"));
    }

    @DisplayName("return arr[i].e: the result is in the parameter array's object graph")
    @Test
    public void pickE() {
        assertEquals("[0:arr≥pickE, -] --> -  MOD[]", mlv("pickE"));
    }
}
