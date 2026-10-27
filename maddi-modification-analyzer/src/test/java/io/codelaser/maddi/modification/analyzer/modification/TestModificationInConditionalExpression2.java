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

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⚠ The NEGATIVE control for the conditional-expression fix, taken verbatim from the shape the clone-bench
 * corpus moved on (`Function5190901_file1956686.getParameterPositions`). Restoring the arms' records must not
 * invent a modification: both arms here are read-only, so the method stays non-modifying.
 */
public class TestModificationInConditionalExpression2 extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Collections;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            public class Y {
                private final Map<String, List<Integer>> paramPos = new HashMap<>();
                public List<Integer> getParameterPositions(String name) {
                    final List<Integer> integerList = paramPos.get(name);
                    return integerList == null ? Collections.<Integer>emptyList() : integerList;
                }
                public boolean has(String name, Integer i) {
                    final List<Integer> integerList = paramPos.get(name);
                    return integerList == null ? false : integerList.contains(i);
                }
                public List<Integer> viaUnmodifiable(String name) {
                    final List<Integer> integerList = paramPos.get(name);
                    return integerList == null ? Collections.<Integer>emptyList()
                            : Collections.unmodifiableList(integerList);
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo Y = javaInspector.parse("a.b.Y", INPUT);
        List<Info> analysisOrder = prepWork(Y);
        analyzer.go(analysisOrder);
        assertFalse(Y.findUniqueMethod("getParameterPositions", 1).isModifying(),
                "both arms only READ: restoring the arms' records must not invent a modification");
        assertFalse(Y.findUniqueMethod("has", 2).isModifying(),
                "`contains` is @NotModified[O]: a read-only call in an arm stays read-only");

        // ...and the one that is NOT the fix's doing: the hint decides it. Until 2026-10-10
        // `Collections.unmodifiableList` carried `@Independent[M]` and no `@NotModified`, so the archive said it
        // MODIFIED its argument, and this method read as modifying once the ternary kept the record. The hint now
        // says `@NotModified` (as for all eleven unmodifiable* views): a view that rejects every write does not
        // write its backing collection. In langchain4j, `Utils.copy(list)` returns `unmodifiableList(list)`, and
        // the wrong hint made every constructor parameter stored through it modified (no read-only Kotlin type).
        assertFalse(Y.findUniqueMethod("viaUnmodifiable", 1).isModifying(),
                "unmodifiableList does not modify its argument");
    }
}
