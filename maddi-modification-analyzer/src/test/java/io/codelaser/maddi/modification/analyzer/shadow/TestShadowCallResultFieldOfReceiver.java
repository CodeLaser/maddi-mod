package io.codelaser.maddi.modification.analyzer.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/*
 The MODREACH twin of the link engine's TestCallResultFieldOfReceiver (CodeLaser/maddi-mod#80): a modification of a
 call's result that is a field of the receiver, or of an object reachable from it, modifies the receiver. The engine
 has that since #80; the reachability pass re-derived the parameter from the statements and lost it twice: a local
 holding the result ('w ≺ 0:parent') was followed through assigned-from links only, and a field whose scope is a call
 ('parent.getWrapper().getH().list.clear()') projected the field without the chain. Found by the rust-llvm
 experiment: rustc rejected 'via_loop(parent: &CNode)' writing through 'parent'.
 */
public class TestShadowCallResultFieldOfReceiver extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class C {
                static class Wrapper { int x; void set(int v) { x = v; } }
                static class Node {
                    Wrapper wrapper;
                    int type;
                    Node parent;
                    Wrapper getWrapper() {
                        Node node = this;
                        while (node.type == 1) {
                            node = node.parent;
                        }
                        return node.wrapper;
                    }
                }
                static void viaLoop(Node parent) { Wrapper w = parent.getWrapper(); w.x = 1; }
                static void viaChain(Node parent) { parent.getWrapper().x = 1; }
                static void viaCall(Node parent) { parent.getWrapper().set(1); }
                static void viaLocalCall(Node parent) { Wrapper w = parent.getWrapper(); w.set(1); }
                static class Holder {
                    java.util.List<Wrapper> list = new java.util.ArrayList<>();
                }
                static class W2 { Holder h = new Holder(); Holder getH() { return h; } }
                static class N2 {
                    W2 wrapper;
                    int type;
                    N2 parent;
                    W2 getWrapper() {
                        N2 node = this;
                        while (node.type == 1) {
                            node = node.parent;
                        }
                        return node.wrapper;
                    }
                }
                static void viaGetter(N2 parent) { parent.getWrapper().getH().list.clear(); }
                static int readOnly(Node parent) { Wrapper w = parent.getWrapper(); return w.x; }
            }
            """;

    private static final String EXPECTED = "{readOnly=true, viaCall=false, viaChain=false, viaGetter=false, "
                                           + "viaLocalCall=false, viaLoop=false}";

    @DisplayName("a modification of a call's result that is part of the parameter modifies the parameter (MODREACH)")
    @Test
    public void modReach() throws IOException {
        assertEquals(EXPECTED, unmodified(true).toString());
    }

    @DisplayName("... as in the fixpoint mode")
    @Test
    public void fixpoint() throws IOException {
        assertEquals(EXPECTED, unmodified(false).toString());
    }

    private Map<String, Boolean> unmodified(boolean modReach) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo C = bundle.javaInspector().parse("a.b.C", INPUT);
        List<Info> ao = bundle.prepAnalyzer().doPrimaryType(C);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(modReach).build());
        analyzer.analyze(ao);
        Map<String, Boolean> result = new TreeMap<>();
        for (MethodInfo m : C.methods()) {
            if (m.isStatic() && m.parameters().size() == 1) {
                result.put(m.name(), m.parameters().getFirst().isUnmodified());
            }
        }
        return result;
    }
}
