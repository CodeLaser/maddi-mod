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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#103: fernflower's {@code SecondaryFunctionsHelper.identifySecondaryFunctions(Statement,
 * VarProcessor)} hands {@code varProc} to its overload for expressions, which modifies it ({@code setVarType}). Each
 * overload calls only itself: two recursions, not one. #127's rule for a call back into the recursion (an undecided
 * verdict marks nothing) took any call between two recursive methods for one, so the statement overload's
 * {@code varProc} came out unmodified whenever its links were computed before the expression overload had decided,
 * which in the parallel first pass is a matter of thread timing (2 of 6 runs of TestIncrementalFernflower).
 */
public class TestRecursionAcrossCycles extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class H {
                static class VP { int type; void setType(int t) { type = t; } int getType() { return type; } }
                static class Stat { final List<Object> objects = new ArrayList<>(); }
                static class Expr { final List<Expr> subs = new ArrayList<>(); }
                static boolean identify(Stat stat, VP vp) {
                    for (Object o : stat.objects) {
                        if (o instanceof Stat s) {
                            if (identify(s, vp)) return true;
                        } else if (o instanceof Expr e) {
                            if (identify(e, true, vp) != null) return true;
                        }
                    }
                    return false;
                }
                private static Expr identify(Expr expr, boolean top, VP vp) {
                    for (Expr sub : expr.subs) {
                        Expr r = identify(sub, false, vp);
                        if (r != null) return r;
                    }
                    if (top) vp.setType(3);
                    return null;
                }
                static int read(Stat stat, VP vp) {
                    int s = vp.getType();
                    for (Object o : stat.objects) if (o instanceof Stat st) s += read(st, vp); else s += read((Expr) o, vp);
                    return s;
                }
                private static int read(Expr expr, VP vp) {
                    int s = vp.getType();
                    for (Expr sub : expr.subs) s += read(sub, vp);
                    return s;
                }
            }
            """;

    private String run(boolean modReach, boolean callerFirst) {
        TypeInfo t = javaInspector.parse("a.b.H", INPUT);
        List<Info> ao = prepWork(t);
        if (callerFirst) {
            // what a parallel first pass does when the caller's worker gets there first: the caller's links are
            // computed while the callee's verdicts are still undecided
            ao = new ArrayList<>(ao);
            Collections.reverse(ao);
        }
        for (MethodInfo mi : t.methods()) {
            assertTrue(mi.analysis().getOrDefault(PropertyImpl.RECURSIVE_METHOD, ValueImpl.BoolImpl.FALSE).isTrue(),
                    "the shape needs every method recursive: " + mi);
        }
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30).setModificationViaReachability(modReach).build()).analyze(ao);
        TreeMap<String, String> out = new TreeMap<>();
        for (MethodInfo mi : t.methods()) {
            String kind = mi.parameters().getFirst().parameterizedType().typeInfo().simpleName();
            for (ParameterInfo pi : mi.parameters()) {
                if ("vp".equals(pi.name())) out.put(mi.name() + "(" + kind + "):vp", String.valueOf(pi.isUnmodified()));
            }
        }
        return out.toString();
    }

    private static final String EXPECTED = "{identify(Expr):vp=false, identify(Stat):vp=false, read(Expr):vp=true, "
                                           + "read(Stat):vp=true}";

    @DisplayName("#103: a recursive callee of another recursion modifies the argument (reachability)")
    @Test
    public void reachability() {
        assertEquals(EXPECTED, run(true, false));
    }

    @DisplayName("#103: a recursive callee of another recursion modifies the argument (fixpoint alone)")
    @Test
    public void fixpoint() {
        assertEquals(EXPECTED, run(false, false));
    }

    @DisplayName("#103: the same, the caller's links computed first (reachability)")
    @Test
    public void reachabilityCallerFirst() {
        assertEquals(EXPECTED, run(true, true));
    }

    @DisplayName("#103: the same, the caller's links computed first (fixpoint alone)")
    @Test
    public void fixpointCallerFirst() {
        assertEquals(EXPECTED, run(false, true));
    }
}
