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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/**
 * CodeLaser/maddi-mod#103: a link summary must not depend on the JVM it was computed in. The JDK's unmodifiable sets
 * ({@code Set.of}, {@code Collectors.toUnmodifiableSet}) iterate in an order salted per JVM start; iterated to build
 * links, they made fernflower's results differ between JVMs, identical within one. This is fernflower's
 * SwitchPatternHelper.findNestedLastStatement: a lambda with two results ('return x' and 'return null') passed to
 * Optional.map, whose links LinkFunctionalInterface added per result. 'find ∩ null' in some JVMs, 'find ≈ null,
 * find.§$ ≺ null' in others (3 of 8). A single JVM cannot show the order dependence, only the outcome: without the fix,
 * this test fails in about a third of the JVMs it runs in.
 */
public class TestSaltedSetOrder extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class M {
                static class Statement { List<Statement> stats; List<Object> exprents;
                    List<Statement> getStats() { return stats; } List<Object> getExprents() { return exprents; } }
                static class SwitchStatement extends Statement { }
                static class BasicBlockStatement extends Statement { }
                static class PatternContainer { Map<Statement, List<Object>> patternsByStatement = new HashMap<>(); }
                static Optional<Statement> find(PatternContainer patternContainer) {
                  return Optional.of(patternContainer)
                    .map(c -> c.patternsByStatement)
                    .filter(patterns -> patterns.size() == 1)
                    .map(patterns -> patterns.keySet().iterator().next())
                    .map(p -> {
                      if (p.getStats() != null && p.getStats().size() >= 2
                          && p.getStats().get(p.getStats().size() - 2) instanceof SwitchStatement) {
                        return p.getStats().get(p.getStats().size() - 1);
                      } else {
                        return null;
                      }
                    })
                    .filter(t -> t instanceof BasicBlockStatement b && b.getExprents() != null);
                }
            }
            """;

    @Test
    public void optionalChainWithTwoResults() {
        TypeInfo t = javaInspector.parse("a.b.M", INPUT);
        List<Info> ao = prepWork(t);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30).build()).analyze(ao);
        MethodInfo m = t.findUniqueMethod("find", 1);
        Object mlv = m.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS, MethodLinkedVariablesImpl.class);
        assertEquals("[-] --> find∩$_ce6", String.valueOf(mlv));
    }
}
