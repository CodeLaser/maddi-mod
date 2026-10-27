package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * maddi-mod#68's tolerated null stops at a declared slot: a public parameter written into the elements of a field
 * that holds no null does not tolerate one, as a parameter written into such a field does not. Found on the
 * maddi-java-demo, where every public adder and setter came out {@code @Nullable} in the IDE.
 */
public class TestTolerantSlots extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class Bag {
                private final List<String> items = new ArrayList<>();
                private final List<String> maybe = new ArrayList<>();
                public void add(String item) { items.add(item); }
                public boolean contains(String item) { return items.contains(item); }
                public void addMaybe(String item) { maybe.add(item); }
                public void clear() { maybe.add(null); }
                public String describe(String s) { return "bag " + s; }
                public int count(List<String> list, String s) { List<String> copy = new ArrayList<>(list); copy.add(s); return copy.size(); }
            }
            """;

    @Test
    public void test() {
        TypeInfo bag = javaInspector.parse("a.b.Bag", INPUT);
        List<Info> ao = prepWork(bag);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setNullability(true).build()).analyze(ao);
        NullabilityPass.Report report = new NullabilityPass(NullabilityPass.Policy.NULL_MARKED
                .withPreconditionsNonNull()).go(ao);

        // into the elements of a field no null is written into: not tolerated
        assertEquals(NullableState.NONNULL, state(report, bag, "add", 0));
        // into the elements of a field that holds null anyway: tolerated
        assertEquals(NullableState.NULLABLE, state(report, bag, "addMaybe", 0));
        // concatenated: tolerated (#68)
        assertEquals(NullableState.NULLABLE, state(report, bag, "describe", 0));
        // into a local's elements: followed on, and nothing there demands it
        assertEquals(NullableState.NULLABLE, state(report, bag, "count", 1));
    }

    private static NullableState state(NullabilityPass.Report report, TypeInfo type, String method, int index) {
        ParameterInfo pi = type.methods().stream().filter(m -> m.name().equals(method)).findFirst().orElseThrow()
                .parameters().get(index);
        return report.verdicts().get(pi).nullable();
    }
}
