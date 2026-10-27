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
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#63: a modification inside a switch expression's block arm ({@code case -> { ... yield }}) is a
 * modification of the method. The reachability pass did not walk such a block, and skipped the summary's entry as
 * re-derivable: the method came out non-modifying.
 */
public class TestSwitchExpressionBlockModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class P {
                private final List<String> log = new ArrayList<>();
                private String mode = "X";
                private double price;
                double inSwitchBlock() {
                    return switch (mode) {
                        case "A" -> {
                            if (price > 0) {
                                log.add("a");
                                yield price;
                            }
                            yield 0;
                        }
                        default -> price;
                    };
                }
                double readOnly() {
                    return switch (mode) {
                        case "A" -> {
                            double d = price * 2;
                            yield d + log.size();
                        }
                        default -> price;
                    };
                }
            }
            """;

    private static String m(TypeInfo typeInfo, String name) {
        MethodInfo mi = typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
        return mi.isNonModifying() + "/" + mi.isStructurallyNonModifying();
    }

    @DisplayName("the fixpoint's verdicts")
    @Test
    public void fixpoint() {
        analyze(false);
    }

    @DisplayName("the reachability pass, the CLI's default, walks the block arm")
    @Test
    public void reachability() {
        analyze(true);
    }

    private void analyze(boolean modReach) {
        TypeInfo c = javaInspector.parse("a.b.P", INPUT);
        List<Info> ao = prepWork(c);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(modReach).build()).analyze(ao);
        assertEquals("false/false", m(c, "inSwitchBlock"));
        assertEquals("true/true", m(c, "readOnly"));
    }
}
