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
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ Found by the Kotlin front end's verdict test: the SAME modifying call, written once inside an
 * {@code if} statement's arm and once inside a conditional EXPRESSION's arm, gave two different answers
 * about the parameter it modifies. The language has nothing to do with it — this is the Java engine.
 */
public class TestModificationInConditionalExpression extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class X {
                static class Box {
                    private final List<String> items = new ArrayList<>();
                    int addAndSize(String s) { items.add(s); return items.size(); }
                    String addAndEcho(String s) { items.add(s); return s; }
                    int size() { return items.size(); }
                }
                static int viaIf(Box b, Box c, String t) {
                    int v;
                    if (b == null) { v = c.addAndSize(t); } else { v = b.size(); }
                    return v;
                }
                static int viaTernary(Box b, Box c, String t) {
                    return b == null ? c.addAndSize(t) : b.size();
                }
                static int unconditional(Box b, Box c, String t) {
                    return c.addAndSize(t) + b.size();
                }
                static int inCondition(Box b, Box c, String t) {
                    return c.addAndSize(t) > 0 ? b.size() : 0;
                }
                static int bothArms(Box b, Box c, String t) {
                    return b == null ? c.addAndSize(t) : c.size();
                }
                static int ternaryInsideAnArgument(Box b, Box c, String t) {
                    return b.addAndSize(b == null ? t : c.addAndEcho(t));
                }
                static int ternaryAssignedToALocal(Box b, Box c, String t) {
                    int v = b == null ? c.addAndSize(t) : b.size();
                    return v;
                }
                static int switchArm(Box b, Box c, String t) {
                    return switch (b == null ? 0 : 1) { case 0 -> c.addAndSize(t); default -> b.size(); };
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        analyzer.go(analysisOrder);

        // Every one of these writes `c.addAndSize(t)` exactly once, so `c` is modified in every one of
        // them. Before the fix only the first two said so: any conditional expression anywhere in the
        // expression discarded the modification record of everything inside it — including its own CONDITION,
        // which is not conditional at all.
        for (String name : List.of("unconditional", "viaIf", "viaTernary", "inCondition", "bothArms",
                "ternaryInsideAnArgument", "ternaryAssignedToALocal", "switchArm")) {
            assertTrue(second(X, name).isModified(),
                    name + ": `c.addAndSize(t)` modifies `c`, wherever in the expression it stands");
        }
    }

    private static ParameterInfo second(TypeInfo X, String name) {
        MethodInfo m = X.findUniqueMethod(name, 3);
        return m.parameters().get(1);
    }
}
