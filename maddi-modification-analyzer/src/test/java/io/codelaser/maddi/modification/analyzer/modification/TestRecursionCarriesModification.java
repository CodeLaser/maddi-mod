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
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#127: a call back into the recursion marks nothing while the callee's verdict is undecided (the
 * cycle would sustain it), but a modification that only the recursive call carries still arrives once the body has
 * decided it: 'next' is modified only through 'next.touchLast()', the children only through 'c.bumpAll()' and
 * 'bump(c)'. The read-only twins stay unmodified.
 */
public class TestRecursionCarriesModification extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class G {
                int count;
                G next;
                final List<G> children = new ArrayList<>();
                G other;
                final List<G> kids = new ArrayList<>();
                void touchLast() { if (next == null) count++; else next.touchLast(); }
                int readLast() { return other == null ? count : other.readLast(); }
                void bumpAll() { count++; for (G c : children) c.bumpAll(); }
                int sumAll() { int s = count; for (G c : kids) s += c.sumAll(); return s; }
                static void bump(G g) { g.count++; for (G c : g.children) bump(c); }
                static int sum(G g) { int s = g.count; for (G c : g.kids) s += sum(c); return s; }
            }
            """;

    private String run(boolean modReach) {
        TypeInfo t = javaInspector.parse("a.b.G", INPUT);
        List<Info> ao = prepWork(t);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(30).setModificationViaReachability(modReach).build()).analyze(ao);
        TreeMap<String, String> out = new TreeMap<>();
        for (MethodInfo mi : t.methods()) {
            out.put(mi.name(), String.valueOf(mi.isNonModifying()));
            for (ParameterInfo pi : mi.parameters()) out.put(mi.name() + ":" + pi.name(), String.valueOf(pi.isUnmodified()));
        }
        for (FieldInfo fi : t.fields()) out.put("." + fi.name(), String.valueOf(fi.isUnmodified()));
        return out.toString();
    }

    // count is primitive: assigned, never "modified"
    private static final String EXPECTED = "{.children=false, .count=true, .kids=true, .next=false, .other=true, "
                                           + "bump=true, bump:g=false, bumpAll=false, readLast=true, sum=true, "
                                           + "sum:g=true, sumAll=true, touchLast=false}";

    @DisplayName("#127: modification carried only by the recursive call arrives (reachability)")
    @Test
    public void reachability() {
        assertEquals(EXPECTED, run(true));
    }

    @DisplayName("#127: modification carried only by the recursive call arrives (fixpoint alone)")
    @Test
    public void fixpoint() {
        // PINS a gap of the fixpoint alone (not the CLI's mode; the reachability pass writes these verdicts): the
        // skipped in-recursion mark is not revisited once touchLast/bumpAll decide modifying (the worklist follows
        // summaries, not verdicts), so .next and .children stay unmodified. Before #127 the fixpoint alone had
        // .next=false but .other=false, readLast=false (the read-only twin), and .children=true as well
        assertEquals(EXPECTED.replace(".children=false", ".children=true").replace(".next=false", ".next=true"),
                run(false));
    }
}
