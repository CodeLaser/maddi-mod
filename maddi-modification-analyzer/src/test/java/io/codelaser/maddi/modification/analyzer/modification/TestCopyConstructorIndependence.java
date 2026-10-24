package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#64: a copy constructor's result is independent of its argument when the elements are immutable.
 * A map's elements are the virtual key/value pairs ({@code §$$}), as immutable as their key and value types.
 */
public class TestCopyConstructorIndependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            class P {
                private final Map<String, Integer> stockLevels = new HashMap<>();
                private final Set<String> names = new HashSet<>();
                private final Map<String, StringBuilder> notes = new HashMap<>();
                void set(String k, int v) { stockLevels.put(k, v); names.add(k); }
                public Map<String, Integer> hashMap() { return new HashMap<>(stockLevels); }
                public Map<String, Integer> linkedHashMap() { return new LinkedHashMap<>(stockLevels); }
                public Map<String, Integer> treeMap() { return new TreeMap<>(stockLevels); }
                public Set<String> hashSet() { return new HashSet<>(names); }
                public List<String> keys() { return new ArrayList<>(stockLevels.keySet()); }
                public Map<String, StringBuilder> notesCopy() { return new HashMap<>(notes); }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    @DisplayName("a copy of a map or set of immutable elements is independent")
    @Test
    public void test() {
        TypeInfo p = javaInspector.parse("a.b.P", INPUT);
        List<Info> ao = prepWork(p);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).build()).analyze(ao);
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("hashMap", "linkedHashMap", "treeMap", "hashSet", "keys")) {
            MethodInfo mi = method(p, name);
            sb.append(name).append(' ')
                    .append(mi.analysis().getOrDefault(PropertyImpl.INDEPENDENT_METHOD,
                            ValueImpl.IndependentImpl.DEPENDENT))
                    .append(' ').append(mi.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                            MethodLinkedVariablesImpl.class)).append('\n');
        }
        String independent = ValueImpl.IndependentImpl.INDEPENDENT.toString();
        for (String name : List.of("hashMap", "linkedHashMap", "treeMap", "hashSet", "keys")) {
            assertEquals(independent, method(p, name).analysis().getOrDefault(PropertyImpl.INDEPENDENT_METHOD,
                    ValueImpl.IndependentImpl.DEPENDENT).toString(), sb.toString());
        }
        // the copy shares the StringBuilder values
        assertEquals(ValueImpl.IndependentImpl.DEPENDENT.toString(), method(p, "notesCopy").analysis()
                .getOrDefault(PropertyImpl.INDEPENDENT_METHOD, ValueImpl.IndependentImpl.DEPENDENT).toString());
    }
}
