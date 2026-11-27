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

package io.codelaser.maddi.modification.analyzer.field;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_METHOD;
import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * Independence claims the dynamic check (rust codelaser-dynamic-modification, 2c) saw contradicted and witnessed on
 * fernflower: an argument stored into the receiver, a return value holding the receiver's own parts.
 * <p>
 * Shape S (fernflower's {@code Statement.addSuccessor}/{@code addEdgeDirectInternal}): the edge goes into one of two
 * map fields, chosen by a ternary, through {@code computeIfAbsent(..).add(edge)}. Shape R
 * ({@code AssignmentExprent.checkExprTypeBounds}): a new result object gets a field of the receiver, wrapped in a
 * record, in a list.
 * <p>
 * Both were lost the same way: the callee's summary exports a composed object-graph link ({@code 0:e ≤ this.succ.§$$s},
 * {@code 0:e ≤ this.pairs.§$s}; the list in between summarized away), which the call site refused as an edge between
 * a real variable and a hidden content (Graph.invalidEdge, gate NOOGRV); and for R, the independence judgement skipped
 * '≥' to a whole field (LinkToField, gate NOOGCONTAINSFIELD). s7 and r4 are the controls: a fresh object stored or
 * wrapped.
 */
public class TestStoredArgumentIndependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class E { private int n; void inc() { n++; } }
                enum K { A, B }
                static class S {
                    private final List<E> list = new ArrayList<>();
                    private final EnumMap<K, List<E>> succ = new EnumMap<>(K.class);
                    private final EnumMap<K, List<E>> pred = new EnumMap<>(K.class);
                    void s0(E e) { list.add(e); }
                    void s1(E e) { succ.computeIfAbsent(K.A, k -> new ArrayList<>()).add(e); }
                    void s2(boolean back, E e) {
                        EnumMap<K, List<E>> m = back ? pred : succ;
                        m.computeIfAbsent(K.A, k -> new ArrayList<>()).add(e);
                    }
                    private void s3i(boolean back, E e, K k) {
                        EnumMap<K, List<E>> m = back ? pred : succ;
                        m.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
                    }
                    void s3(E e) { for (K k : new K[]{K.A, K.B}) s3i(false, e, k); }
                    void s4(E e) { s3i(false, e, K.A); }
                    private void h(E e) { list.add(e); }
                    void s5(E e) { h(e); }
                    private void h2(E e) { succ.computeIfAbsent(K.A, k -> new ArrayList<>()).add(e); }
                    void s6(E e) { h2(e); }
                    void s7(E e) { h2(new E()); e.inc(); }
                }
                static class Result {
                    private final List<Pair> pairs = new ArrayList<>();
                    void add(E e, int t) { pairs.add(new Pair(e, t)); }
                    List<Pair> getPairs() { return pairs; }
                    record Pair(E e, int t) { }
                }
                static class Result2 {
                    private final List<E> es = new ArrayList<>();
                    void add(E e) { es.add(e); }
                }
                static class Box { private E e; void set(E e) { this.e = e; } }
                static class R {
                    private E left = new E();
                    Result r0() { Result r = new Result(); r.add(left, 1); return r; }
                    List<E> r1() { List<E> l = new ArrayList<>(); l.add(left); return l; }
                    Result2 r2() { Result2 r = new Result2(); r.add(left); return r; }
                    Box r3() { Box b = new Box(); b.set(left); return b; }
                    Result r4() { Result r = new Result(); r.add(new E(), 1); return r; }
                }
            }
            """;

    @DisplayName("an argument stored into the receiver, a result holding the receiver's parts: dependent")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        TypeInfo s = x.findSubType("S");
        TypeInfo r = x.findSubType("R");
        String sv = s.methods().stream().filter(m -> m.name().matches("s\\d"))
                .map(m -> m.name() + "=" + ind(m.parameters().getLast())).collect(Collectors.joining(" "));
        String rv = r.methods().stream()
                .map(m -> m.name() + "=" + m.analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class))
                .collect(Collectors.joining(" "));
        assertEquals("s0=@Dependent s1=@Dependent s2=@Dependent s3=@Dependent s4=@Dependent s5=@Dependent "
                     + "s6=@Dependent s7=@Independent | r0=@Dependent r1=@Dependent r2=@Dependent r3=@Dependent "
                     + "r4=@Independent", sv + " | " + rv);
    }

    private static Object ind(ParameterInfo pi) {
        return pi.analysis().getOrNull(INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.class);
    }
}
