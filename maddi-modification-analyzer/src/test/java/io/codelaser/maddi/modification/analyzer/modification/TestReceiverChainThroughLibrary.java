/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#24 R1. A library method that modifies its receiver ({@code Iterator.next()}) is one node of the
 * reachability graph, seeded once, with an E2 edge to the receiver nodes of every call site. Those edges must stop at
 * the object the call modifies: the iterator, not the collection it was obtained from. In langchain4j,
 * {@code comparisonValues.iterator().next()} marked the field {@code IsIn.comparisonValues} modified, and
 * {@code DefaultRetrievalAugmentor.process}'s {@code queries} with it.
 */
public class TestReceiverChainThroughLibrary extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.Iterator;
            import java.util.HashSet;
            import java.util.List;
            import java.util.Map;
            import java.util.Set;
            class R1 {
                static IllegalArgumentException illegalArgument(String format, Object... args) {
                    return new IllegalArgumentException(format.formatted(args));
                }
                static class Template {
                    private final Set<String> allVariables;
                    private final Iterable<String> names;
                    Template(Set<String> allVariables, List<String> names) {
                        this.allVariables = new HashSet<>(allVariables);
                        this.names = new ArrayList<>(names);
                    }
                    void ensureAllVariablesProvided(Map<String, Object> variables) {
                        for (String variable : allVariables) {
                            if (!variables.containsKey(variable)) throw illegalArgument("missing %s", variable);
                        }
                    }
                    int count() {
                        int n = 0;
                        for (String name : names) n += name.length();
                        return n;
                    }
                }
                static class IsIn {
                    private final List<String> values;
                    IsIn(List<String> values) { this.values = new ArrayList<>(values); }
                    boolean test(Object o) { return values.iterator().next().equals(o); }
                    int size() { return values.size(); }
                }
                static String first(List<String> queries) {
                    Iterator<String> it = queries.iterator();
                    return it.hasNext() ? it.next() : null;
                }
                static String firstChained(List<String> queries) {
                    return queries.iterator().next();
                }
                static String advance(Iterator<String> it) { return it.next(); }
                static void dropFirst(List<String> list) {
                    Iterator<String> it = list.iterator();
                    it.next();
                    it.remove();
                }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private String verdicts(boolean modReach) {
        TypeInfo X = javaInspector.parse("a.b.R1", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(modReach)
                .build()).analyze(ao);
        TypeInfo isIn = X.findSubType("IsIn");
        TypeInfo template = X.findSubType("Template");
        return "allVariables=" + template.getFieldByName("allVariables", true).isUnmodified()
               + " names=" + template.getFieldByName("names", true).isUnmodified()
               + " values=" + isIn.getFieldByName("values", true).isUnmodified()
               + " first=" + method(X, "first").parameters().getFirst().isUnmodified()
               + " firstChained=" + method(X, "firstChained").parameters().getFirst().isUnmodified()
               + " advance=" + method(X, "advance").parameters().getFirst().isUnmodified()
               + " dropFirst=" + method(X, "dropFirst").parameters().getFirst().isUnmodified();
    }

    private static final String EXPECTED = "allVariables=true names=true values=true first=true firstChained=true advance=false dropFirst=false";

    @DisplayName("Iterator.next() modifies the iterator, not the collection it came from: fixpoint")
    @Test
    public void fixpoint() {
        assertEquals(EXPECTED, verdicts(false));
    }

    @DisplayName("Iterator.next() modifies the iterator, not the collection it came from: reachability")
    @Test
    public void reachability() {
        assertEquals(EXPECTED, verdicts(true));
    }
}
