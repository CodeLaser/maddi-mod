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
 * A subtype waits for the verdict of an undecided abstract source supertype (CodeLaser/maddi-mod#82). It used to
 * judge such a supertype by its concrete methods alone: {@code Base} has only {@code size()}, which is independent,
 * so {@code CopyingBox} came out {@code @Independent} while {@code Base} was still waiting for the fold of its
 * abstract {@code items()} over the implementations. {@code go()} never revisits an {@code @Independent} type, so
 * the early answer stuck, and in a parallel pass whether it was given depended on thread timing.
 * <p>
 * {@code LiveBox.items()} hands out its mutable list, which makes {@code Base.items()}, and so {@code Base}, dependent.
 */
public class TestSubtypeWaitsForAbstractSupertype extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;

            public class X {
                abstract static class Base<T> {
                    abstract List<T> items();

                    int size() {
                        return items().size();
                    }
                }

                static final class CopyingBox<T> extends Base<T> {
                    @Override
                    List<T> items() {
                        return new ArrayList<>();
                    }
                }

                static final class LiveBox<T> extends Base<T> {
                    private final List<T> list = new ArrayList<>();

                    @Override
                    List<T> items() {
                        return list;
                    }

                    void add(T t) {
                        list.add(t);
                    }
                }
            }
            """;

    @DisplayName("a subtype is not frozen @Independent while its abstract supertype is undecided")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10).build());
        analyzer.analyze(analysisOrder);
        for (String name : List.of("Base", "CopyingBox", "LiveBox")) {
            System.out.println("### " + name + " " + independent(x.findSubType(name)));
        }

        assertSame(ValueImpl.IndependentImpl.DEPENDENT, independent(x.findSubType("Base")), "precondition");
        assertSame(ValueImpl.IndependentImpl.DEPENDENT, independent(x.findSubType("CopyingBox")));
    }

    private static Value.Independent independent(TypeInfo t) {
        return t.analysis().getOrNull(PropertyImpl.INDEPENDENT_TYPE, ValueImpl.IndependentImpl.class);
    }
}
