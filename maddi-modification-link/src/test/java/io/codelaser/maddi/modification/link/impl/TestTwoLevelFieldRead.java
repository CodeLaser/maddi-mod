package io.codelaser.maddi.modification.link.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.variable.MethodLinkedVariables;

/**
 * CodeLaser/maddi-mod#86: a summary's two-level field read, {@code ofParent ← this.parent.wrapper}, keeps both levels
 * at the call site: {@code p.ofParent()} is {@code 0:p.parent.wrapper}, as the read written out is. The method was
 * taken for a getter of {@code wrapper}, and every call site replaces a getter call by a read of the field.
 */
public class TestTwoLevelFieldRead extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class C {
                static class Wrapper { int x; }
                static class Node {
                    Wrapper wrapper;
                    Node parent;
                    Wrapper ofParent() { return parent.wrapper; }
                    void setParentWrapper(Wrapper w) { parent.wrapper = w; }
                }
                static Wrapper viaCall(Node p) { return p.ofParent(); }
                static Wrapper writtenOut(Node p) { return p.parent.wrapper; }
                static void setViaCall(Node p, Wrapper w) { p.setParentWrapper(w); }
                static void setWrittenOut(Node p, Wrapper w) { p.parent.wrapper = w; }
                // a getter of an outer instance's field is no getter of the inner object
                class Inner { Wrapper outer() { return w0; } }
                Wrapper w0;
                static Wrapper viaInner(C c) { return c.new Inner().outer(); }
            }
            """;

    @Test
    public void test() {
        TypeInfo c = javaInspector.parse("a.b.C", INPUT);
        new PrepAnalyzer(runtime, new PrepAnalyzer.Options.Builder().build()).doPrimaryType(c);
        LinkComputerImpl lc = new LinkComputerImpl(javaInspector);
        Map<String, String> mlvs = new LinkedHashMap<>();
        c.recursiveSubTypeStream().flatMap(TypeInfo::constructorAndMethodStream).forEach(m -> {
            MethodLinkedVariables mlv = lc.doMethod(m);
            mlvs.put(m.name(), mlv.toString());
        });
        for (String m : new String[]{"setParentWrapper", "outer", "ofParent"}) {
            var mi = c.recursiveSubTypeStream().flatMap(TypeInfo::methodStream).filter(x -> x.name().equals(m))
                    .findFirst().orElseThrow();
            assertEquals(null, mi.getSetField().field(), m + " is no getter or setter");
        }
        assertEquals("[] --> ofParent←this.parent.wrapper", mlvs.get("ofParent"));
        assertEquals("[-] --> viaCall←0:p.parent.wrapper", mlvs.get("viaCall"));
        assertEquals(mlvs.get("writtenOut").replace("writtenOut", "viaCall"), mlvs.get("viaCall"));
        // the setter writes the parent's field (w's own modification flag is the callee's parameter verdict, which the
        // link computer alone does not decide)
        assertEquals("[-, 1:w*→0:p.parent*.wrapper] --> -", mlvs.get("setViaCall").replaceFirst("^\\[[^,]*, ", "[-, "));
    }
}
