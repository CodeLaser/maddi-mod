package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;

/**
 * CodeLaser/maddi-mod#150: a constructor that delegates with {@code this(..)} to the constructor storing an argument
 * in a field must link that argument to the field, as the storing constructor does; so must an explicit
 * {@code super(..)}. The reachability pass is on, as where the issue was found (the fixpoint engine misses mkV as well).
 */
public class TestDelegatingConstructorLink extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            class X {
                static final class Proc { private int n; void set(int v) { n = v; } }
                static final class V {
                    private final int i;
                    private final Proc p;
                    V(int i, Proc p) { this.i = i; this.p = p; }
                    void fix() { if (p != null) p.set(i); }
                }
                static final class W {
                    private final Proc p;
                    W(int i, Proc p) { this(i, p, 0); }
                    W(int i, Proc p, int x) { this.p = p; }
                    void fix() { if (p != null) p.set(1); }
                }
                static class Base {
                    protected final Proc p;
                    Base(Proc p) { this.p = p; }
                }
                static final class Sub extends Base {
                    Sub(int i, Proc p) { super(p); }
                    void fix() { if (p != null) p.set(2); }
                }
                static final class User {
                    private final Proc proc;
                    User(Proc proc) { this.proc = proc; }
                    int mkV() { new V(1, proc).fix(); return 0; }
                    int mkW() { new W(1, proc).fix(); return 0; }
                    int mkSub() { new Sub(1, proc).fix(); return 0; }
                }
            }
            """;

    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(true)
                .build()).analyze(ao);
        // the delegating constructors carry the storing constructor's link; the argument is @Modified with it
        assertEquals("[-, 1:p*→this*.p] --> -", links(X.findSubType("W").findConstructor(2)));
        assertEquals("[-, 1:p*→this*.p] --> -", links(X.findSubType("Sub").findConstructor(2)));
        TypeInfo user = X.findSubType("User");
        String actual = "mkV=" + user.findUniqueMethod("mkV", 0).isNonModifying()
                        + " mkW=" + user.findUniqueMethod("mkW", 0).isNonModifying()
                        + " mkSub=" + user.findUniqueMethod("mkSub", 0).isNonModifying();
        assertEquals("mkV=false mkW=false mkSub=false", actual);
    }

    private static String links(MethodInfo methodInfo) {
        return String.valueOf(methodInfo.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                MethodLinkedVariablesImpl.class));
    }
}
