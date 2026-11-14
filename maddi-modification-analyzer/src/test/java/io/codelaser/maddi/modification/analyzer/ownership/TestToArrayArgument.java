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

import java.io.IOException;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * {@code Collection.toArray(T[] a)} writes the elements into {@code a} when they fit, and then returns {@code a}
 * itself (CodeLaser/maddi#211). A method that delegates to it (gson's {@code NonNullElementWrapperList}) therefore
 * modifies its argument. It may also return it, so the argument is not borrowed: that half is still open, because a
 * dependent return value (the hint's {@code @Independent(dependentReturnValue = true)}) gives a view link, not the
 * {@code ret ← 0:a} that BorrowedParameters' rule 1 reads. When it is fixed, toArray's line becomes borrowed=false.
 */
public class TestToArrayArgument extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X<E> {
                private final List<E> delegate = new ArrayList<>();
                public <T> T[] toArray(T[] a) { return delegate.toArray(a); }
                public int count(Object[] a) { return delegate.size() + a.length; }
            }
            """;

    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setOwnership(true).build())
                .analyze(analysisOrder);
        new OwnershipPass().go(analysisOrder);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("toArray", "count")) {
            MethodInfo m = x.findUniqueMethod(name, 1);
            ParameterInfo a = m.parameters().getFirst();
            sb.append(name).append(" unmodified=").append(a.isUnmodified())
                    .append(" borrowed=").append(a.analysis().getOrNull(PropertyImpl.BORROWED_PARAMETER,
                            ValueImpl.BoolImpl.class) instanceof ValueImpl.BoolImpl b && b.isTrue())
                    .append('\n');
        }
        assertEquals("""
                toArray unmodified=false borrowed=true
                count unmodified=true borrowed=true
                """, sb.toString());
    }
}
