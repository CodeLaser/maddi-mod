/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
