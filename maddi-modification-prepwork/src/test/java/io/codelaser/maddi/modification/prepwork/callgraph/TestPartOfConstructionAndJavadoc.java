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

package io.codelaser.maddi.modification.prepwork.callgraph;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField.PART_OF_CONSTRUCTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <b>Documenting a private method is not calling it.</b> {@code computePartOfConstruction} propagates
 * "called from construction" along every method→method edge of the call graph with no weight test, and a javadoc
 * {@code {@link #helper()}} in a constructor's comment is such an edge (weight {@code d}, one doc reference).
 * <p>
 * Here the constructor documents {@code helper} and nothing calls it. {@code helper} is private, so it is a
 * candidate and is never "called from outside"; whether it is reported as part of construction therefore reads the
 * doc edge alone. {@code documentedAndCalled} is the control: the same shape, with the call written.
 */
public class TestPartOfConstructionAndJavadoc extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                private final int x;
                private final int y;

                /**
                 * Sets up the object; see {@link #documentedOnly()} for the rule it follows.
                 */
                X() {
                    x = 1;
                    y = documentedAndCalled();
                }

                private int documentedOnly() {
                    return x;
                }

                private int documentedAndCalled() {
                    return 2;
                }
            }
            """;

    @DisplayName("a constructor's javadoc link to a private method: does it make it part of construction?")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse(ABX, INPUT);
        PrepAnalyzer prepAnalyzer = new PrepAnalyzer(runtime);
        prepAnalyzer.doPrimaryType(X);

        Value.SetOfInfo setOfInfo = X.analysis().getOrNull(PART_OF_CONSTRUCTION, ValueImpl.SetOfInfoImpl.class);
        assertNotNull(setOfInfo);
        assertEquals("[a.b.X.<init>(), a.b.X.documentedAndCalled()]",
                setOfInfo.infoSet().stream().map(Object::toString).sorted().toList().toString(),
                "documentedOnly() is named by the constructor's javadoc and called by nothing");
    }
}
