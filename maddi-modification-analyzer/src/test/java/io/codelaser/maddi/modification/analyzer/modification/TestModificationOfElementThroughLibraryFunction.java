package io.codelaser.maddi.modification.analyzer.modification;

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
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * A library callee that hands its receiver's elements to a function ({@code Iterable.forEach}, {@code Map.forEach},
 * {@code Optional.ifPresent}): when the lambda or method reference passed modifies its argument, the receiver's
 * elements are modified, as in the loop form (CodeLaser/maddi-mod#99). Found by the rust-llvm oracle on fernflower's
 * {@code ExprProcessor.popNestedTypeAnnotation}: rustc rejected the body against {@code &Vec<..>}.
 */
public class TestModificationOfElementThroughLibraryFunction extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayDeque;
            import java.util.Deque;
            import java.util.List;
            import java.util.Map;
            import java.util.Optional;
            class P {
                static class H {
                    private final Deque<String> paths = new ArrayDeque<>();
                    Deque<String> getPaths() { return paths; }
                    void touch() { paths.pop(); }
                    int size() { return paths.size(); }
                }
                static void viaForEachGetter(List<H> hs) { hs.forEach(h -> h.getPaths().pop()); }
                static void viaForEachCall(List<H> hs) { hs.forEach(h -> h.touch()); }
                static void viaForEachRef(List<H> hs) { hs.forEach(H::touch); }
                static void viaLoopGetter(List<H> hs) { for (H h : hs) h.getPaths().pop(); }
                static void viaLoopCall(List<H> hs) { for (H h : hs) h.touch(); }
                static void viaMapForEach(Map<String, H> hs) { hs.forEach((k, h) -> h.touch()); }
                static void viaIfPresent(Optional<H> hs) { hs.ifPresent(H::touch); }
                static void readsForEach(List<H> hs) { hs.forEach(h -> System.out.println(h.size())); }
                static void readsForEachRef(List<H> hs) { hs.forEach(H::size); }
                // stored, not changed: in the link-only harness (no field analysis) store's parameter stays undecided
                // and the list reads modified (TestForEachLambda); the analyzer decides both unmodified
                private static final java.util.Set<H> SINK = new java.util.HashSet<>();
                static void store(H h) { SINK.add(h); }
                static void storeForEach(List<H> hs) { hs.forEach(h -> store(h)); }
            }
            """;

    // first parameter: deep / structural
    private static final String EXPECTED = "{readsForEach=unmodified/unmodified, readsForEachRef=unmodified/unmodified, "
                                           + "store=unmodified/unmodified, storeForEach=unmodified/unmodified, "
                                           + "viaForEachCall=modified/unmodified, viaForEachGetter=modified/unmodified, "
                                           + "viaForEachRef=modified/unmodified, viaIfPresent=modified/unmodified, "
                                           + "viaLoopCall=modified/unmodified, viaLoopGetter=modified/unmodified, "
                                           + "viaMapForEach=modified/unmodified}";

    @DisplayName("a lambda handed the elements by a library callee modifies them: the receiver is deep-modified")
    @Test
    public void fixpoint() throws IOException {
        assertEquals(EXPECTED, run(false).toString());
    }

    @DisplayName("... and the same with the MODREACH cutover on")
    @Test
    public void modReach() throws IOException {
        assertEquals(EXPECTED, run(true).toString());
    }

    private Map<String, String> run(boolean modReach) throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo p = bundle.javaInspector().parse("a.b.P", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(p);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(modReach).build());
        analyzer.analyze(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        for (MethodInfo m : p.methods()) {
            if (!m.isStatic()) continue;
            ParameterInfo pi = m.parameters().getFirst();
            actual.put(m.name(), (pi.isModified() ? "modified" : "unmodified") + "/"
                                 + (pi.isStructurallyUnmodified() ? "unmodified" : "modified"));
        }
        return actual;
    }
}
