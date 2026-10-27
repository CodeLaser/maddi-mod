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

package io.codelaser.maddi.modification.prepwork.variable;

import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 of the flatten-snapshot design ({@code DESIGN-vardata-flatten.md}): the flatten primitive
 * itself, in isolation (no analyzer wiring). A method's last-statement VariableData holds a container
 * per in-scope variable, each chained back to the previous statement's container. Flattening must
 * preserve every value-bearing query while collapsing that chain link, so the intermediate containers
 * become collectible once the intermediate statements' VARIABLE_DATA is dropped.
 */
public class TestVariableDataFlatten extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                static int method(int p) {
                    int a = p + 1;
                    int b = a * 2;
                    int c = b - a;
                    return c;
                }
            }
            """;

    @DisplayName("flatten preserves best()/definition and breaks the previous-statement chain")
    @Test
    public void flatten() {
        TypeInfo X = javaInspector.parse(ABX, INPUT);
        MethodInfo method = X.findUniqueMethod("method", 1);
        new PrepAnalyzer(runtime).doMethod(method);

        Statement last = method.methodBody().statements().getLast(); // return c;
        VariableDataImpl vd = (VariableDataImpl) VariableDataImpl.of(last);
        assertNotNull(vd);
        // p (param), a, b (in scope, untouched here => previous-only VICs) and c (read => has evaluation)
        assertTrue(vd.knownVariableNames().size() >= 4, () -> vd.knownVariableNamesToString());

        VariableDataImpl flat = vd.flattened();
        assertNotSame(vd, flat);
        assertEquals(vd.knownVariableNamesToString(), flat.knownVariableNamesToString(),
                "same variables in the same order");

        vd.variableInfoContainerStream().forEach(orig -> {
            String fqn = orig.variable().fullyQualifiedName();
            VariableInfoContainer f = flat.variableInfoContainerOrNull(fqn);
            assertNotNull(f, () -> "missing " + fqn);
            // value-bearing queries preserved — same VariableInfo object, so links/assignments/reads too
            assertSame(orig.best(), f.best(), () -> "best() preserved for " + fqn);
            assertSame(orig.getPreviousOrInitial(), f.getPreviousOrInitial(),
                    () -> "getPreviousOrInitial() value preserved for " + fqn);
            assertEquals(orig.indexOfDefinition(), f.indexOfDefinition(), () -> "definition for " + fqn);
            assertEquals(orig.hasEvaluation(), f.hasEvaluation(), () -> "hasEvaluation for " + fqn);
            assertEquals(orig.hasMerge(), f.hasMerge(), () -> "hasMerge for " + fqn);
            // structural change: the chain link is gone
            assertFalse(f.isPrevious(), () -> "chain link broken for " + fqn);
            assertTrue(f.isInitial(), () -> "flattened container reads as back-reference-free for " + fqn);
        });

        // idempotent: flattening a flattened VD changes nothing structural
        VariableDataImpl again = flat.flattened();
        again.variableInfoContainerStream().forEach(f -> assertFalse(f.isPrevious()));
    }
}
