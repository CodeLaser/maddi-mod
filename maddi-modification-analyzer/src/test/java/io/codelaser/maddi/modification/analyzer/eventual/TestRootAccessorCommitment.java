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

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.EventualCluster;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The {@code ParameterizedTypeImpl.isJavaUtilList()} shape (dogfood keystone {@code ParameterizedType},
 * 2026-09-27): a non-modifying root accessor with several returns ({@code bestTypeInfo()}: the own
 * {@code typeInfo} field, the first type argument's, {@code null}) hands out root-derived content that a later
 * call modifies before ITS mark ({@code T.supers()} reads an {@code EventuallyFinalOnDemand}). The method is
 * honestly modifying (composed with MODREACH, the link {@code bestTypeInfo ← this.typeInfo} being real since the
 * fork/join linker), and its eventual non-modification must name the labels after which the handed-out content
 * is committed: the root's own commitment, not ∅. With ∅ the walk wrote no promise at all, and the type sank
 * from {@code @Immutable(hc=true)} to {@code @FinalFields} after its marks.
 */
public class TestRootAccessorCommitment extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import io.codelaser.maddi.support.EventuallyFinalOnDemand;
            import java.util.List;

            public class A {
              static class T {
                private final EventuallyFinalOnDemand<List<T>> inspection = new EventuallyFinalOnDemand<>();
                public void commit(List<T> supers) { inspection.setFinal(supers); }
                public List<T> supers() { return inspection.get(); }
              }
              static class P {
                private final T typeInfo;
                private final List<P> parameters;
                private final String name;
                P(T typeInfo, List<P> parameters, String name) {
                  this.typeInfo = typeInfo;
                  this.parameters = List.copyOf(parameters);
                  this.name = name;
                }
                public T bestTypeInfo() {
                  if (typeInfo != null) return typeInfo;
                  if (!parameters.isEmpty()) return parameters.getFirst().bestTypeInfo();
                  return null;
                }
                public boolean isList() {
                  T best = bestTypeInfo();
                  return best != null && best.supers().stream().anyMatch(t -> t == best);
                }
              }
            }
            """;

    private static Set<String> nonModAfter(TypeInfo typeInfo, String methodName, int params) {
        return typeInfo.findUniqueMethod(methodName, params).analysis()
                .getOrDefault(PropertyImpl.EVENTUALLY_NON_MODIFYING_METHOD, ValueImpl.SetOfStringsImpl.EMPTY_SET)
                .set();
    }

    @DisplayName("a modifying call on a root accessor's result is excused after the root's own commitment")
    @Test
    public void test() {
        boolean saved = EventualCluster.ENABLED;
        EventualCluster.ENABLED = true;
        try {
            TypeInfo A = javaInspector.parse("A", INPUT);
            var iterating = new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10)
                    .setModificationViaReachability(true)
                    .build());
            iterating.analyze(prepWork(A));
            TypeInfo T = A.findSubType("T");
            TypeInfo P = A.findSubType("P");

            // the callee: modifying before 'inspection' is committed, not after
            assertEquals(Set.of("inspection"), nonModAfter(T, "supers", 0));
            MethodInfo isList = P.findUniqueMethod("isList", 0);
            // the caller is honestly modifying: the accessor hands out this.typeInfo, and supers() modifies it
            assertFalse(isList.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.TRUE)
                    .isTrue(), "isList must read as modifying (through this.typeInfo)");
            // ... and eventually non-modifying after the root's own commitment (name is a String: harmless)
            assertEquals(Set.of("typeInfo", "parameters"), nonModAfter(P, "isList", 0));
            System.out.println("P eventual: " + P.analysis().getOrNull(PropertyImpl.EVENTUALLY_IMMUTABLE_TYPE,
                    ValueImpl.EventuallyImmutableImpl.class));
        } finally {
            EventualCluster.ENABLED = saved;
        }
    }
}
