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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.TreeSet;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Message;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * Hand-written {@code @Fresh}, {@code @Borrowed} and {@code @Owned} are checked against the computed verdicts, and a
 * negative verdict carries its reason (OWNERSHIP_CULPRIT) for the IDE's "not @Fresh: ...".
 */
public class TestOwnershipContracts extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import io.codelaser.maddi.annotation.Borrowed;
            import io.codelaser.maddi.annotation.Fresh;
            import io.codelaser.maddi.annotation.Owned;

            public class X {
                static class Right {
                    @Owned private final List<String> list = new ArrayList<>();
                    @Fresh List<String> copy() { return new ArrayList<>(list); }
                    int count(@Borrowed List<String> other) { return other.size(); }
                }
                static class Wrong {
                    @Owned private final List<String> list = new ArrayList<>();
                    private List<String> kept;
                    @Fresh List<String> list() { return list; }
                    void keep(@Borrowed List<String> l) { kept = l; }
                    int size() { return kept.size(); }
                }
            }
            """;

    @DisplayName("a hand-written annotation the verdict does not uphold is a contract violation")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setTrackObjectCreations(true).build())
                .analyze(analysisOrder);

        OwnershipPass.Report report = new OwnershipPass(false).go(analysisOrder);
        List<Message> violations = OwnershipPass.contractViolations(report);
        assertTrue(violations.stream().allMatch(m -> OwnershipPass.CONTRACT_VIOLATION.equals(m.category())));
        TreeSet<String> texts = new TreeSet<>();
        violations.forEach(m -> texts.add(m.message().substring(0, m.message().indexOf(", violating"))));
        assertEquals("[field a.b.X.Wrong.list is not @Owned, method a.b.X.Wrong.list() is not @Fresh, "
                     + "parameter 'l' of a.b.X.Wrong.keep(java.util.List) is not @Borrowed]",
                texts.toString(), violations::toString);

        OwnershipPass.writeCulprits(report);
        TypeInfo wrong = x.findSubType("Wrong");
        MethodInfo list = wrong.findUniqueMethod("list", 0);
        assertEquals(1, culprit(list).steps().size());
        assertTrue(culprit(wrong.getFieldByName("list", true)) != null);
        assertTrue(culprit(wrong.findUniqueMethod("keep", 1).parameters().getFirst()) != null);
        TypeInfo right = x.findSubType("Right");
        assertNull(culprit(right.findUniqueMethod("copy", 0)), "a positive verdict has no culprit");
        assertNull(culprit(right.getFieldByName("list", true)));
    }

    private static ValueImpl.CulpritImpl culprit(Info info) {
        return info.analysis().getOrNull(PropertyImpl.OWNERSHIP_CULPRIT, ValueImpl.CulpritImpl.class);
    }
}
