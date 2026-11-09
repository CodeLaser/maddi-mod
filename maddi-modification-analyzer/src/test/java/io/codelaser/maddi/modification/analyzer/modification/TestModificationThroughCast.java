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
 * A modifying call on a cast of a parameter ({@code ((Sync) stat).removeExc()}) modifies the parameter: the cast is
 * the same object. The fixpoint had it; the MODREACH re-derivation did not project a cast receiver, and decided the
 * parameter unmodified. Found by the rust-llvm oracle on fernflower's {@code DomHelper.removeSynchronizedHandler}:
 * rustc refused the call through {@code &Statement}.
 */
public class TestModificationThroughCast extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class Stats {
                public static class Stat {
                    public int type;
                    private final List<Stat> stats = new ArrayList<>();
                    public List<Stat> getStats() { return stats; }
                    public int size() { return stats.size(); }
                }
                public static class Sync extends Stat {
                    private int exc;
                    public void removeExc() { exc = 0; }
                }
                public static void viaCast(Stat stat) { ((Sync) stat).removeExc(); }
                public static void viaTypeCheck(Stat stat) { if (stat.type == 3) ((Sync) stat).removeExc(); }
                public static void recursive(Stat stat) {
                    for (Stat st : stat.getStats()) recursive(st);
                    if (stat.type == 3) ((Sync) stat).removeExc();
                }
                public static void viaLocal(Stat stat) { Sync s = (Sync) stat; s.removeExc(); }
                public static int reads(Stat stat) { return ((Sync) stat).size(); }
            }
            """;

    private static final String EXPECTED = "{reads=unmodified, recursive=modified, viaCast=modified, "
                                           + "viaLocal=modified, viaTypeCheck=modified}";

    @DisplayName("a modifying call on a cast of a parameter modifies the parameter")
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
        TypeInfo stats = bundle.javaInspector().parse("a.b.Stats", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(stats);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(bundle.javaInspector(),
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(modReach).build());
        analyzer.analyze(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        for (MethodInfo m : stats.methods()) {
            actual.put(m.name(), m.parameters().getFirst().isModified() ? "modified" : "unmodified");
        }
        return actual;
    }
}
