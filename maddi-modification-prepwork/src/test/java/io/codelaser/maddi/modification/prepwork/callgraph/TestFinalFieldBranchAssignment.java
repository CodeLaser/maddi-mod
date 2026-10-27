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

package io.codelaser.maddi.modification.prepwork.callgraph;

import io.codelaser.maddi.modification.prepwork.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// effectively-final-field detection: a field assigned anywhere in the primary type must not be reported final.
// The interesting cases are assignments inside lambda / anonymous / local types enclosed by the field's owner:
// those methods have no field->method edge in the call graph (their typeInfo is not the owner), so finality
// detection must inspect them directly. See ComputePartOfConstructionFinalField.computeEffectivelyFinalFields.
public class TestFinalFieldBranchAssignment extends CommonTest {

    private boolean isFinal(@Language("java") String input) {
        TypeInfo X = javaInspector.parse(ABX, input);
        new PrepAnalyzer(runtime).doPrimaryType(X);
        FieldInfo i = X.getFieldByName("i", true);
        // guard against the default-value trap: the property must actually have been computed
        assertTrue(i.analysis().haveAnalyzedValueFor(PropertyImpl.FINAL_FIELD), "FINAL_FIELD not computed");
        return i.analysis().getOrDefault(PropertyImpl.FINAL_FIELD, FALSE).isTrue();
    }

    @DisplayName("field assigned in an early-return branch is not final")
    @Test
    public void testEarlyReturnBranch() {
        assertFalse(isFinal("""
                package a.b;
                class X {
                    private int i;
                    public X(int i) { this.i = i; }
                    public void mutate(boolean c) { if (c) { this.i = 3; return; } System.out.println("x"); }
                }
                """));
    }

    @DisplayName("field assigned inside a switch-expression branch is not final")
    @Test
    public void testSwitchExpressionBranch() {
        assertFalse(isFinal("""
                package a.b;
                class X {
                    private int i;
                    public X(int i) { this.i = i; }
                    public void mutate(int v) {
                        int x = switch (v) { case 1 -> { this.i = 1; yield 1; } default -> 2; };
                        System.out.println(x);
                    }
                }
                """));
    }

    @DisplayName("field assigned inside a lambda body is not final")
    @Test
    public void testLambdaBody() {
        assertFalse(isFinal("""
                package a.b;
                class X {
                    private int i;
                    public X(int i) { this.i = i; }
                    public void mutate() {
                        Runnable r = () -> { this.i = 1; };
                        r.run();
                    }
                }
                """));
    }

    @DisplayName("field assigned inside an anonymous-class method is not final")
    @Test
    public void testAnonymousClassMethod() {
        assertFalse(isFinal("""
                package a.b;
                class X {
                    private int i;
                    public X(int i) { this.i = i; }
                    public void mutate() {
                        Runnable r = new Runnable() { public void run() { i = 1; } };
                        r.run();
                    }
                }
                """));
    }

    private boolean isFinalInInner(@Language("java") String input) {
        TypeInfo X = javaInspector.parse(ABX, input);
        new PrepAnalyzer(runtime).doPrimaryType(X);
        FieldInfo i = X.findSubType("Inner").getFieldByName("i", true);
        assertTrue(i.analysis().haveAnalyzedValueFor(PropertyImpl.FINAL_FIELD), "FINAL_FIELD not computed");
        return i.analysis().getOrDefault(PropertyImpl.FINAL_FIELD, FALSE).isTrue();
    }

    @DisplayName("nested type's field assigned by the enclosing type is not final")
    @Test
    public void testAssignmentFromEnclosingType() {
        assertFalse(isFinalInInner("""
                package a.b;
                class X {
                    static class Inner {
                        private int i;
                        Inner(int i) { this.i = i; }
                        int get() { return i; }
                    }
                    void renumber(Inner inner) { inner.i = 42; }
                }
                """));
    }

    @DisplayName("nested type's field assigned by a sibling nested type is not final")
    @Test
    public void testAssignmentFromSiblingType() {
        assertFalse(isFinalInInner("""
                package a.b;
                class X {
                    static class Inner {
                        private int i;
                        Inner(int i) { this.i = i; }
                        int get() { return i; }
                    }
                    static class Renumberer {
                        void renumber(Inner inner) { inner.i = 42; }
                    }
                }
                """));
    }

    @DisplayName("positive control: field assigned only in the constructor stays final")
    @Test
    public void testFinalControl() {
        assertTrue(isFinal("""
                package a.b;
                class X {
                    private int i;
                    public X(int i) { this.i = i; }
                    public int get() { return i; }
                }
                """));
    }
}
