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
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent effectively-final (@Final) tests. A field is effectively final when it either has the {@code final}
 * modifier, or it is not assigned in any method transitively reachable from a non-private, non-constructor method
 * (road-to-immutability, "Final fields"). These assert FieldInfo.isPropertyFinal(), the computed effectively-final
 * property (as opposed to isFinal(), the source modifier); they do not depend on modification-link.
 */
public class TestFinalFields extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.Random;
            class X {
                // 'random' is assigned only via a private method reachable from the constructor -> effectively final
                static class EF1 {
                    private Random random;
                    public EF1() { initialize(3L); }
                    private void initialize(long seed) { random = new Random(seed); }
                    public int nextInt() { return random.nextInt(); }
                }
                // the assigning method is public, hence reachable after construction -> 'random' is variable
                static class EF2 {
                    private Random random;
                    public EF2() { reset(); }
                    public void reset() { initialize(3L); }
                    private void initialize(long seed) { random = new Random(seed); }
                    public int nextInt() { return random.nextInt(); }
                }
            }
            """;

    @DisplayName("effectively final only when the assigning method is not reachable after construction")
    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo ef1 = X.findSubType("EF1");
        FieldInfo random1 = ef1.getFieldByName("random", true);
        assertTrue(random1.isPropertyFinal(), "assigned only via a constructor-reachable private method");

        TypeInfo ef2 = X.findSubType("EF2");
        FieldInfo random2 = ef2.getFieldByName("random", true);
        assertFalse(random2.isPropertyFinal(), "assigned via reset(), reachable after construction");
    }

    @Language("java")
    private static final String INPUT2 = """
            package a.b;
            class X {
                // no modifier, but assigned only in the constructor -> effectively final
                static class A {
                    private int i;
                    A(int i) { this.i = i; }
                    int get() { return i; }
                }
                // reassigned by a public method -> variable
                static class B {
                    private int i;
                    B(int i) { this.i = i; }
                    void setI(int i) { this.i = i; }
                }
            }
            """;

    @DisplayName("a field assigned only in the constructor is effectively final; a public setter makes it variable")
    @Test
    public void test2() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT2);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);

        TypeInfo a = X.findSubType("A");
        assertTrue(a.getFieldByName("i", true).isPropertyFinal());

        TypeInfo b = X.findSubType("B");
        assertFalse(b.getFieldByName("i", true).isPropertyFinal());
    }
}
