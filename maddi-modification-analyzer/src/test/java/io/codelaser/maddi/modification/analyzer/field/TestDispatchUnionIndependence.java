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

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#167, #116's counterpart for independence: a concrete, overridable method whose overrides are
 * dependent may not stay independent, a call through it can reach them. fernflower's
 * {@code Exprent.checkExprTypeBounds() { return null; }}, whose overrides put a subexpression into the result.
 * <p>
 * {@code check}: the base returns null, an override returns the receiver's field inside a new list. {@code put}: the
 * base ignores its argument, an override stores it. {@code fresh} is the control: every override returns a new object
 * of its own. {@code P}: throw-only placeholders, whose independence is their override's ({@code Exprent.copy}), and
 * {@code Asg.copyLeft} calling one on its field ({@code AssignmentExprent.copy}); {@code PA} is the abstract control.
 * Both bases have a mutable field of their own, as {@code Exprent}: without one the base is immutable and what an
 * override returns is hidden content.
 */
public class TestDispatchUnionIndependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static class E { private int n; void inc() { n++; } }
                static abstract class Ex {
                    List<E> check() { return null; }
                    void put(E e) { }
                    List<E> fresh() { return null; }
                }
                static class A extends Ex {
                    private final E left = new E();
                    private final List<E> es = new ArrayList<>();
                    @Override List<E> check() { List<E> l = new ArrayList<>(); l.add(left); return l; }
                    @Override void put(E e) { es.add(e); }
                    @Override List<E> fresh() { List<E> l = new ArrayList<>(); l.add(new E()); return l; }
                }
                static class B extends Ex {
                }
                static abstract class P {
                    int id;
                    void setId(int id) { this.id = id; }
                    List<E> copy() { throw new UnsupportedOperationException(); }
                    void take(E e) { throw new UnsupportedOperationException(); }
                }
                static class Q extends P {
                    private final List<E> es = new ArrayList<>();
                    @Override List<E> copy() { return es; }
                    @Override void take(E e) { es.add(e); }
                }
                static abstract class PA { int id; void setId(int id) { this.id = id; } abstract List<E> copy(); }
                static class QA extends PA {
                    private final List<E> es = new ArrayList<>();
                    @Override List<E> copy() { return es; }
                }
                static class AsgA {
                    private final PA left;
                    AsgA(PA left) { this.left = left; }
                    List<E> copyLeft() { return left.copy(); }
                }
                static class Asg {
                    private final P left;
                    Asg(P left) { this.left = left; }
                    List<E> copyLeft() { return left.copy(); }
                }
            }
            """;

    @DisplayName("a dependent override makes the overridden concrete method dependent")
    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).build()).analyze(ao);
        assertEquals("check=@Dependent put.e=@Dependent fresh=@Independent | "
                     + "A.check=@Dependent A.put.e=@Dependent A.fresh=@Independent"
                     + " | P.copy=@Dependent P.take.e=@Dependent Asg.copyLeft=@Dependent"
                     + " PA.copy=@Dependent AsgA.copyLeft=@Dependent", verdicts(x));
    }

    private static String verdicts(TypeInfo x) {
        TypeInfo ex = x.findSubType("Ex");
        TypeInfo a = x.findSubType("A");
        return "check=" + ind(ex.findUniqueMethod("check", 0))
               + " put.e=" + indP(ex.findUniqueMethod("put", 1))
               + " fresh=" + ind(ex.findUniqueMethod("fresh", 0))
               + " | A.check=" + ind(a.findUniqueMethod("check", 0))
               + " A.put.e=" + indP(a.findUniqueMethod("put", 1))
               + " A.fresh=" + ind(a.findUniqueMethod("fresh", 0))
               + " | P.copy=" + ind(x.findSubType("P").findUniqueMethod("copy", 0))
               + " P.take.e=" + indP(x.findSubType("P").findUniqueMethod("take", 1))
               + " Asg.copyLeft=" + ind(x.findSubType("Asg").findUniqueMethod("copyLeft", 0))
               + " PA.copy=" + ind(x.findSubType("PA").findUniqueMethod("copy", 0))
               + " AsgA.copyLeft=" + ind(x.findSubType("AsgA").findUniqueMethod("copyLeft", 0));
    }

    private static Object ind(MethodInfo m) {
        return m.analysis().getOrNull(INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class);
    }

    private static Object indP(MethodInfo m) {
        return m.parameters().getFirst().analysis().getOrNull(INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.class);
    }
}
