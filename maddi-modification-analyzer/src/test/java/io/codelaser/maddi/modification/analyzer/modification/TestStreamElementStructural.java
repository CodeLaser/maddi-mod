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
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * A write to an element of the receiver's own {@code List<LV>} is a structural modification of the receiver: the
 * holder's view (maddi-mod#159) makes the elements its accessible content. fernflower's
 * {@code StructLocalVariableTableAttribute.mergeSignatures} writes it through
 * {@code stream().filter(..).findFirst().ifPresent(lv -> lv.signature = ..)}, and the receiver was claimed
 * modified deeply only (the dynamic check's 2a, contradicted in both sampled calls).
 * <p>
 * Each method: the receiver's verdict, deep then structural, U or M. {@code loop} is the control, as is
 * {@code param}: a parameter's list is a root container, whose elements are its hidden content.
 * <p>
 * Three places, each with gate NOELEMENTFUNCTIONREACH: the engine's #99 rule (MethodModification) and its element
 * owners through a call chain's temporaries (ExpressionVisitor), and the reachability pass's #99 seed and summary seed.
 */
public class TestStreamElementStructural extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class Z {
                static class LV { int k; String sig; }
                private List<LV> lvs = new ArrayList<>();
                void loop(String s) { for (LV lv : lvs) if (lv.k == 0) { lv.sig = s; break; } }
                void each(String s) { lvs.forEach(lv -> lv.sig = s); }
                void first(String s) { lvs.stream().findFirst().ifPresent(lv -> lv.sig = s); }
                void filtered(String s) { lvs.stream().filter(t -> t.k == 0).findFirst().ifPresent(lv -> lv.sig = s); }
                static void param(List<LV> in, String s) { in.forEach(lv -> lv.sig = s); }
                void merge(List<LV> other) {
                    other.forEach(type -> lvs.stream().filter(t -> t.k == type.k).findFirst().ifPresent(lv -> lv.sig = type.sig));
                }
            }
            """;

    @DisplayName("a write to an element of the receiver's own list, through a stream and an Optional, is structural")
    @Test
    public void test() throws Exception {
        // param is the control: a parameter List<LV> is a root container, its elements its hidden content (#159)
        assertEquals("loop=MM each=MM first=MM filtered=MM merge=MM param.in=MU"
                     + " | loop=MM each=MM first=MM filtered=MM merge=MM param.in=MU",
                run(false) + " | " + run(true));
    }

    private String run(boolean reach) throws Exception {
        beforeEach();
        TypeInfo z = javaInspector.parse("a.b.Z", INPUT);
        List<Info> ao = prepWork(z);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reach).build()).analyze(ao);
        return List.of("loop", "each", "first", "filtered", "merge").stream().map(n -> {
            MethodInfo m = z.methods().stream().filter(x -> x.name().equals(n)).findFirst().orElseThrow();
            return n + "=" + (m.isNonModifying() ? 'U' : 'M') + (m.isStructurallyNonModifying() ? 'U' : 'M');
        }).collect(Collectors.joining(" ")) + " param.in=" + param(z);
    }

    private static String param(TypeInfo z) {
        var in = z.methods().stream().filter(x -> x.name().equals("param")).findFirst().orElseThrow().parameters()
                .getFirst();
        return (in.isUnmodified() ? "U" : "M") + (in.isStructurallyUnmodified() ? "U" : "M");
    }
}
