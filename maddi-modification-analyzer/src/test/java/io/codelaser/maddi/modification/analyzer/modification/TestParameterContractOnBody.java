package io.codelaser.maddi.modification.analyzer.modification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.GuardAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * CodeLaser/maddi-mod#125, option 3: {@code @NotModified(contract = true)} on a parameter of a concrete, overridable
 * method decides that parameter, and wins over the #116 dispatch union. The shape is jenkins' {@code ACL.lambda2}: an
 * anonymous override hands its arguments to an unknown {@code BiFunction.apply}, which counts them as modified; without
 * the contract the union carries that to the base method, to every caller, and onto the {@code static final}
 * constants passed in. The override keeps its own verdict and the guard reports it.
 */
public class TestParameterContractOnBody extends CommonTest {

    @Language("java")
    private static final String SOURCE = """
            package a.b;
            import io.codelaser.maddi.annotation.NotModified;
            import java.util.function.BiFunction;
            public class X {
                public static class Permission {
                    public boolean enabled = true;
                    private final String name;
                    public Permission(String name) { this.name = name; }
                    public String getName() { return name; }
                }
                public static class Auth {
                    private final String user;
                    public Auth(String user) { this.user = user; }
                    public String user() { return user; }
                }
                public abstract static class Acl {
                    public boolean hasPermission2(@NotModified(contract = true) Auth a,
                                                  @NotModified(contract = true) Permission permission) {
                        throw new UnsupportedOperationException("implement hasPermission2");
                    }
                    public final void checkPermission(Auth a, Permission p) {
                        if (!hasPermission2(a, p)) throw new SecurityException(p.getName());
                    }
                    public static Acl lambda2(final BiFunction<Auth, Permission, Boolean> impl) {
                        return new Acl() {
                            @Override
                            public boolean hasPermission2(Auth a, Permission permission) {
                                return impl.apply(a, permission);
                            }
                        };
                    }
                }
                public static class Uncontracted {
                    public boolean check(Auth a, Permission permission) { return a != null; }
                    public static Uncontracted lambda(final BiFunction<Auth, Permission, Boolean> impl) {
                        return new Uncontracted() {
                            @Override
                            public boolean check(Auth a, Permission permission) { return impl.apply(a, permission); }
                        };
                    }
                }
                public static final Permission READ = new Permission("read");
                public static final Permission WRITE = new Permission("write");
                public static void requireRead(Acl acl, Auth a) { acl.checkPermission(a, READ); }
                public static boolean canWrite(Uncontracted u, Auth a) { return u.check(a, WRITE); }
            }
            """;

    @DisplayName("#125: a parameter contract on a concrete method beats the dispatch union (fixpoint)")
    @Test
    public void fixpoint() {
        test(false);
    }

    @DisplayName("#125: a parameter contract on a concrete method beats the dispatch union (reachability)")
    @Test
    public void reachability() {
        test(true);
    }

    private void test(boolean viaReachability) {
        boolean before = io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION;
        io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION = true;
        try {
            TypeInfo X = javaInspector.parse("a.b.X", SOURCE);
            List<Info> ao = prepWork(X);
            var iterating = new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                    .setMaxIterations(10)
                    .setModificationViaReachability(viaReachability)
                    .build());
            iterating.analyze(ao);

            TypeInfo acl = X.findSubType("Acl");
            MethodInfo hasPermission2 = method(acl, "hasPermission2");
            MethodInfo override = anonymousOverride(ao, hasPermission2);
            MethodInfo check = method(X.findSubType("Uncontracted"), "check");
            MethodInfo uncontractedOverride = anonymousOverride(ao, check);
            String summary = "base=" + params(hasPermission2)
                             + ", override=" + params(override)
                             + ", checkPermission=" + params(method(acl, "checkPermission"))
                             + ", requireRead=" + params(method(X, "requireRead"))
                             + ", READ=" + X.getFieldByName("READ", true).isUnmodified()
                             + ", uncontracted=" + params(check)
                             + ", uncontractedOverride=" + params(uncontractedOverride)
                             + ", WRITE=" + X.getFieldByName("WRITE", true).isUnmodified();
            // contracted: the union does not overrule the base; the override keeps its own (sound) verdict.
            // control: the same shape without a contract keeps the union verdict, and WRITE is modified. Measured: the
            // fixpoint alone does not carry the anonymous override's verdict up to the base for this shape, so only
            // the reachability pass (on in the corpus runs) shows the control
            String control = viaReachability ? "uncontracted=false/false, uncontractedOverride=false/false, WRITE=false"
                    : "uncontracted=true/true, uncontractedOverride=false/false, WRITE=true";
            assertEquals("base=true/true, override=false/false, checkPermission=true/true, requireRead=true/true, "
                         + "READ=true, " + control, summary);
            assertTrue(iterating.messages().stream().anyMatch(m ->
                            GuardAnalyzerImpl.CONTRACT_VIOLATION.equals(m.category())
                            && m.message().contains("hasPermission2")),
                    () -> "expected a contract violation on the override; got " + iterating.messages());
        } finally {
            io.codelaser.maddi.modification.prepwork.Util.DISPATCH_UNION = before;
        }
    }

    private static MethodInfo anonymousOverride(List<Info> ao, MethodInfo base) {
        return ao.stream().filter(i -> i instanceof MethodInfo m && base.name().equals(m.name())
                                       && m.overrides().contains(base))
                .map(i -> (MethodInfo) i).findFirst().orElseThrow();
    }

    private static String params(MethodInfo methodInfo) {
        return methodInfo.parameters().stream().map(pi -> Boolean.toString(pi.isUnmodified()))
                .collect(Collectors.joining("/"));
    }

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methodStream().filter(m -> name.equals(m.name())).findFirst().orElseThrow();
    }
}
