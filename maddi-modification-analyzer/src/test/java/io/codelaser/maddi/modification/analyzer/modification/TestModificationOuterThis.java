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

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/*
Semantic audit 2026-07-18, unsound mechanism #1 (guava CompactHashMap.EntrySetView.remove): calling a
modifying method on the ENCLOSING instance — explicitly via Outer.this or implicitly — must make the inner
class's method modifying: the enclosing instance is reachable from the inner this via the synthetic outer
reference, so its object graph is part of the inner receiver's field graph.
 */
public class TestModificationOuterThis extends CommonTest {
    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            public class X {
                private int count;

                public void increment() {
                    count++;
                }

                public int getCount() {
                    return count;
                }

                class View {
                    public void touchExplicit() {
                        X.this.increment();
                    }

                    public void touchImplicit() {
                        increment();
                    }

                    public int readOnly() {
                        return X.this.getCount();
                    }
                }
            }
            """;

    @DisplayName("modifying call on the enclosing instance makes the inner method modifying")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder);
        TypeInfo view = X.findSubType("View");
        MethodInfo increment = X.findUniqueMethod("increment", 0);
        assertTrue(increment.isModifying());
        MethodInfo touchExplicit = view.findUniqueMethod("touchExplicit", 0);
        assertTrue(touchExplicit.isModifying(), "X.this.increment() modifies the enclosing instance");
        MethodInfo touchImplicit = view.findUniqueMethod("touchImplicit", 0);
        assertTrue(touchImplicit.isModifying(), "implicit outer call increment() modifies the enclosing instance");
        MethodInfo readOnly = view.findUniqueMethod("readOnly", 0);
        assertTrue(readOnly.isNonModifying(), "reading through the outer reference stays non-modifying");
    }
}
