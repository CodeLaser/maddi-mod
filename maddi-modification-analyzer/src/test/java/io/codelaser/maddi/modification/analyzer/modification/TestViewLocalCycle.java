package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#75: a local initialised with a view-factory call ({@code rv.§m ≡ 0:list.§m}, #40) whose
 * argument leads back to that local. The reachability pass projected the local onto the view's argument, the argument
 * through its links onto the local, and so on, until the stack overflowed (langchain4j-core). The pass then failed
 * silently in every round, leaving the fixpoint's verdicts.
 */
public class TestViewLocalCycle extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X {
                void reassigned(List<String> list, int n) {
                    List<String> current = list;
                    for (int i = 0; i < n; i++) {
                        List<String> view = Collections.synchronizedList(current);
                        current = view;
                    }
                    current.add("x");
                }
                void swapped(List<String> list, boolean b) {
                    List<String> a = list;
                    List<String> s = Collections.synchronizedList(a);
                    if (b) a = s;
                    s.add("x");
                }
                void read(List<String> list, int n) {
                    List<String> current = list;
                    for (int i = 0; i < n; i++) {
                        List<String> view = Collections.synchronizedList(current);
                        current = view;
                    }
                    System.out.println(current.size());
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        IteratingAnalyzerImpl iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(10)
                        .setModificationViaReachability(true).build());
        iterating.analyze(ao);
        assertEquals(0, iterating.modReachFailures(), "the reachability pass must run");
        StringBuilder sb = new StringBuilder();
        for (String m : List.of("reassigned", "swapped", "read")) {
            sb.append(m).append('=').append(X.findUniqueMethod(m, 2).parameters().getFirst().isUnmodified() ? "U" : "M")
                    .append(' ');
        }
        // swapped: the view local's modification reaches the argument through the cycle (U with NOVIEWLOCAL).
        // reassigned: should be M, a list or a view of it is modified; the fixpoint gives U as well, and so does the
        // pass with NOVIEWLOCAL, so that is the loop's linking, not this cycle
        assertEquals("reassigned=U swapped=M read=U ", sb.toString());
    }
}
