package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#55: a parameter (or the receiver) that a recursion cycle only reads. The fixpoint alone folds the
 * cycle's undecided value as modified and the cycle sustains it; the reachability pass, the CLI's default and the
 * single writer of these verdicts, finds nothing reachable that modifies them.
 */
public class TestRecursiveReadOnly extends CommonTest {

    @Language("java")
    private static final String NODE = """
            package a.b;
            import java.util.*;
            class Node {
                Node parent; final Map<String, String> props = new HashMap<>(); final List<Node> children = new ArrayList<>();
                boolean contains(Node x) { if (this == x) return true; for (Node c : children) if (c.contains(x)) return true; return false; }
                static String get(Node n, String k) { String v = n.props.get(k); return v != null || n.parent == null ? v : get(n.parent, k); }
                static int size(Node n) { return 1 + sizeAll(n.children); }
                static int sizeAll(List<Node> ns) { int s = 0; for (Node n : ns) s += size(n); return s; }
                static int depth(Node n) { return n.parent == null ? 0 : 1 + n.parent.hashCode(); }
            }
            """;

    @Language("java")
    private static final String STAT = """
            package a.b;
            class Stat {
                Stat first; boolean leaf;
                Stat head() { return leaf ? this : first.head(); }
                Stat headLoop() { Stat s = this; while (!s.leaf) s = s.first; return s; }
                Stat firstOf() { return leaf ? this : first; }
            }
            """;

    private String run(String fqn, String input, boolean modReach) {
        TypeInfo t = javaInspector.parse(fqn, input);
        List<Info> ao = prepWork(t);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30).setModificationViaReachability(modReach).build()).analyze(ao);
        TreeMap<String, String> out = new TreeMap<>();
        for (MethodInfo mi : t.methods()) {
            out.put(mi.name(), String.valueOf(mi.isNonModifying()));
            for (ParameterInfo pi : mi.parameters()) {
                if (!pi.parameterizedType().isPrimitiveStringClass()) out.put(mi.name() + ":" + pi.name(), String.valueOf(pi.isUnmodified()));
            }
        }
        return out.toString();
    }

    @DisplayName("the reachability pass: every parameter and receiver the cycles only read is unmodified")
    @Test
    public void reachability() {
        assertEquals("{contains=true, contains:x=true, depth=true, depth:n=true, get=true, get:n=true, size=true, "
                     + "size:n=true, sizeAll=true, sizeAll:ns=true}", run("a.b.Node", NODE, true));
        assertEquals("{firstOf=true, head=true, headLoop=true}", run("a.b.Stat", STAT, true));
    }

    @DisplayName("the fixpoint alone: the cycles keep their modified value (the issue's numbers)")
    @Test
    public void fixpointAlone() {
        assertEquals("{contains=true, contains:x=false, depth=true, depth:n=true, get=true, get:n=false, size=true, "
                     + "size:n=false, sizeAll=true, sizeAll:ns=false}", run("a.b.Node", NODE, false));
        assertEquals("{firstOf=true, head=false, headLoop=true}", run("a.b.Stat", STAT, false));
    }
}
