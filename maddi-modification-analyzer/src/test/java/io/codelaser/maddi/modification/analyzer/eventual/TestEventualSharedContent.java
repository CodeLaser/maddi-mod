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
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After the mark, what an argument or a return value SHARES decides, not its own type (CodeLaser/maddi#51). The shape of
 * {@code Element.print(Qualification)} and {@code Element.typesReferenced()}: an implementation hands one of its
 * fields -- of an eventually immutable type -- to a mutable collector passed in, and returns it in a fresh
 * {@code Stream}. Both are {@code @Dependent} unconditionally (the field's type is not yet immutable-hc), and neither
 * the collector nor {@code Stream} ever commits; but what they share is committed content once the mark has passed.
 * The veto: sharing a plain {@code ArrayList} field keeps the type capped.
 */
public class TestEventualSharedContent extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            import io.codelaser.maddi.annotation.eventual.Mark;
            import io.codelaser.maddi.support.SetOnce;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.stream.Stream;

            public class B {
              static class Node {
                private final SetOnce<String> t = new SetOnce<>();
                @Mark("t") void commit(String s) { t.set(s); }
                String get() { return t.get(); }
              }
              static class Collector {
                private final List<Object> collected = new ArrayList<>();
                void add(Object o) { collected.add(o); }
              }
              interface I {
                @Mark("t") void commit(String s);
                void print(Collector c);
                Stream<Node> nodes();
              }
              static class Impl implements I {
                private final Node node = new Node();
                @Override public void commit(String s) { node.commit(s); }
                @Override public void print(Collector c) { c.add(node); }
                @Override public Stream<Node> nodes() { return Stream.of(node); }
              }
              interface J {
                @Mark("t") void commit(String s);
                void print(Collector c);
              }
              static class JImpl implements J {
                private final Node node = new Node();
                private final List<String> names = new ArrayList<>();
                @Override public void commit(String s) { node.commit(s); }
                @Override public void print(Collector c) { c.add(names); }
              }
            }
            """;

    @DisplayName("sharing eventually immutable content through a parameter or a Stream does not cap the type")
    @Test
    public void test() {
        TypeInfo B = javaInspector.parse("B", INPUT);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
        TypeInfo I = B.findSubType("I");
        TypeInfo J = B.findSubType("J");

        // the premise: unconditionally, both exposures are dependent
        assertTrue(I.findUniqueMethod("print", 1).parameters().getFirst().analysis()
                .getOrDefault(PropertyImpl.INDEPENDENT_PARAMETER, ValueImpl.IndependentImpl.DEPENDENT).isDependent());
        assertTrue(I.findUniqueMethod("nodes", 0).analysis()
                .getOrDefault(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.DEPENDENT).isDependent());

        Value.EventuallyImmutable evI = I.analysis().getOrDefault(PropertyImpl.EVENTUALLY_IMMUTABLE_TYPE,
                ValueImpl.EventuallyImmutableImpl.NOT_EVENTUAL);
        assertTrue(evI.isEventual() && evI.immutableAfterMark().isAtLeastImmutableHC(),
                "after the mark I shares committed content only, is " + evI);

        Value.EventuallyImmutable evJ = J.analysis().getOrDefault(PropertyImpl.EVENTUALLY_IMMUTABLE_TYPE,
                ValueImpl.EventuallyImmutableImpl.NOT_EVENTUAL);
        assertFalse(evJ.isEventual() && evJ.immutableAfterMark().isAtLeastImmutableHC(),
                "J hands out a plain ArrayList field: it must stay capped, is " + evJ);
    }
}
