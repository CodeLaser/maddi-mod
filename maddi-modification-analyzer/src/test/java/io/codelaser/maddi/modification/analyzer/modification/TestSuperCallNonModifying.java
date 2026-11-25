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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#165: an override that calls {@code super.m()} made both {@code m} and the override modifying,
 * though nothing modifies. The cycle comes from the dispatch union (Util.dispatchUnion, on by default): {@code
 * Base.getCurrency} takes the union with its override {@code Sched.getCurrency}, and the override's non-virtual
 * {@code super} call reads that union back. At the first evaluation the other side is undecided, which counts as
 * modifying, and the FALSE is a self-consistent fixpoint. With the dispatch union off ({@code NODISPATCHUNION}) all
 * four are non-modifying.
 * <p>
 * The modification reachability pass (MODREACH, on by default in the CLI and in refactor-base's
 * MODIFICATION_ANALYSIS) repairs it: no primitive modification is reachable, so it upgrades both.
 */
public class TestSuperCallNonModifying extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            public class X {
              static class Cur { }
              static class Money { private static Cur DEF; private final Cur cur;
                public Money(Cur c) { cur = c; }
                public Cur getCurrency() { return cur; }
                public static Cur def() { return DEF; } }
              static class Acc { public Cur getCurrency() { return Money.def(); } }
              static abstract class Base { protected Acc account;
                public Cur getCurrency() { return account.getCurrency(); } }
              static class Sched extends Base { private Money principal;
                @Override public Cur getCurrency() {
                  Cur c = null;
                  if (this.account != null) { c = super.getCurrency(); } else { c = this.principal.getCurrency(); }
                  return c; } }
              static abstract class Plain { protected Acc account;
                public Cur getCurrency() { return account.getCurrency(); } }
            }
            """;

    private static String nm(TypeInfo X, String type) {
        var v = X.findSubType(type).findUniqueMethod("getCurrency", 0).analysis()
                .getOrNull(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.class);
        return type + "=" + (v == null ? "null" : v.isTrue() ? "true" : "false");
    }

    private static String all(TypeInfo X) {
        return String.join(" ", nm(X, "Acc"), nm(X, "Base"), nm(X, "Sched"), nm(X, "Plain"));
    }

    // PINS the plain fixpoint's pessimism, deliberately: all four should be "true"; MODREACH repairs it (below)
    @Test
    public void plainFixpoint() {
        TypeInfo X = javaInspector.parse("X", INPUT);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder().build())
                .analyze(prepWork(X));
        assertEquals("Acc=true Base=false Sched=false Plain=true", all(X));
    }

    @Test
    public void modReach() {
        TypeInfo X = javaInspector.parse("X", INPUT);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).build()).analyze(prepWork(X));
        assertEquals("Acc=true Base=true Sched=true Plain=true", all(X));
    }
}
