package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#129: a value read through {@code entrySet()} and passed as an argument that the callee does not
 * modify must not make the map modified, exactly as when it is read through {@code get(k)}.
 */
public class TestEntrySetValueArgument extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class N {
                static class Trace extends IllegalStateException { Trace(N a, N b) { super(a.name + b.name); } }
                final String name;
                final Map<N, Trace> prior = new HashMap<>();
                final Map<N, Trace> prior2 = new HashMap<>();
                N(String name) { this.name = name; }
                Trace flat(N node) {
                    for (Map.Entry<N, Trace> entry : prior.entrySet()) {
                        Trace path = new Trace(entry.getKey(), entry.getKey());
                        path.setStackTrace(entry.getValue().getStackTrace());
                        return path;
                    }
                    return null;
                }
                Trace flat2(N node) {
                    Trace t = prior2.get(node);
                    Trace path = new Trace(node, node);
                    path.setStackTrace(t.getStackTrace());
                    return path;
                }
            }
            """;

    private String run(boolean modReach) {
        TypeInfo t = javaInspector.parse("a.b.N", INPUT);
        List<Info> ao = prepWork(t);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30).setModificationViaReachability(modReach).build()).analyze(ao);
        TreeMap<String, String> out = new TreeMap<>();
        for (MethodInfo mi : t.methods()) out.put(mi.name(), String.valueOf(mi.isNonModifying()));
        for (FieldInfo fi : t.fields()) out.put("." + fi.name(), String.valueOf(fi.isUnmodified()));
        return out.toString();
    }

    private static final String EXPECTED = "{.name=true, .prior=true, .prior2=true, flat=true, flat2=true}";

    @DisplayName("#129: entrySet value passed as a non-modified argument (reachability)")
    @Test
    public void reachability() {
        assertEquals(EXPECTED, run(true));
    }

    @DisplayName("#129: entrySet value passed as a non-modified argument (fixpoint alone)")
    @Test
    public void fixpoint() {
        assertEquals(EXPECTED, run(false));
    }
}
