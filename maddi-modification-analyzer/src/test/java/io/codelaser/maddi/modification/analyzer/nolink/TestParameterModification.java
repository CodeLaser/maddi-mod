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
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent parameter-modification (@Modified / @NotModified parameter) tests. A parameter is modified when the
 * method calls a modifying method <em>on</em> it; merely reading it, or passing it <em>as an argument</em> to another
 * call, does not modify it (road-to-immutability, "Modification"). Asserts ParameterInfo.isModified() directly.
 */
public class TestParameterModification extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.List;
            class X {
                static class M { int i; void set(int v) { this.i = v; } }
                static class Y {
                    // calls a modifying method on the parameter -> modified
                    void mutate(M m) { m.set(1); }
                    // only reads the parameter -> not modified
                    int read(M m) { return m.i; }
                    // calls a modifying method (add) ON the parameter -> modified
                    void fill(List<String> list, String s) { list.add(s); }
                    // 's' is passed AS AN ARGUMENT to add; the string itself is not modified
                    void store(List<String> sink, String s) { sink.add(s); }
                }
            }
            """;

    @DisplayName("a parameter is modified only when a modifying method is called on it, not when it is read or passed")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo Y = X.findSubType("Y");

        assertTrue(Y.findUniqueMethod("mutate", 1).parameters().getFirst().isModified(), "m.set(1)");
        assertFalse(Y.findUniqueMethod("read", 1).parameters().getFirst().isModified(), "only reads m.i");

        MethodInfo fill = Y.findUniqueMethod("fill", 2);
        assertTrue(fill.parameters().get(0).isModified(), "list.add(s) modifies the list");
        assertFalse(fill.parameters().get(1).isModified(), "the String argument is not modified");

        MethodInfo store = Y.findUniqueMethod("store", 2);
        assertTrue(store.parameters().get(0).isModified(), "sink.add(s) modifies the sink");
        assertFalse(store.parameters().get(1).isModified(), "s is passed as an argument, not modified");
    }
}
