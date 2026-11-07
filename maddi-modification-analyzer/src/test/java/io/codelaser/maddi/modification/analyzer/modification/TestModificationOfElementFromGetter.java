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
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * A getter hands out an element of a map or list held in a field, and the caller modifies that element: the caller
 * modifies its object, as the direct {@code classes.get(n)} form does (CodeLaser/maddi-mod#100). Found by the rust-llvm
 * oracle on fernflower's {@code StructContext.instanceOf}: {@code getClass(valclass).getPool()}, a lazily loading
 * getter, against {@code &self}.
 */
public class TestModificationOfElementFromGetter extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            public class Ctx {
                public static class C {
                    private Object pool;
                    public Object getPool() { if (pool == null) pool = new Object(); return pool; }
                }
                private final Map<String, C> classes = new HashMap<>();
                private final List<C> list = new ArrayList<>();
                public C getClass(String n) { return classes.get(n); }
                public C first() { return list.get(0); }
                public boolean direct(String n) { C c = classes.get(n); return c.getPool() != null; }
                public boolean viaGetter(String n) { C c = getClass(n); return c.getPool() != null; }
                public boolean viaGetterChain(String n) { return getClass(n).getPool() != null; }
                public boolean viaFirst() { return first().getPool() != null; }
                public boolean reads(String n) { C c = getClass(n); return c != null; }
            }
            """;

    private static final String EXPECTED = "{direct=true, first=false, getClass=false, reads=false, viaFirst=true, "
                                           + "viaGetter=true, viaGetterChain=true}";

    @DisplayName("modifying an element a getter hands out makes the caller modifying")
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
        TypeInfo ctx = bundle.javaInspector().parse("a.b.Ctx", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(ctx);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(modReach).build());
        analyzer.analyze(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        for (MethodInfo m : ctx.methods()) {
            actual.put(m.name(), Boolean.toString(m.isModifying()));
        }
        return actual;
    }
}
