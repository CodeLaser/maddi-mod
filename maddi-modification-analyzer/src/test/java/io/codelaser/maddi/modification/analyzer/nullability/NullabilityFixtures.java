package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.impl.ModAnalyzerForTesting;
import io.codelaser.maddi.modification.analyzer.impl.SingleIterationAnalyzerImpl;
import org.intellij.lang.annotations.Language;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The harness the per-part test classes share: {@code run} builds a pass per fixture, {@code verdicts} and
 * {@code explain} print its report, {@code k} a type in Kotlin's notation.
 * <p>
 * The declaration pass (docs/design/nullability.md §4.2 M3, T3): one verdict per declaration, in Kotlin's
 * notation ({@code String?} nullable, {@code String} non-null, {@code String!} unspecified), under the
 * {@code @NullMarked} policy (no null reaches it: non-null).
 */
abstract class NullabilityFixtures extends CommonTest {

    TypeInfo parsed;

    List<Info> analysisOrder;

    Report run(String fqn, String src) {
        return run(fqn, src, Policy.NULL_MARKED);
    }

    Report run(String fqn, String src, Policy policy) {
        TypeInfo t = javaInspector.parse(fqn, src);
        parsed = t;
        List<Info> ao = prepWork(t);
        ModAnalyzerForTesting analyzer = new SingleIterationAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setNullability(true).build());
        analyzer.go(ao, 3);
        analysisOrder = ao;
        return new NullabilityPass(policy).go(ao);
    }

    static String k(ParameterizedType pt) {
        if (pt.arrays() > 0) {
            String own = switch (pt.nullable()) {
                case NULLABLE -> "?";
                case NONNULL -> "";
                case UNSPECIFIED -> "!";
            };
            return k(pt.componentType()) + "[]" + own;
        }
        String base = pt.typeParameter() != null ? pt.typeParameter().simpleName()
                : pt.typeInfo() != null ? pt.typeInfo().simpleName() : "?"; // 'Class<?>'
        String args = pt.parameters().isEmpty() ? ""
                : pt.parameters().stream().map(NullabilityFixtures::k).collect(Collectors.joining(", ", "<", ">"));
        String suffix = switch (pt.nullable()) {
            case NULLABLE -> "?";
            case NONNULL -> "";
            case UNSPECIFIED -> "!";
        };
        return base + args + suffix;
    }

    // every verdict of the type, sorted: 'f: String?', 'm(0:s): String?' for a parameter, 'm(): String' for a return
    static String verdicts(Report report) {
        return report.verdicts().entrySet().stream()
                .map(e -> label(e.getKey()) + ": " + k(e.getValue()))
                .sorted().collect(Collectors.joining("\n"));
    }

    static String label(Info info) {
        return switch (info) {
            case FieldInfo fi -> fi.name();
            case ParameterInfo pi -> pi.methodInfo().name() + "(" + pi.index() + ":" + pi.name() + ")";
            case MethodInfo mi -> mi.name() + "()";
            default -> info.toString();
        };
    }

    static String explain(Report report) {
        return report.verdicts().keySet().stream()
                .filter(i -> report.cause().containsKey(i) || report.seedOrigin().containsKey(i))
                .map(report::explain).sorted().collect(Collectors.joining("\n"));
    }

    @Language("java")
    static final String LOCALS = """
            package a.b;
            import java.util.List;
            class V {
                String reassigned(boolean b) { String x = null; if (b) x = "a"; return "z"; }
                void sibling(boolean b) {
                    if (b) { String s = null; System.out.println(s); }
                    else { String s = "t"; System.out.println(s); }
                }
                void loops(List<String> list) {
                    for (String e : list) { System.out.println(e); }
                    for (String f = null; f != null; ) { }
                }
                void caught() {
                    try { System.out.println("x"); } catch (RuntimeException ex) { System.out.println(ex); }
                }
                String flows() { String y = null; String w = y; return w; }
            }
            """;

    // every local verdict, sorted: 'method.name: Type', a counter per method and name so sibling locals both show
    static String locals(Report report) {
        Map<String, Integer> seen = new java.util.HashMap<>();
        return report.locals().entrySet().stream()
                .map(e -> {
                    String key = e.getKey().methodInfo().name() + "." + e.getKey().name();
                    int n = seen.merge(key, 1, Integer::sum);
                    return key + (n > 1 ? "#" + n : "") + ": " + k(e.getValue());
                })
                .sorted().collect(Collectors.joining("\n"));
    }

    // the verdict of every declaration, by fully qualified name
    static Map<String, String> byName(Report report) {
        return report.verdicts().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().fullyQualifiedName(), e -> k(e.getValue())));
    }

    List<io.codelaser.maddi.cst.api.expression.MethodCall> calls(String method, String callee) {
        MethodInfo mi = parsed.methods().stream().filter(m -> m.name().equals(method)).findFirst().orElseThrow();
        List<io.codelaser.maddi.cst.api.expression.MethodCall> out = new java.util.ArrayList<>();
        mi.methodBody().visit(e -> {
            if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc && mc.methodInfo() != null
                && mc.methodInfo().name().equals(callee)) out.add(mc);
            return true;
        });
        return out;
    }
}
