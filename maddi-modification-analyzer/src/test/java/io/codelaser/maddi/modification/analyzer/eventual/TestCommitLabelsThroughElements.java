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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.EventualCluster;

/**
 * CodeLaser/maddi-mod#133: an after-mark label carried across a call on the ELEMENTS of a final container field.
 * {@code StatementImpl.outputBuilder} prints its annotations as {@code annotations().stream().map(ae ->
 * ae.print(q))}; {@code ae.print} modifies {@code ae} before the annotation's own mark only, so once the
 * container's content has committed the call modifies nothing. Without a label the statement's {@code print}
 * stays modifying after the mark, the abstract {@code Element.print} with it, and the Element family at
 * {@code @FinalFields} after the mark.
 */
public class TestCommitLabelsThroughElements extends CommonTest {

    private Set<String> nonModAfter(TypeInfo typeInfo, String methodName, int params) {
        return typeInfo.findUniqueMethod(methodName, params).analysis()
                .getOrDefault(PropertyImpl.EVENTUALLY_NON_MODIFYING_METHOD, ValueImpl.SetOfStringsImpl.EMPTY_SET)
                .set();
    }

    @Language("java")
    private static final String INPUT = """
            import java.util.List;
            import io.codelaser.maddi.support.EventuallyFinalOnDemand;

            public class B {
              static class T {
                private final EventuallyFinalOnDemand<String> inspection = new EventuallyFinalOnDemand<>();
                public void commit(String s) { inspection.setFinal(s); }
                public int size() { return inspection.get().length(); }
              }
              static class S {
                private final List<T> parts;
                S(List<T> parts) { this.parts = List.copyOf(parts); }
                public int viaStream() { return parts.stream().mapToInt(p -> p.size()).sum(); }
                public int viaFor() {
                  int sum = 0;
                  for (T p : parts) sum += p.size();
                  return sum;
                }
                public void viaForEach() { parts.forEach(p -> p.size()); }
                public int viaAccessor() { return parts().stream().mapToInt(p -> p.size()).sum(); }
                public List<T> parts() { return parts; }
                // the StatementImpl.outputBuilder shape: a fresh builder fed from a local derived from the field,
                // and from the field through its accessor
                public String viaLocal() {
                  List<T> some = parts.isEmpty() ? parts : parts.stream().filter(p -> p.size() > 0).toList();
                  StringBuilder sb = new StringBuilder();
                  sb.append(some.stream().map(p -> "" + p.size()).collect(java.util.stream.Collectors.joining(",")));
                  sb.append(parts().stream().map(p -> "" + p.size()).collect(java.util.stream.Collectors.joining(",")));
                  return sb.toString();
                }
              }
            }
            """;

    @DisplayName("a pre-mark-modifying call on an element of a final container field is excused by the field's label")
    @Test
    public void test() {
        boolean saved = EventualCluster.ENABLED;
        EventualCluster.ENABLED = true;
        try {
            TypeInfo B = javaInspector.parse("B", INPUT);
            analyzer.go(prepWork(B));
            TypeInfo T = B.findSubType("T");
            TypeInfo S = B.findSubType("S");

            assertEquals(Set.of("inspection"), nonModAfter(T, "size", 0));
            Map<String, Set<String>> actual = new TreeMap<>();
            for (String m : List.of("viaStream", "viaFor", "viaForEach", "viaAccessor", "viaLocal")) {
                actual.put(m + "/" + S.findUniqueMethod(m, 0).analysis()
                        .getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class), nonModAfter(S, m, 0));
            }
            assertEquals("{viaAccessor/false=[parts], viaFor/false=[parts], viaForEach/false=[parts], viaLocal/false=[parts],"
                    + " viaStream/false=[parts]}",
                    actual.toString());
        } finally {
            EventualCluster.ENABLED = saved;
        }
    }
}
