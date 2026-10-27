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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent field-modification (@Modified / @NotModified field) tests. A field is modified when one of the type's
 * methods calls a modifying method on it (road-to-immutability, "Modification"). These assert FieldInfo.isModified()
 * directly, not the linked-variable representation from modification-link.
 */
public class TestFieldModification extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            class X {
                static class M {
                    int i;
                    void set(int i) { this.i = i; }
                }
                static class Holder {
                    private final M mutated = new M();  // has a modifying call -> modified
                    private final M untouched = new M(); // only read -> not modified
                    private final List<String> list = new ArrayList<>(); // add() called -> modified
                    private final String label = "x";   // immutable, never modified

                    void go() {
                        mutated.set(1);
                        int j = untouched.i;
                        list.add(label);
                    }
                }
            }
            """;

    @DisplayName("a field is modified only when a method modifies the object it holds")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo holder = X.findSubType("Holder");
        assertTrue(holder.getFieldByName("mutated", true).isModified(), "mutated.set(1) modifies it");
        assertFalse(holder.getFieldByName("untouched", true).isModified(), "only read");
        assertTrue(holder.getFieldByName("list", true).isModified(), "list.add(...) modifies it");
        assertFalse(holder.getFieldByName("label", true).isModified(), "a String is immutable");
    }
}
