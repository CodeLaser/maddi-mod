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

package io.codelaser.maddi.modification.analyzer.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * Rule 1 in a closed world: a field that is not private, but that no body outside its primary type mentions, is
 * private in effect.
 */
public class TestEffectivelyPrivate extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                final List<String> unseen = new ArrayList<>();
                final List<String> seen = new ArrayList<>();
                protected final List<String> sub = new ArrayList<>();
                static class Inner { int n(X x) { return x.unseen.size(); } }
                int size() { return unseen.size() + seen.size() + sub.size(); }
            }
            class Other {
                int n(X x) { return x.seen.size(); }
            }
            """;

    @DisplayName("closed world: a field no other type mentions is private in effect")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = new ArrayList<>();
        for (TypeInfo t : x.compilationUnit().types()) analysisOrder.addAll(bundle.prepAnalyzer().doPrimaryType(t));
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setTrackObjectCreations(true).build())
                .analyze(analysisOrder);
        assertEquals(Map.of("unseen", "NOT_OWNED", "seen", "NOT_OWNED", "sub", "NOT_OWNED"),
                verdicts(new OwnershipPass(false).go(analysisOrder), "open"));
        // Inner is in X's primary type; Other is not
        assertEquals(Map.of("unseen", "OWNED", "seen", "NOT_OWNED", "sub", "OWNED"),
                verdicts(new OwnershipPass(true).go(analysisOrder), "closed"));
    }

    private static Map<String, String> verdicts(OwnershipPass.Report report, String world) {
        Map<String, String> map = new TreeMap<>();
        report.owned().forEach((f, o) -> {
            if (!"X".equals(f.owner().simpleName())) return;
            map.put(f.name(), o.name());
            System.out.println("EFFECTIVE " + world + " " + f.name() + " = " + o + "  "
                               + report.notOwnedReason().getOrDefault(f, ""));
        });
        return map;
    }
}
