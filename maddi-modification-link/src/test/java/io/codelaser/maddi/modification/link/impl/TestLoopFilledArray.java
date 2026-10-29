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
 * Arrays filled in a loop and returned (CodeLaser/maddi-mod#79). The returned array shares its elements' content
 * with the input, as {@code a[index++] = ..} already does (TestLocallyIndexedSummary.postInc).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestLoopFilledArray extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class C {
                static class Entry { final String e; Entry(String e) { this.e = e; } }
                static Entry[] plainIdx(List<String> in) {
                    Entry[] a = new Entry[in.size()];
                    int index = 0;
                    for (String s : in) {
                        a[index] = new Entry(s);
                        index++;
                    }
                    return a;
                }
                static Entry[] forIdx(List<String> in) {
                    Entry[] a = new Entry[in.size()];
                    for (int i = 0; i < a.length; i++) a[i] = new Entry(in.get(i));
                    return a;
                }
                static Entry[] constantSlot(List<String> in) {
                    Entry[] a = new Entry[1];
                    int index = 0;
                    a[index] = new Entry(in.get(0));
                    return a;
                }
                static Entry[] slotPerIteration(List<String> in) {
                    Entry[] a = new Entry[1];
                    for (String s : in) {
                        int k = 0;
                        a[k] = new Entry(s);
                    }
                    return a;
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

    @DisplayName("a[index] = ..; index++ in the body: index varies, not the constant 0")
    @Test
    public void plainIdx() {
        // was 'plainIdx[0] ∩ 0:in.§$s': the store went to slot 0 (gate NOLOOPVARIED)
        assertEquals("[-] --> plainIdx∩0:in.§$s  MOD[]", mlv("plainIdx"));
    }

    @DisplayName("a[i] = new Entry(in.get(i)) in a for loop, returned")
    @Test
    public void forIdx() {
        // was '[-] --> -': 'a[i]' left the graph with 'i' (gate NOSCOPELIFT). The graph has no ∩ between a real
        // variable and hidden content, so the lift keeps the coarser 'a ∩ 0:in', not 'a ∩ 0:in.§$s'
        assertEquals("[-] --> forIdx∩0:in  MOD[]", mlv("forIdx"));
    }

    @DisplayName("outside a loop, a constant index stays a constant")
    @Test
    public void constantSlot() {
        assertEquals("[-] --> constantSlot[0].e∈0:in.§$s  MOD[]", mlv("constantSlot"));
    }

    @DisplayName("a local declared in the body is constant within one iteration")
    @Test
    public void slotPerIteration() {
        assertEquals("[-] --> slotPerIteration[0]∩0:in.§$s  MOD[]", mlv("slotPerIteration"));
    }
}
