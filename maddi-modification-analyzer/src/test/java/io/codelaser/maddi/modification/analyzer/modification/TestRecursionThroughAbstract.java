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

import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pessimistic-bootstrap shape behind the dogfood's reverse-divergence class (PLAN-modification-
 * reachability §14; docs/design/handoff-verification-residue.md): a pure read accessor that recurses through
 * an ABSTRACT declaration. At the first evaluation of the call site the abstract callee is undecided,
 * {@code MethodInfo.isNonModifying()} defaults undecided to modifying, the receiver field lands in the
 * summary's modified set, the abstract batch aggregates the impl's FALSE — and the pessimism is a
 * self-consistent fixpoint no later iteration can leave. {@code TypeInfoImpl.packageName()} /
 * {@code descriptor()} / {@code fromPrimaryTypeDownwards()} are the real-world casualties, and through
 * {@code TypeNameImpl.typeName} they sink the whole {@code print} family.
 */
public class TestRecursionThroughAbstract extends CommonTest {

    private String nonModifying(TypeInfo typeInfo, String methodName) {
        var v = typeInfo.findUniqueMethod(methodName, 0).analysis()
                .getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
        return v == null ? "null" : v.isTrue() ? "true" : "false";
    }

    @Language("java")
    private static final String INPUT = """
            public class X {
              interface I {
                String name();
              }
              static class C implements I {
                private final I parent;
                private final String simpleName;
                C(I parent, String simpleName) { this.parent = parent; this.simpleName = simpleName; }
                @Override public String name() {
                  if (parent == null) return simpleName;
                  return parent.name() + "." + simpleName;
                }
                public String direct() {
                  if (parent == null) return simpleName;
                  return direct();
                }
              }
            }
            """;

    @DisplayName("PIN of the pessimism: the plain fixpoint computes recursive pure accessors as modifying")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("X", INPUT);
        analyzer.go(prepWork(X));
        TypeInfo I = X.findSubType("I");
        TypeInfo C = X.findSubType("C");

        // PINS THE DEFECT, deliberately: all three should be "true" — direct() is plain self-recursion
        // of a pure function, name() is the packageName() shape. The conservative undecided-callee
        // default at the call site (MethodInfo.isNonModifying: getOrDefault FALSE) makes the first
        // evaluation mark the receiver modified, and the monotone write discipline (no TRUE->FALSE
        // downgrades needed here — the FALSE is simply self-consistent) never leaves it. The repair
        // channel is the MODREACH cutover (testModReach below); a fixpoint-side fix would need the
        // downgrade direction the monotone discipline forbids.
        assertEquals("direct=false C.name=false I.name=false",
                "direct=" + nonModifying(C, "direct")
                + " C.name=" + nonModifying(C, "name")
                + " I.name=" + nonModifying(I, "name"));
    }

    @DisplayName("the MODREACH cutover (P3 primitive seeding + reverse upgrade) repairs the pessimism")
    @Test
    public void testModReach() {
        TypeInfo X = javaInspector.parse("X", INPUT);
        var ao = prepWork(X);
        var iterating = new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl(javaInspector,
                new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(10)
                        .setModificationViaReachability(true) // implies trackObjectCreations
                        .build());
        iterating.analyze(ao);
        TypeInfo I = X.findSubType("I");
        TypeInfo C = X.findSubType("C");

        // no primitive modification evidence anywhere in these bodies: the reachability pass leaves
        // all three unreached with a complete frontier and upgrades the fixpoint's recursion FALSEs
        assertEquals("direct=true C.name=true I.name=true",
                "direct=" + nonModifying(C, "direct")
                + " C.name=" + nonModifying(C, "name")
                + " I.name=" + nonModifying(I, "name"));
    }
}
