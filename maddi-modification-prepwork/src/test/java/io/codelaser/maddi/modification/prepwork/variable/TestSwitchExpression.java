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
import io.codelaser.maddi.cst.api.expression.SwitchExpression;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Block;
import io.codelaser.maddi.cst.api.statement.Statement;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestSwitchExpression extends CommonTest {
    @Language("java")
    public static final String INPUT1 = """
            import java.util.ArrayList;import java.util.List;
            record X(List<String> list, int k) {
                int method() {
                    return switch(k) {
                        case 0 -> list.getFirst().length();
                        case 1 -> {
                            System.out.println("?");
                            yield k+3;
                        }
                        case 2 -> {
                            int v = 5;
                            System.out.println(v);
                            yield v;
                        }
                        default -> throw new UnsupportedOperationException();
                    };
                }
            }
            """;

    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("X", INPUT1);
        new PrepAnalyzer(runtime).doPrimaryType(X);
        assertTrue(X.typeNature().isRecord());

        MethodInfo method = X.findUniqueMethod("method", 0);
        Statement returnStatement = method.methodBody().statements().getFirst();
        SwitchExpression switchExpression = (SwitchExpression) returnStatement.expression();
        Statement s0 = switchExpression.entries().getFirst().statement();
        assertEquals("this.list.getFirst().length();", s0.toString());
        assertEquals("5-23:5-46", s0.source().compact2());
        VariableData vd0 = VariableDataImpl.of(s0);
        assertEquals("X.list, X.this", vd0.knownVariableNamesToString());
        Block b1 = (Block) switchExpression.entries().get(1).statement();
        Statement s1 = b1.statements().get(1);
        assertEquals("8-17:8-26", s1.source().compact2());
        assertNotNull(VariableDataImpl.of(s1).knownVariableNamesToString());

        VariableData vdRs = VariableDataImpl.of(returnStatement);
        // should NOT contain v!!
        assertEquals("""
                X.k, X.list, X.method(), X.this, java.lang.System.out\
                """, vdRs.knownVariableNamesToString());
    }
}
