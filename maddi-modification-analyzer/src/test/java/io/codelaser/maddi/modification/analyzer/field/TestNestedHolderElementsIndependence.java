package io.codelaser.maddi.modification.analyzer.field;

import static io.codelaser.maddi.cst.impl.analysis.PropertyImpl.INDEPENDENT_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * fernflower's StructBootstrapMethodsAttribute.initContent(data, pool), which the run-time check's 2c contradicted: the
 * holder fills a {@code List<List<PooledConstant>>} with constants it gets from the pool, whose own
 * {@code List<PooledConstant>} holds them. Both wrote the element type, so the constants are accessible content of both
 * (#159), and with a mutable element type the pool parameter is dependent, nested or not. But PooledConstant has one
 * final field and an empty method: {@code @Immutable(hc=true)}, its subclasses' fields hidden content seen through it
 * (accessible content is judged by declared types). Sharing one shares hidden content only: {@code @Independent(hc=true)}
 * is right, and the check now judges a shared object's mutability through the declared view (rust a8f7f3f).
 */
public class TestNestedHolderElementsIndependence extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.List;
            public class X {
                static class K { private int n; public void touch() { n++; } }
                // fernflower's PooledConstant: a final field and an empty method; its subclasses are mutable
                static class PC { public final int type; PC(int type) { this.type = type; } public void resolve(PPool p) { } }
                static class Prim extends PC { public Object value; Prim(int t) { super(t); }
                    @Override public void resolve(PPool p) { value = p.get(0); } }
                static class PPool {
                    private final List<PC> pool = new ArrayList<>();
                    public PC get(int i) { return pool.get(i); }
                }
                static class PFlat {
                    private final List<PC> args = new ArrayList<>();
                    public void init(int n, PPool pool) { for (int j = 0; j < n; ++j) args.add(pool.get(j)); }
                }
                static class PNested {
                    private final List<List<PC>> args = new ArrayList<>();
                    public void init(int n, PPool pool) {
                        List<PC> list = new ArrayList<>();
                        for (int j = 0; j < n; ++j) list.add(pool.get(j));
                        args.add(list);
                    }
                }
                static class Pool {
                    private final List<K> pool = new ArrayList<>();
                    public K get(int i) { return pool.get(i); }
                }
                // fernflower's shape: a list of lists, filled through the pool's getter
                static class Nested {
                    private final List<List<K>> args = new ArrayList<>();
                    public void init(int n, Pool pool) {
                        List<K> list = new ArrayList<>();
                        for (int j = 0; j < n; ++j) list.add(pool.get(j));
                        args.add(list);
                    }
                }
                // one level: a plain list filled through the getter
                static class Flat {
                    private final List<K> args = new ArrayList<>();
                    public void init(int n, Pool pool) {
                        for (int j = 0; j < n; ++j) args.add(pool.get(j));
                    }
                }
                // a single element through the getter
                static class Single {
                    private final List<K> args = new ArrayList<>();
                    public void init(Pool pool) { args.add(pool.get(0)); }
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).build()).analyze(ao);
        assertEquals("@Immutable(hc=true)", String.valueOf(x.findSubType("PC").analysis().getOrNull(
                io.codelaser.maddi.cst.impl.analysis.PropertyImpl.IMMUTABLE_TYPE, ValueImpl.ImmutableImpl.class)));
        String verdicts = List.of("Nested", "Flat", "Single", "PFlat", "PNested").stream().map(name -> {
            MethodInfo init = x.findSubType(name).methods().stream().filter(m -> m.name().equals("init"))
                    .findFirst().orElseThrow();
            return name + " pool=" + init.parameters().getLast().analysis().getOrNull(INDEPENDENT_PARAMETER,
                    ValueImpl.IndependentImpl.class);
        }).collect(Collectors.joining("\n"));
        assertEquals("""
                Nested pool=@Dependent
                Flat pool=@Dependent
                Single pool=@Dependent
                PFlat pool=@Independent(hc=true)
                PNested pool=@Independent(hc=true)""", verdicts);
    }
}
