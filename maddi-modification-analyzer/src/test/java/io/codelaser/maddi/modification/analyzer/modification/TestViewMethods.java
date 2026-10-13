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
 * be modified, but the call itself changes nothing. The convention (JavaUtil): the argument {@code @NotModified}, the
 * result left dependent on it, so that a modification through the view reaches the argument.
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
        assertEquals("syncDiscarded=U syncRead=U syncAdd=M syncAddChained=M setFromMapAdd=M setFromMapRead=U "
                     + "lifoPush=M checkedClear=M checkedRead=U unmodRead=U ", sb.toString());
    }
}
