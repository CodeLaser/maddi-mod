package io.codelaser.maddi.modification.analyzer.ring;

import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.graph.G;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/*
 CodeLaser/maddi-mod#158, wrap and unwrap round a call cycle: the shape of Jenkins' Queue.Task.getDefaultAuthentication
 and getDefaultAuthentication2. Two default methods call each other; one direction wraps the result in a new object
 (toSpring: a field deeper), the other unwraps it again (fromSpring: reads that field) or wraps it the other way.
 */
@EnabledIfEnvironmentVariable(named = "LINKCHURN", matches = ".+", disabledReason = "CodeLaser/maddi-mod#158: a reproduction that prints; run with LATESTWINSCOUNTS=1 LINKCHURN=1")
public class TestWrapUnwrapRing extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            public class W {
                interface Spring { Object principal(); }
                interface Acegi {
                    Object principal();
                    static Acegi fromSpring(Spring a) {
                        if (a == SYSTEM2) return SYSTEM;
                        if (a instanceof SpringImpl) return ((SpringImpl) a).delegate;
                        return new AcegiImpl(a);
                    }
                    default Spring toSpring() { return new SpringImpl(this); }
                }
                static final class SpringImpl implements Spring {
                    final Acegi delegate;
                    SpringImpl(Acegi delegate) { this.delegate = delegate; }
                    @Override public Object principal() { return delegate.principal(); }
                }
                static final class AcegiImpl implements Acegi {
                    final Spring delegate;
                    AcegiImpl(Spring delegate) { this.delegate = delegate; }
                    @Override public Object principal() { return delegate.principal(); }
                }
                static final class Root implements Spring {
                    @Override public Object principal() { return "system"; }
                }
                static final Spring SYSTEM2 = new Root();
                static final Acegi SYSTEM = new AcegiImpl(SYSTEM2);

                interface Task {
                    boolean overridden();
                    default Spring getDefault2() {
                        if (overridden()) {
                            return getDefault().toSpring();
                        } else {
                            return SYSTEM2;
                        }
                    }
                    default Acegi getDefault() {
                        return Acegi.fromSpring(getDefault2());
                    }
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo x = javaInspector.parse("a.b.W", INPUT);
        G<Info> graph = prepAnalyzer.doPrimaryTypesReturnGraph(Set.of(x));
        List<Info> ao = new ComputeAnalysisOrder().go(graph);
        IteratingAnalyzerImpl a = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(60).build());
        a.analyze(ao, graph);
        System.out.println("RING certified=" + a.certifiedWithoutFrozenValues());
    }
}
