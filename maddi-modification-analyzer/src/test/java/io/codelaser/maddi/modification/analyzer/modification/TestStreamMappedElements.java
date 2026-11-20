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

package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#138: objects taken out of a parameter's elements through a stream ('map', 'filter',
 * 'collect', 'toList') are those elements, or their accessible content; modifying them modifies the parameter, as the
 * for-each twin does. fernflower's SwitchHelper.removeTempVariableDeclarations, ClassWriter.appendTypeParameters.
 */
public class TestStreamMappedElements extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.stream.Collectors;
            class X {
                public static class St { public final List<String> exprents = new ArrayList<>(); }
                public record Item(St statement, boolean delete) { }
                static final class Part { private int n; int bump() { n += 1; return n; } }
                static void viaLoop(List<Item> items) { for (Item i : items) i.statement().exprents.clear(); }
                static void viaMapToList(List<Item> items) {
                    List<St> sts = items.stream().map(a -> a.statement()).toList();
                    for (St s : sts) s.exprents.clear();
                }
                static void viaCollectSet(List<Item> items) {
                    Set<St> sts = items.stream().filter(a -> a.delete).map(a -> a.statement()).collect(Collectors.toSet());
                    for (St s : sts) s.exprents.clear();
                }
                static void viaMethodRef(List<Item> items) {
                    Set<St> sts = items.stream().map(Item::statement).collect(Collectors.toSet());
                    for (St s : sts) s.exprents.clear();
                }
                static List<Part> extract(List<Part> parts) {
                    return parts.stream().filter(p -> true).collect(Collectors.toList());
                }
                static void viaExtractForEach(List<Part> parts) { extract(parts).forEach(p -> p.bump()); }
                static void viaExtractFor(List<Part> parts) { for (Part p : extract(parts)) p.bump(); }
                static List<Part> sub(List<Part> parts) { return parts.subList(0, 1); }
                static void viaSubForEach(List<Part> parts) { sub(parts).forEach(p -> p.bump()); }
                static void viaLocalForEach(List<Part> parts) { List<Part> l = extract(parts); l.forEach(p -> p.bump()); }
                // the control: what is mapped out is a fresh object, nothing of the items
                static void viaFresh(List<Item> items) {
                    List<St> sts = items.stream().map(a -> new St()).toList();
                    for (St s : sts) s.exprents.clear();
                }
            }
            """;

    private static final List<String> METHODS = List.of("viaLoop", "viaMapToList", "viaCollectSet", "viaMethodRef",
            "viaExtractForEach", "viaExtractFor", "viaSubForEach", "viaLocalForEach", "viaFresh");

    @DisplayName("modifying what a stream took out of a parameter's elements, with the reachability pass")
    @Test
    public void reachability() {
        assertEquals("viaLoop=MU viaMapToList=MU viaCollectSet=MU viaMethodRef=MU viaExtractForEach=MU viaExtractFor=MU"
                     + " viaSubForEach=MU viaLocalForEach=MU viaFresh=UU", run(true));
    }

    /*
     KNOWN GAP, the fixpoint engine only (production runs the reachability pass): 'viaExtractForEach' and
     'viaLocalForEach'. The engine's #99 rule (MethodModification, a library callee hands the receiver's elements to a
     modifying function) marks the receiver itself modified, deep-only; a fresh list of parts' elements does not pass
     that on to parts ('l.§m ← parts.§m' carries no modification, 'l.§$s ⊆ parts.§$s' only one of l's hidden content).
     The pass seeds the receiver's element sources (ShadowModificationPass.elementSources). subList's '§m ≡' does pass
     it on.
     */
    @DisplayName("idem, the fixpoint engine")
    @Test
    public void fixpoint() {
        assertEquals("viaLoop=MU viaMapToList=MU viaCollectSet=MU viaMethodRef=MU viaExtractForEach=UU viaExtractFor=MU"
                     + " viaSubForEach=MU viaLocalForEach=UU viaFresh=UU", run(false));
    }

    private String run(boolean reach) {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String n : METHODS) {
            MethodInfo m = x.methods().stream().filter(mi -> mi.name().equals(n)).findFirst().orElseThrow();
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(n).append('=').append(m.parameters().getFirst().isUnmodified() ? 'U' : 'M')
                    .append(m.parameters().getFirst().isStructurallyUnmodified() ? 'U' : 'M');
        }
        return sb.toString();
    }
}
