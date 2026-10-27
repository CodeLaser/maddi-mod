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
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.CycleBreakingStrategy;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.common.util.TolerantWrite;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.codelaser.maddi.inspection.resource.SourceSetImpl.testProtocolSourceSet;
import static io.codelaser.maddi.modification.common.CommonTest.javaInspectorWithExtras;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape of refactor-base's {@code Loop.LoopData}/{@code Try.TryData}: a bodiless method returning
 * {@code Object}, implemented by a getter of an {@code Object} field (or of an element of an {@code Object[]}).
 * Without analysis hints for the JDK, the shallow default of the declaration ({@code Object} is hidden content:
 * {@code @Independent(hc=true)}) and the deep verdict of the implementation disagreed, and the abstract-method
 * fold tried to downgrade the declaration every pass: 5 refused downgrades per certification on the support
 * module (test-manager, maddi-mod 0.9.5-alpha.21).
 */
public class TestObjectReturnDefault extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class Data {
                interface Exit { }
                static class Return implements Exit {
                    private final Object value;
                    Return(Object value) { this.value = value; }
                    Object value() { return value; }
                }
                interface LoopData {
                    Object get(int i);
                    Object getReturnValue();
                    Object loopValue();
                    String label();
                }
                static class LoopDataImpl implements LoopData {
                    private final Object[] variables;
                    private final Object loopValue;
                    private final Exit exit;
                    private final String label;
                    LoopDataImpl(Object[] variables, Object loopValue, Exit exit, String label) {
                        this.variables = variables;
                        this.loopValue = loopValue;
                        this.exit = exit;
                        this.label = label;
                    }
                    @Override public Object get(int i) { return variables[i]; }
                    @Override public Object getReturnValue() { return ((Return) exit).value; }
                    @Override public Object loopValue() { return loopValue; }
                    @Override public String label() { return label; }
                }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private static Value.Independent independent(MethodInfo methodInfo) {
        return methodInfo.analysis().getOrNull(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.class);
    }

    @DisplayName("with the JDK hints: declaration and implementation agree on an Object-typed return")
    @Test
    public void withHints() {
        TypeInfo data = javaInspector.parse("a.b.Data", INPUT);
        analyzeAndCheck(javaInspector, data, prepWork(data));
    }

    /**
     * The generator's situation: no analysis hints at all, so {@code java.lang.Object} and {@code String} carry
     * no stored immutability. The declaration's shallow default must still agree with the implementation.
     */
    @DisplayName("without any hints: still no refused downgrade")
    @Test
    public void withoutHints() throws java.io.IOException {
        JavaInspector ji = javaInspectorWithExtras(testProtocolSourceSet(), List.of(), List.of());
        ji.setParameterNames(true);
        ji.onlyPreload();
        PrepAnalyzer prep = new PrepAnalyzer(ji.runtime(), new PrepAnalyzer.Options.Builder().build());
        TypeInfo data = ji.parse("a.b.Data", INPUT);
        analyzeAndCheck(ji, data, prep.doPrimaryType(data));
    }

    private static void analyzeAndCheck(JavaInspector ji, TypeInfo data, List<Info> ao) {
        new IteratingAnalyzerImpl(ji, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(12)
                .setCycleBreakingStrategy(CycleBreakingStrategy.NO_INFORMATION_IS_NON_MODIFYING)
                .build()).analyze(ao);
        Map<String, Long> refused = TolerantWrite.refusedDowngrades();

        TypeInfo loopData = data.findSubType("LoopData");
        TypeInfo impl = data.findSubType("LoopDataImpl");
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("get", "getReturnValue", "loopValue", "label")) {
            sb.append(name).append(": decl=").append(independent(method(loopData, name)))
                    .append(" impl=").append(independent(method(impl, name))).append('\n');
        }
        System.out.println(sb);
        System.out.println("refused: " + refused);
        assertEquals(Map.of(), refused, "refused downgrades: " + refused + "\n" + sb);
        for (String name : List.of("get", "getReturnValue", "loopValue")) {
            assertEquals(ValueImpl.IndependentImpl.INDEPENDENT_HC, independent(method(impl, name)), name);
            assertEquals(independent(method(loopData, name)), independent(method(impl, name)), name);
        }
    }
}
