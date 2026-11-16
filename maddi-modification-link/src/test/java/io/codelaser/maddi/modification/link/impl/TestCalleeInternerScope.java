package io.codelaser.maddi.modification.link.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.TreeSet;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.expression.Expression;
import io.codelaser.maddi.cst.api.expression.NullConstant;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.link.impl.localvar.MarkerVariable;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.Link;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinkEndInterner;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

/**
 * A method's links computed on demand while another method's are (recurseMethod: the parallel first iteration) have
 * their own LinkEndInterner scope. In the caller's scope, the callee's null marker '$_ceN' was interned to the
 * caller's equally named one, so the callee's 'return null' pointed at a null literal of another method (fernflower
 * MergeHelper.getFirstDirectData came out non-null in J2K). Gate NOCALLEESCOPE.
 */
public class TestCalleeInternerScope extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class C {
                static String a(String s) { if (s.isEmpty()) return null; return s; }
                static String b(String s) { if (s.isEmpty()) return null; return s; }
            }
            """;

    private static final LinkComputer.Options NULL_RETURNS = new LinkComputer.Options.Builder()
            .setRecurse(true).setCheckDuplicateNames(true).setNullConstantReturns(true).build();

    @DisplayName("an on-demand computation keeps its own null markers")
    @Test
    public void test() {
        TypeInfo c = javaInspector.parse("a.b.C", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(c);
        LinkComputerImpl lc = new LinkComputerImpl(javaInspector, NULL_RETURNS);
        MethodInfo a = c.findUniqueMethod("a", 1);
        MethodInfo b = c.findUniqueMethod("b", 1);
        try (LinkEndInterner _ = LinkEndInterner.open()) {
            lc.recurseMethod(a);
            lc.recurseMethod(b);
        }
        assertEquals("a: own, b: own", "a: " + owners(a) + ", b: " + owners(b));
    }

    // whether every null marker in the statements' links holds a null literal of this method's own body
    private static String owners(MethodInfo mi) {
        Set<Expression> own = Collections.newSetFromMap(new IdentityHashMap<>());
        mi.methodBody().visit(e -> {
            if (e instanceof NullConstant nc) own.add(nc);
            return true;
        });
        Set<String> result = new TreeSet<>();
        mi.methodBody().visit(el -> {
            VariableData vd = el instanceof Statement st ? VariableDataImpl.of(st) : null;
            if (vd != null) {
                vd.variableInfoStream().forEach(vi -> {
                    if (vi.linkedVariables() == null) return;
                    for (Link link : vi.linkedVariables()) {
                        for (Variable v : new Variable[]{link.from(), link.to()}) {
                            if (v instanceof MarkerVariable mv && mv.assignmentExpression() instanceof NullConstant) {
                                result.add(own.contains(mv.assignmentExpression()) ? "own" : "foreign " + mv.simpleName());
                            }
                        }
                    }
                });
            }
            return true;
        });
        return String.join("|", result);
    }
}
