package io.codelaser.maddi.modification.analyzer.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#154: a local holding a view of a varargs array ({@code Arrays.asList(a, b)}) wraps a fresh array
 * of all the trailing arguments. Replacing a slot of the view modifies none of them; modifying an element may modify
 * any of them, in the element's own mode. The engine linked the view's modification face to the last argument's
 * ({@code l.§m ≡ 1:b.§mT}, gate NOVARARGSARRAYFACE), the pass projected the view onto the first argument (gate
 * NOVARARGSVIEW), and a modified call result reached the container but not the objects it was given (gate
 * NOCALLRESULTCOELEMENT; {@code addThenModify} too).
 */
public class TestVarargsView extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Arrays;
            import java.util.List;
            class X {
                static class B { int i; B(int i) { this.i = i; } B copy() { return new B(i); } }
                static void setSlot(B b)       { List<B> l = Arrays.asList(b.copy(), new B(2)); l.set(0, new B(3)); }
                static void setSlotDirect(B b) { List<B> l = Arrays.asList(b, new B(2));        l.set(0, new B(3)); }
                static void modifyElement(B b) { List<B> l = Arrays.asList(b, new B(2));        l.get(0).i = 4; }
                static void modifySecond(B a, B b) { List<B> l = Arrays.asList(a, b);           l.get(1).i = 4; }
                // an array passed as the varargs array itself: the view is that array's
                static void setInArray(B[] bs) { List<B> l = Arrays.asList(bs); l.set(0, new B(3)); }
                // the same element rule for a plain list
                static void addThenModify(B b) { List<B> l = new java.util.ArrayList<>(); l.add(b); l.get(0).i = 4; }
            }
            """;

    @Test
    public void reachability() {
        test(true);
    }

    @Test
    public void fixpoint() {
        test(false);
    }

    private void test(boolean reachability) {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(reachability).build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("setSlot", "setSlotDirect", "modifyElement", "modifySecond", "setInArray", "addThenModify")) {
            MethodInfo m = X.methods().stream().filter(mi -> mi.name().equals(name)).findFirst().orElseThrow();
            sb.append(name);
            for (ParameterInfo pi : m.parameters()) {
                sb.append(' ').append(pi.name()).append('=').append(pi.isUnmodified()).append('/')
                        .append(pi.isStructurallyUnmodified());
            }
            sb.append('\n');
        }
        // unmodified/structurally unmodified
        assertEquals("""
                setSlot b=true/true
                setSlotDirect b=true/true
                modifyElement b=false/false
                modifySecond a=false/false b=false/false
                setInArray bs=false/false
                addThenModify b=false/false
                """, sb.toString());
    }
}
