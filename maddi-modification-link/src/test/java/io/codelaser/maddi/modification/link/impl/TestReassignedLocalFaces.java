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
 * A reassigned local keeps none of its old object's faces, and the cases of an old-style switch are alternatives
 * (guava's ImmutableList.copyOf(E[]): its element flowed into the argument's, CodeLaser/maddi-mod#72 4a).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestReassignedLocalFaces extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class C {
                static abstract class IList<E> {
                    static <E> IList<E> of(E e1) { return new Singleton<>(e1); }
                    static <E> IList<E> construct(Object[] elements) {
                        switch (elements.length) {
                            case 1:
                                @SuppressWarnings("unchecked") E only = (E) elements[0];
                                return of(only);
                            default:
                                return new Regular<>(elements);
                        }
                    }
                }
                static final class Regular<E> extends IList<E> {
                    final Object[] array;
                    Regular(Object[] array) { this.array = array; }
                }
                static final class Singleton<E> extends IList<E> {
                    final E element;
                    Singleton(E element) { this.element = element; }
                }
                static final Regular<Object> EMPTY = new Regular<>(new Object[0]);

                static <E> IList<E> newThenNew(E[] elements, Object[] other) {
                    IList<E> r = new Singleton<>(elements[0]);
                    r = new Regular<>(other);
                    return r;
                }
                static <E> IList<E> ofThenConstruct(E[] elements, Object[] other) {
                    IList<E> r = IList.of(elements[0]);
                    r = IList.construct(other);
                    return r;
                }
                static <E> IList<E> switchReturns(E[] elements, Object[] other) {
                    switch (elements.length) {
                        case 1:
                            return IList.of(elements[0]);
                        default:
                            return IList.construct(other);
                    }
                }
                static <E> IList<E> ifReturns(E[] elements, Object[] other) {
                    if (elements.length == 1) return IList.of(elements[0]);
                    else return IList.construct(other);
                }
                @SuppressWarnings("unchecked")
                static <E> IList<E> switchEmpty(E[] elements) {
                    switch (elements.length) {
                        case 0:
                            return (IList<E>) EMPTY;
                        default:
                            return new Singleton<>(elements[0]);
                    }
                }
                @SuppressWarnings("unchecked")
                static <E> IList<E> ifEmpty(E[] elements) {
                    if (elements.length == 0) return (IList<E>) EMPTY;
                    else return new Singleton<>(elements[0]);
                }
                static int fallThrough(int n, int[] a) {
                    int k = 0;
                    switch (n) {
                        case 0:
                            k = a[0];
                        case 1:
                            return k;
                        default:
                            return a[1];
                    }
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
            c.recursiveSubTypeStream().flatMap(TypeInfo::constructorAndMethodStream).forEach(m -> {
                MethodLinkedVariables mlv = lc.doMethod(m);
                cache.put(m.name(), mlv + "  MOD[" + mlv.sortedModifiedString() + "]");
            });
        }
        return cache.get(name);
    }

    @DisplayName("r = new Singleton<>(elements[0]); r = new Regular<>(other): r.element is gone")
    @Test
    public void newThenNew() {
        // was '.., newThenNew.element ← 0:elements[0], ..': the group of 'r.element' outlived 'r' (gate NOFACEDROP)
        assertEquals("[-, -] --> newThenNew.array←1:other,newThenNew.array.§m≡1:other.§m  MOD[]",
                mlv("newThenNew"));
    }

    @DisplayName("r = of(elements[0]); r = construct(other): no link between the parameters")
    @Test
    public void ofThenConstruct() {
        // was '0:elements ~ 1:other', '1:other[0] → 0:elements[0]', 'ofThenConstruct.element → 0:elements[0]'
        assertEquals("[-, -] --> ofThenConstruct.array←1:other,ofThenConstruct.array∋1:other[0],"
                     + "ofThenConstruct.element∈1:other,ofThenConstruct.element←1:other[0],"
                     + "ofThenConstruct.array.§m≡1:other.§m  MOD[]", mlv("ofThenConstruct"));
    }

    @DisplayName("switch cases returning of(elements[0]) and construct(other): as the if-else")
    @Test
    public void switchReturns() {
        // was '0:elements ~ 1:other', 'switchReturns.element → 0:elements[0]': the cases composed (gate NOSWITCHFORK)
        assertEquals(mlv("ifReturns").replace("ifReturns", "switchReturns"), mlv("switchReturns"));
        assertEquals("[-, -] --> switchReturns.element∈0:elements,switchReturns.element←0:elements[0],"
                     + "switchReturns.array∋switchReturns.element,switchReturns.array←1:other,"
                     + "switchReturns.array∋1:other[0],switchReturns.array[0]←1:other[0],"
                     + "switchReturns.element∈1:other,switchReturns.element←1:other[0],"
                     + "switchReturns.array.§m≡1:other.§m  MOD[]", mlv("switchReturns"));
    }

    @DisplayName("switch cases returning a constant and a new object: as the if-else")
    @Test
    public void switchEmpty() {
        // was without 'switchEmpty ← C.EMPTY': the second return erased the first
        assertEquals(mlv("ifEmpty").replace("ifEmpty", "switchEmpty"), mlv("switchEmpty"));
        assertEquals("[-] --> switchEmpty←C.EMPTY,switchEmpty.element∈0:elements,switchEmpty.element←0:elements[0]"
                     + "  MOD[]", mlv("switchEmpty"));
    }

    @DisplayName("a case falling through is still linked one after the other")
    @Test
    public void fallThrough() {
        assertEquals("[-, -] --> fallThrough∈1:a,fallThrough←1:a[0],fallThrough←1:a[1]  MOD[]", mlv("fallThrough"));
    }
}
