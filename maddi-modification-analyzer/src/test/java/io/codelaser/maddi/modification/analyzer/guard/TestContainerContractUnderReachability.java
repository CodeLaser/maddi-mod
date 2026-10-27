package io.codelaser.maddi.modification.analyzer.guard;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An authored {@code @Container} on an interface is a contract on every parameter of its bodiless methods, as an
 * authored {@code @NotModified} on such a parameter is. A modifying implementation breaks the contract and the guard
 * reports it; the interface's own verdicts stay what the author declared. Found through the IDE daemon, which runs
 * the reachability pass: there the interface came out {@code @Immutable(hc=true)}, not a container, with a modified
 * parameter.
 */
public class TestContainerContractUnderReachability extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.Container;
            class X {
                static class Mutable {
                    private int value;
                    void set(int v) { this.value = v; }
                    int get() { return value; }
                }
                @Container
                interface HasAdd {
                    void add(Mutable m);
                }
                static class BadImpl implements HasAdd {
                    @Override
                    public void add(Mutable m) { m.set(3); }
                }
            }
            """;

    @Test
    public void fixpoint() {
        test(false);
    }

    @Test
    public void reachability() {
        test(true);
    }

    private void test(boolean modReach) {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setGuardContracts(true)
                .setModificationViaReachability(modReach)
                .build()).analyze(ao);

        TypeInfo hasAdd = X.findSubType("HasAdd");
        MethodInfo add = hasAdd.findUniqueMethod("add", 1);
        assertTrue(add.parameters().getFirst().isUnmodified(), "the @Container contract covers add's parameter");
        assertTrue(hasAdd.analysis().getOrDefault(PropertyImpl.CONTAINER_TYPE, ValueImpl.BoolImpl.FALSE).isTrue(),
                "HasAdd is @Container by contract");

        MethodInfo badAdd = X.findSubType("BadImpl").findUniqueMethod("add", 1);
        assertTrue(!badAdd.parameters().getFirst().isUnmodified(), "the implementation does modify its argument");
    }
}
