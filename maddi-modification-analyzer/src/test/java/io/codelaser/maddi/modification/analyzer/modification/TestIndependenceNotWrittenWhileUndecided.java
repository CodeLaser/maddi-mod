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

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A type's independence is not written while its own fields and abstract methods are undecided. It was:
 * {@code computeIndependentType} returned {@code hierarchy.min(own)}, and {@code min(null)} is the left operand, so
 * an interface whose abstract methods had no verdict yet came out {@code @Independent} -- and {@code go()} never
 * revisits an {@code @Independent} type. Guava's {@code Multimap} is the corpus case: its own computation settles on
 * {@code @Dependent} ({@code removeAll}, {@code keySet} hand out live views), its published verdict stayed
 * {@code @Independent}, and every subtype reading it inherited the error.
 * <p>
 * {@code Box.items()} is abstract; its verdict is the fold over its implementations, available only after
 * {@code ListBox} has been analysed. {@code ListBox.items()} returns its mutable list.
 */
public class TestIndependenceNotWrittenWhileUndecided extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;

            public class X {
                interface Box<T> {
                    List<T> items();
                }

                interface NamedBox<T> extends Box<T> {
                    String name();
                }

                static final class ListBox<T> implements NamedBox<T> {
                    private final List<T> list = new ArrayList<>();

                    @Override
                    public List<T> items() {
                        return list;
                    }

                    @Override
                    public String name() {
                        return "list";
                    }

                    void add(T t) {
                        list.add(t);
                    }
                }
            }
            """;

    @DisplayName("an interface whose abstract accessor turns out dependent is not frozen @Independent")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        analyzer.analyze(analysisOrder);
        TypeInfo box = x.findSubType("Box");
        System.out.println("### Box.items " + box.findUniqueMethod("items", 0).analysis()
                .getOrNull(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class));
        for (String name : List.of("Box", "NamedBox", "ListBox")) {
            System.out.println("### " + name + " " + independent(x.findSubType(name)));
        }

        assertSame(ValueImpl.IndependentImpl.DEPENDENT, box.findUniqueMethod("items", 0).analysis()
                .getOrNull(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class), "precondition");
        assertSame(ValueImpl.IndependentImpl.DEPENDENT, independent(x.findSubType("ListBox")));
        assertSame(ValueImpl.IndependentImpl.DEPENDENT, independent(box), "items() hands out a live list");
        assertSame(ValueImpl.IndependentImpl.DEPENDENT, independent(x.findSubType("NamedBox")));
    }

    private static Value.Independent independent(TypeInfo t) {
        return t.analysis().getOrNull(PropertyImpl.INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class);
    }
}
