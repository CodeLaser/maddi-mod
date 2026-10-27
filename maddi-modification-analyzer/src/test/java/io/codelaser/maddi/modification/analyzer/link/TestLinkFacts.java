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

package io.codelaser.maddi.modification.analyzer.link;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.LinkFacts;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.LinkFactsImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LinkFactsImpl}: what a method's return links say about its result, for the Rust printer (an owned value or
 * a borrow, and from what). The shapes are those the rust-llvm experiment found rustc rejecting under the
 * independence verdicts alone: a builder's {@code build()} and a {@code copy} printed as {@code &T}, a parameter kept in
 * a new object without a lifetime (maddi-mod scripts/rust-llvm).
 */
public class TestLinkFacts extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.Collections;
            import java.util.List;

            public class X {
                private final List<String> list = new ArrayList<>();
                private String name;

                List<String> getList() { return list; }
                String first(List<String> l) { return l.get(0); }
                List<String> same(List<String> l) { return l; }
                List<String> copy(List<String> l) { return new ArrayList<>(l); }
                List<String> fresh() { return new ArrayList<>(); }
                List<String> either(boolean b, List<String> l) { return b ? list : l; }
                List<String> view() { return Collections.unmodifiableList(list); }
                Holder hold(List<String> l) { return new Holder(l); }
                Holder build() { return new Holder(list); }
                int size() { return list.size(); }
                void clear() { list.clear(); }

                static class Holder {
                    private final List<String> held;
                    Holder(List<String> held) { this.held = held; }
                    List<String> held() { return held; }
                }
            }
            """;

    /*
     result / receiver in the result / parameters kept in the result
     */
    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            // a getter, and one of a parameter's elements, and the parameter itself: borrows
            Map.entry("getList", "EXISTING/receiver/[]"),
            Map.entry("first", "EXISTING/-/[l]"),
            Map.entry("same", "EXISTING/-/[l]"),
            // a new list sharing the parameter's elements: owned, but no longer than the elements live
            Map.entry("copy", "NEW/-/[l]"),
            Map.entry("fresh", "NEW/-/[]"),
            Map.entry("either", "EXISTING/receiver/[l]"),
            // a new view of the field: owned, borrowing the receiver
            Map.entry("view", "NEW/receiver/[]"),
            // a new object keeping the parameter (Items.copy, E0621), and a builder's build() (E0515)
            Map.entry("hold", "NEW/-/[l]"),
            Map.entry("build", "NEW/receiver/[]"),
            Map.entry("size", "NEW/-/[]"),
            Map.entry("clear", "null/-/[]")));

    @DisplayName("the result: new or existing, and what of the receiver and the parameters it is or holds")
    @Test
    public void test() throws IOException {
        assertEquals(EXPECTED, run(false));
    }

    @DisplayName("... and the same with the MODREACH cutover on")
    @Test
    public void testModReach() throws IOException {
        assertEquals(EXPECTED, run(true));
    }

    private Map<String, String> run(boolean modReach) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(modReach).build());
        analyzer.analyze(analysisOrder);
        LinkFacts facts = new LinkFactsImpl(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        for (String m : EXPECTED.keySet()) {
            MethodInfo mi = x.methods().stream().filter(mm -> mm.name().equals(m)).findFirst().orElseThrow();
            actual.put(m, facts.result(mi) + "/" + (facts.receiverInResult(mi) ? "receiver" : "-") + "/"
                          + facts.keptInResult(mi).stream().map(ParameterInfo::name).sorted()
                                  .collect(Collectors.joining(",", "[", "]")));
        }
        System.out.println("### modReach=" + modReach + " " + actual);
        return actual;
    }
}
