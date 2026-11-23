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
 * CodeLaser/maddi-mod#103: fernflower's {@code Statement.getEdges(EdgeType, EdgeDirection)} looks its enum argument up
 * in an {@code EnumMap}. In some analyses of the same sources the parameter came out modified, as if the enum were
 * mutable: its IMMUTABLE_TYPE undecided when the method's links were computed. Every order must give the same answer.
 */
public class TestEnumKeyUndecided extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class S {
                enum ET {
                    REGULAR(1), EXCEPTION(2), ALL(0x80000000);
                    private final int mask;
                    ET(int mask) { this.mask = mask; }
                    public int mask() { return mask; }
                    public static ET[] types() { return new ET[]{REGULAR, EXCEPTION}; }
                }
                enum Dir { FORWARD, BACKWARD }
                private final EnumMap<ET, List<Object>> pred = new EnumMap<>(ET.class);
                private final EnumMap<ET, List<Object>> succ = new EnumMap<>(ET.class);
                void add(ET type, Object e) { pred.computeIfAbsent(type, k -> new ArrayList<>()).add(e); }
                List<Object> getEdges(ET type, Dir direction) {
                    EnumMap<ET, List<Object>> map = direction == Dir.BACKWARD ? pred : succ;
                    List<Object> res;
                    if ((type.mask() & (type.mask() - 1)) == 0) {
                        res = map.get(type);
                        res = res == null ? new ArrayList<>() : new ArrayList<>(res);
                    } else {
                        res = new ArrayList<>();
                        for (ET et : ET.types()) {
                            if ((type.mask() & et.mask()) != 0) {
                                List<Object> lst = map.get(et);
                                if (lst != null) res.addAll(lst);
                            }
                        }
                    }
                    return res;
                }
            }
            """;

    private String run(boolean modReach, boolean reverse) {
        TypeInfo t = javaInspector.parse("a.b.S", INPUT);
        List<Info> ao = prepWork(t);
        if (reverse) {
            ao = new ArrayList<>(ao);
            Collections.reverse(ao);
        }
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30).setModificationViaReachability(modReach).build()).analyze(ao);
        MethodInfo getEdges = t.findUniqueMethod("getEdges", 2);
        TypeInfo et = t.subTypes().stream().filter(s -> "ET".equals(s.simpleName())).findFirst().orElseThrow();
        return "type=" + getEdges.parameters().get(0).isUnmodified()
               + " direction=" + getEdges.parameters().get(1).isUnmodified()
               + " ET=" + et.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.MUTABLE);
    }

    private static void check(String s) {
        assertTrue(s.startsWith("type=true direction=true"), s);
    }

    @Test public void reachability() { check(run(true, false)); }
    @Test public void fixpoint() { check(run(false, false)); }
    @Test public void reachabilityReversed() { check(run(true, true)); }
    @Test public void fixpointReversed() { check(run(false, true)); }
}
