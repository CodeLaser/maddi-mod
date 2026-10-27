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

package io.codelaser.maddi.modification.analyzer.eventual;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.SingleIterationAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * No mark, no eventual verdict. vavr's {@code MatchError}: a {@code @FinalFields} type (there its Throwable super caps
 * it; here a super with a modified final list) holding one {@code final Object} field. The field's name becomes a label -- Object is immutable-hc with hidden
 * content -- and the CodeLaser/maddi#51 relaxation that writes an after-mark {@code @FinalFields} equal to the unconditional one
 * certified "eventually @FinalFields after obj": a transition that does not exist. On vavr, which has no mark at all,
 * that relaxation and its contraction twin certified 119 such verdicts.
 */
public class TestEventualNeedsAMark extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import java.util.ArrayList;
            import java.util.List;
            public class M {
              static class Base {
                private final List<String> notes = new ArrayList<>();
                void note(String s) { notes.add(s); }
              }
              static class MatchError extends Base {
                private final Object obj;
                MatchError(Object obj) { this.obj = obj; }
                public Object getObject() { return obj; }
              }
            }
            """;

    @DisplayName("a markless type gets no eventual verdict from a field-name label")
    @Test
    public void test() {
        TypeInfo M = javaInspector.parse("M", INPUT);
        List<Info> ao = prepWork(M);
        // the relaxation only writes in the cycle-breaking phase (a weak verdict is deferred before it); a fixture
        // this small never leaves anything undecided, so switch cycle breaking on by hand after a first pass
        SingleIterationAnalyzerImpl sia = (SingleIterationAnalyzerImpl) analyzer;
        for (int i = 0; i < 3; i++) sia.go(ao, i > 0, i == 0);
        TypeInfo matchError = M.findSubType("MatchError");
        Value.EventuallyImmutable ev = matchError.analysis().getOrDefault(PropertyImpl.EVENTUALLY_IMMUTABLE_TYPE,
                ValueImpl.EventuallyImmutableImpl.NOT_EVENTUAL);
        assertFalse(ev.isEventual(), "nothing in this universe ever commits, yet: " + ev);
    }
}
