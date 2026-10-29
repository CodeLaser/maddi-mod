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

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.link.impl.linkgraph.Graph;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Alternatives are not aliases: a recipient assigned one of several values in one statement may hold either, but
 * the values are not each other (guava's TypeResolver.resolveParameterizedType read a conditional's null on the
 * other argument of newParameterizedTypeWithOwner, CodeLaser/maddi-mod#72). Gate NOALTSOURCES.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestAlternativeSources extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.lang.reflect.Type;
            import java.util.List;
            public class C {
                static Type newType(Type owner, Class<?> raw) { return owner == null ? raw : owner; }
                static Type call(Type owner, Class<?> raw) {
                    Type t = newType(owner, raw);
                    return t;
                }
                static List<String> returnEither(List<String> a, List<String> b, boolean c) {
                    return c ? a : b;
                }
                static void modifyEither(List<String> a, List<String> b, boolean c) {
                    List<String> m = c ? a : b;
                    m.add("x");
                }
                static List<String> modifyEitherThenReturn(List<String> a, List<String> b, boolean c) {
                    List<String> m = c ? a : b;
                    m.add("x");
                    return m;
                }
                static int chain(List<String> a, List<String> b, boolean c) {
                    List<String> m = c ? a : b;
                    List<String> n = m;
                    return n.size();
                }
                static List<String> either(List<String> a, List<String> b, boolean c) { return c ? a : b; }
                static List<String> first(List<String> x, List<String> y) { return x; }
                static List<String> twoCalls(List<String> a, List<String> b, boolean c) {
                    return first(either(a, b, c), either(a, b, !c));
                }
                private Object[] elements;
                private Object[] requireElements() { return java.util.Objects.requireNonNull(elements); }
                void setElement(int i, Object value) { requireElements()[i] = value; }
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

    @DisplayName("a call result with two sources: the result may be either, the arguments are not each other")
    @Test
    public void call() {
        // NOALTSOURCES gives the same summary here; the alias the group made shows inside the caller (guava's
        // TypeResolver.resolveParameterizedType, measured on the corpus)
        assertEquals("[-, -] --> call←0:owner,call←1:raw  MOD[]", mlv("call"));
    }

    @DisplayName("a conditional returned: as the call result")
    @Test
    public void returnEither() {
        assertEquals("[-, -, -] --> returnEither←0:a,returnEither←1:b  MOD[]", mlv("returnEither"));
    }

    @DisplayName("a conditional's recipient modified: both alternatives are")
    @Test
    public void modifyEither() {
        // the modification travels over the plain edges; the §m equivalence and the ≈ between the alternatives
        // remain (the §m groups are transitive): a modification of one still counts for the other, conservatively
        assertEquals("[0:a*≈1:b*,0:a*.§$s∋$_ce1,0:a*.§m≡1:b*.§m, 1:b*≈0:a*,1:b*.§$s∋$_ce1,1:b*.§m≡0:a*.§m, -] --> -"
                     + "  MOD[a.b.C.modifyEither(java.util.List,java.util.List,boolean):0:a,"
                     + " a.b.C.modifyEither(java.util.List,java.util.List,boolean):1:b]", mlv("modifyEither"));
    }

    @DisplayName("a conditional's recipient modified and returned")
    @Test
    public void modifyEitherThenReturn() {
        assertEquals("[0:a*≈1:b*,0:a*.§$s∋$_ce1,0:a*.§m≡1:b*.§m, 1:b*≈0:a*,1:b*.§$s∋$_ce1,1:b*.§m≡0:a*.§m, -] --> "
                     + "modifyEitherThenReturn←0:a*,modifyEitherThenReturn←1:b*,modifyEitherThenReturn.§$s∋$_ce1,"
                     + "modifyEitherThenReturn.§$s←0:a*.§$s,modifyEitherThenReturn.§$s←1:b*.§$s,"
                     + "modifyEitherThenReturn.§m≡0:a*.§m,modifyEitherThenReturn.§m≡1:b*.§m"
                     + "  MOD[a.b.C.modifyEitherThenReturn(java.util.List,java.util.List,boolean):0:a,"
                     + " a.b.C.modifyEitherThenReturn(java.util.List,java.util.List,boolean):1:b]",
                mlv("modifyEitherThenReturn"));
    }

    @DisplayName("a conditional's recipient copied on: the copy is an alias of the recipient")
    @Test
    public void chain() {
        assertEquals("[-, -, -] --> -  MOD[]", mlv("chain"));
    }

    @DisplayName("an element of an array without a variable is no recipient of alternatives, and no crash")
    @Test
    public void setElement() {
        // 'requireElements()[i]' has no primary variable (guava's CompactHashSet.setElement threw)
        assertEquals("[-, 1:value→requireElements()[0:i]] --> -  MOD[]", mlv("setElement"));
    }

    @DisplayName("two recipients sharing their alternatives keep the group, as before: cost over precision")
    @Test
    public void twoCalls() {
        // both call results take 0:a and 1:b: they keep the group, giving devel's (NOALTSOURCES) summary; as plain
        // edges it would add 'twoCalls.§m≡0:a.§m,twoCalls.§m≡1:b.§m' (guava's ImmutableMapTest.testEquals, 28 maps
        // sharing their alternatives, grew past the work ceiling)
        assertEquals("[-, -, -] --> twoCalls←0:a,twoCalls←1:b  MOD[]", mlv("twoCalls"));
    }

    @DisplayName("a method whose alternatives exceed the work ceiling is computed again without them, not degraded")
    @Test
    public void retryWithoutAlternatives() {
        TypeInfo c = javaInspector.parse("a.b.C", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(c);
        MethodInfo modifyEither = c.findUniqueMethod("modifyEither", 3);
        Graph.DEGRADE_ON_ALT_SOURCES_FOR_TESTING = true;
        try {
            MethodLinkedVariables mlv = new LinkComputerImpl(javaInspector).doMethod(modifyEither);
            // the summary without alternatives (NOALTSOURCES), not modifyEither()'s, nor a degraded one
            assertEquals("[0:a*.§$s∋$_ce1, 1:b*.§$s∋$_ce1, -] --> -", mlv.toString());
            assertFalse(modifyEither.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                    ValueImpl.BoolImpl.FALSE).isTrue());
        } finally {
            Graph.DEGRADE_ON_ALT_SOURCES_FOR_TESTING = false;
        }
    }
}
