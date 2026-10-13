package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Library "view" methods: {@code Collections.synchronizedList(list)} returns an object through which {@code list} can
 * be modified, but the call itself changes nothing. The JDK hints state the argument {@code @NotModified} and
 * {@code @Independent(dependentReturnValue = true)}; the shallow link computer shares the modification component of
 * the result and that argument ({@code rv.§m ≡ 0:list.§m}, CodeLaser/maddi-mod#40), so that only a modification
 * through the view reaches the argument. Before the link existed the hint read every case U, which is unsound, and
 * the factories kept the default (every case M). An unmodifiable view's argument is read-only either way.
 */
public class TestViewMethods extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class X {
                void syncDiscarded(List<String> list) { Collections.synchronizedList(list); }
                void syncRead(List<String> list) { System.out.println(Collections.synchronizedList(list).size()); }
                void syncAdd(List<String> list) { List<String> s = Collections.synchronizedList(list); s.add("x"); }
                void syncAddChained(List<String> list) { Collections.synchronizedList(list).add("x"); }
                void setFromMapAdd(Map<String, Boolean> map) { Set<String> s = Collections.newSetFromMap(map); s.add("x"); }
                void setFromMapRead(Map<String, Boolean> map) { System.out.println(Collections.newSetFromMap(map).size()); }
                void lifoPush(Deque<String> deque) { Queue<String> q = Collections.asLifoQueue(deque); q.add("x"); }
                void checkedClear(List<String> list) { Collections.checkedList(list, String.class).clear(); }
                void checkedRead(List<String> list) { System.out.println(Collections.checkedList(list, String.class).isEmpty()); }
                void unmodRead(List<String> list) { System.out.println(Collections.unmodifiableList(list).size()); }
                static Set<String> wrapper(Map<String, Boolean> map) { return Collections.newSetFromMap(map); }
                void wrapperAdd(Map<String, Boolean> map) { wrapper(map).add("x"); }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        var iterating = new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl(javaInspector,
                new io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(10)
                        .setModificationViaReachability(true)
                        .build());
        iterating.analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String m : List.of("syncDiscarded", "syncRead", "syncAdd", "syncAddChained", "setFromMapAdd",
                "setFromMapRead", "lifoPush", "checkedClear", "checkedRead", "unmodRead")) {
            sb.append(m).append('=').append(X.findUniqueMethod(m, 1).parameters().getFirst().isUnmodified() ? "U" : "M")
                    .append(' ');
        }
        // a source method passing the view on: its own parameter is read only, and a caller modifying the view it
        // returns modifies the caller's map (guava's Sets.newSetFromMap)
        assertEquals("wrapper=" + X.findUniqueMethod("wrapper", 1).parameters().getFirst().isUnmodified()
                     + " wrapperAdd=" + X.findUniqueMethod("wrapperAdd", 1).parameters().getFirst().isUnmodified(),
                "wrapper=true wrapperAdd=false");
        assertEquals("syncDiscarded=U syncRead=U syncAdd=M syncAddChained=M setFromMapAdd=M setFromMapRead=U "
                     + "lifoPush=M checkedClear=M checkedRead=U unmodRead=U ", sb.toString());
    }
}
