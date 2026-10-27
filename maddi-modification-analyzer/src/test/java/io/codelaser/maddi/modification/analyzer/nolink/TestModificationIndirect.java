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

package io.codelaser.maddi.modification.analyzer.nolink;

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soundness regression tests: a field modification must be detected even when it happens through an indirection --
 * a returning getter, a local alias, a conditional, or a loop. Missing any of these would be an unsound false negative.
 * Asserts FieldInfo.isModified() and MethodInfo.isModifying().
 */
public class TestModificationIndirect extends CommonTest {

    private TypeInfo analyze(String input, String subType) {
        TypeInfo X = javaInspector.parse("a.b.X", input);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);
        return X.findSubType(subType);
    }

    @Language("java") private static final String INDIRECT_GETTER = """
            package a.b;
            import java.util.ArrayList; import java.util.List;
            class X {
                static class Y {
                    private final List<String> list = new ArrayList<>();
                    List<String> getList() { return list; }
                    void indirectAdd(String s) { getList().add(s); }
                }
            }
            """;

    @DisplayName("modification through a returning getter is detected")
    @Test
    public void indirectGetter() {
        TypeInfo Y = analyze(INDIRECT_GETTER, "Y");
        assertTrue(Y.getFieldByName("list", true).isModified());
        assertTrue(Y.findUniqueMethod("indirectAdd", 1).isModifying());
    }

    @Language("java") private static final String LOCAL_ALIAS = """
            package a.b;
            import java.util.ArrayList; import java.util.List;
            class X {
                static class Y {
                    private final List<String> list = new ArrayList<>();
                    void aliasAdd(String s) { List<String> l = list; l.add(s); }
                }
            }
            """;

    @DisplayName("modification through a local alias of a field is detected")
    @Test
    public void localAlias() {
        TypeInfo Y = analyze(LOCAL_ALIAS, "Y");
        assertTrue(Y.getFieldByName("list", true).isModified());
        assertTrue(Y.findUniqueMethod("aliasAdd", 1).isModifying());
    }

    @Language("java") private static final String CONDITIONAL_AND_LOOP = """
            package a.b;
            import java.util.ArrayList; import java.util.List;
            class X {
                static class Y {
                    private final List<String> conditional = new ArrayList<>();
                    private final List<String> loop = new ArrayList<>();
                    void maybeAdd(String s, boolean b) { if (b) { conditional.add(s); } }
                    void addAll(String[] items) { for (String s : items) loop.add(s); }
                }
            }
            """;

    @DisplayName("modification inside a conditional or a loop is detected")
    @Test
    public void conditionalAndLoop() {
        TypeInfo Y = analyze(CONDITIONAL_AND_LOOP, "Y");
        assertTrue(Y.getFieldByName("conditional", true).isModified());
        assertTrue(Y.getFieldByName("loop", true).isModified());
        assertTrue(Y.findUniqueMethod("maybeAdd", 2).isModifying());
        assertTrue(Y.findUniqueMethod("addAll", 1).isModifying());
    }
}
