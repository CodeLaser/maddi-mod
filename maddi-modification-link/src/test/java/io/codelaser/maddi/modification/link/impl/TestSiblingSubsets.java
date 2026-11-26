package io.codelaser.maddi.modification.link.impl;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.LinkComputer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

/*
 Two subsets of one set: 'a ⊆ b' and 'c ⊆ b'. The table of compositions gives nothing for ⊆∘⊇, so the closure
 derives no link between a and c, although they can share elements. See convergence-in-call-cycles.adoc, the
 audit of the closure. This test prints what the engine says; it does not assert.
 */
public class TestSiblingSubsets extends CommonTest {

    @Language("java")
    private static final String INPUT1 = """
            package a.b;
            import java.util.ArrayList;import java.util.List;
            class X<T> {
                // two views of one list
                void views(List<T> b) {
                    List<T> a = b.subList(1, 5);
                    List<T> c = b.subList(3, 8);
                    System.out.println(a + " " + c);
                }
                // two copies of parts of one list: they share elements, not structure
                void copies(List<T> b) {
                    List<T> a = new ArrayList<>(b);
                    List<T> c = new ArrayList<>(b);
                    System.out.println(a + " " + c);
                }
                // one list fed from another, then copied: c can hold the elements of a
                void fedThenCopied(List<T> a, List<T> b) {
                    b.addAll(a);
                    List<T> c = new ArrayList<>(b);
                    System.out.println(a + " " + c);
                }
                // the same across a return
                List<T> returned(List<T> a, List<T> b) {
                    b.addAll(a);
                    return new ArrayList<>(b);
                }
            }
            """;

    @Test
    public void test1() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT1);
        PrepAnalyzer analyzer = new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build());
        analyzer.doPrimaryType(X);
        LinkComputer tlc = new LinkComputerImpl(javaInspector);
        for (String name : List.of("views", "copies", "fedThenCopied", "returned")) {
            MethodInfo method = X.methodStream().filter(m -> name.equals(m.name())).findFirst().orElseThrow();
            MethodLinkedVariables mlv = method.analysis().getOrCreate(METHOD_LINKS, () -> tlc.doMethod(method));
            System.out.println("SIBLING " + name + " summary: " + mlv);
            int i = 0;
            for (var statement : method.methodBody().statements()) {
                VariableData vd = VariableDataImpl.of(statement);
                int index = i++;
                vd.variableInfoStream().forEach(vi -> System.out.println("SIBLING " + name + " " + index + "  "
                        + vi.variable().simpleName() + ": " + vi.linkedVariables()));
            }
        }
    }
}
