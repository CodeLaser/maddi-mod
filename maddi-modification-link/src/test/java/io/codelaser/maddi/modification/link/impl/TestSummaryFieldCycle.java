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
package io.codelaser.maddi.modification.link.impl;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.Links;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jenkins' ANTLR-generated LabelExpressionParser, reduced: two rule methods whose contexts point at each other
 * ('C1.c2 : C2', 'C2.c1 : C1'), each calling the other and reading the child's field back. Exporting the field
 * cycles, the two summaries grow one lap per round without end -- 199 -> 751 -> 1947 -> 4003 -> 7135 -> 11559
 * characters for r1 over six rounds, 'r1.c2.c1.c2.c1...' twelve fields deep -- which on the real parser filled a
 * 12 G heap with FieldReference names (TestJenkinsCore OOM, 2026-10-01). Not exporting a second lap on the same
 * type, they are identical from round 2 on. See LinkComputerImpl.goesRoundAFieldCycle.
 */
public class TestSummaryFieldCycle extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class G {
                static class Label {
                    Label and(Label o) { return this; }
                }
                static class Ctx {
                    Ctx parent;
                    Ctx(Ctx parent) { this.parent = parent; }
                }
                static class C1 extends Ctx {
                    Label l;
                    C2 c2;
                    C1(Ctx parent) { super(parent); }
                }
                static class C2 extends Ctx {
                    Label l;
                    C1 c1;
                    C2(Ctx parent) { super(parent); }
                }
                Ctx _ctx;
                int la;
                C1 r1() {
                    C1 _localctx = new C1(_ctx);
                    _ctx = _localctx;
                    ((C1) _localctx).c2 = r2();
                    ((C1) _localctx).l = ((C1) _localctx).c2.l;
                    while (la++ < 3) {
                        ((C1) _localctx).c2 = r2();
                        ((C1) _localctx).l = _localctx.l.and(((C1) _localctx).c2.l);
                    }
                    _ctx = _localctx.parent;
                    return _localctx;
                }
                C2 r2() {
                    C2 _localctx = new C2(_ctx);
                    _ctx = _localctx;
                    if (la++ > 5) {
                        ((C2) _localctx).c1 = r1();
                        ((C2) _localctx).l = ((C2) _localctx).c1.l;
                    } else {
                        ((C2) _localctx).l = new Label();
                    }
                    _ctx = _localctx.parent;
                    return _localctx;
                }
            }
            """;

    @DisplayName("the summaries of mutually recursive rule methods converge; no field cycle is exported")
    @Test
    public void test() {
        TypeInfo G = javaInspector.parse("a.b.G", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(G);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        MethodInfo r1 = G.findUniqueMethod("r1", 0);
        MethodInfo r2 = G.findUniqueMethod("r2", 0);
        List<String> rounds = new ArrayList<>();
        for (int round = 1; round <= 6; round++) {
            tlc.doPrimaryType(G);
            rounds.add(summary(r1) + "\n" + summary(r2));
        }
        assertEquals(rounds.get(2), rounds.get(5), "the summaries keep growing");
        for (MethodInfo m : List.of(r1, r2)) {
            MethodLinkedVariables mlv = m.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                    MethodLinkedVariablesImpl.class);
            List<Variable> cyclic = Stream.concat(Stream.concat(mlv.ofReturnValue().stream(),
                                    mlv.ofParameters().stream().flatMap(Links::stream))
                            .flatMap(l -> Stream.of(l.from(), l.to())), mlv.modified().stream())
                    .flatMap(Variable::variableStreamDescend)
                    .filter(LinkComputerImpl::goesRoundAFieldCycle)
                    .toList();
            assertTrue(cyclic.isEmpty(), m.name() + " exports " + cyclic);
        }
        // the first lap is kept: the child's label is still linked to the context's
        assertTrue(summary(r1).contains("r1.c2.l"), summary(r1));
    }

    private static String summary(MethodInfo m) {
        return String.valueOf(m.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class));
    }
}
