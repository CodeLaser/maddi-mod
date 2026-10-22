package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.analyzer.nullability.NullabilityPass;
import io.codelaser.maddi.modification.analyzer.nullability.Switches;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The nullability pass on fernflower, without printing: every nullable verdict (top level and type arguments) with
 * its cause chain, written to {@code build/fernflower-nullability-causes.txt} ({@code NULLABILITY_CORPUS} names
 * another J2K corpus, e.g. {@code langchain4j}). The verdict side's instrument for
 * the printer's findings; the J2K ratchet itself is {@link TestJavaToKotlinFernflowerNullability}'s, run by the
 * printer's side.
 */
@Tag("slow")
public class TestFernflowerNullabilityCauses {
    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        // NULLABILITY_CORPUS (passed on by a --no-daemon build): another J2K corpus, e.g. langchain4j
        String corpusName = java.util.Objects.requireNonNullElse(System.getenv("NULLABILITY_CORPUS"), "fernflower");
        // nacos: one module per run, J2K_NACOS_MAIN as TestJavaToKotlinNacosNullability (api/main by default)
        String nacosMain = java.util.Objects.requireNonNullElse(System.getenv("J2K_NACOS_MAIN"), "api/main");
        long parseStart = System.nanoTime();
        // jenkins: its core module, as TestJavaToKotlinJenkinsNullability
        JavaToKotlinRatchet.Corpus corpus = switch (corpusName) {
            case "nacos" -> JavaToKotlinRatchet.parse("nacos", nacosMain, null, false, true);
            case "jenkins" -> JavaToKotlinRatchet.parse("jenkins", "Jenkins core/main", null, false);
            // timefold: its core module with tests, as TestJavaToKotlinTimefoldNullability
            case "timefold" -> JavaToKotlinRatchet.parse("timefold-solver", "core/main", "core/test-classes", false);
            default -> JavaToKotlinRatchet.parse(corpusName);
        };
        JavaInspector javaInspector = corpus.javaInspector();
        AnalysisEngineImpl engine = new AnalysisEngineImpl();
        long parseMs = (System.nanoTime() - parseStart) / 1_000_000;
        long prepStart = System.nanoTime();
        engine.resultsLoader(javaInspector.runtime(), javaInspector.mainSources()).load(List.of(JDK_HINTS));
        var parseResult = corpus.summary().parseResult();
        PrepOutcome prep = engine.prep(new PrepRequest(javaInspector.runtime(), Set.copyOf(parseResult.primaryTypes()),
                parseResult.sourceSetToModuleInfoMap().values(), _ -> false, true, true));
        List<Info> order = new ComputeAnalysisOrder().go(prep.callGraph().graph(), true);
        long prepMs = (System.nanoTime() - prepStart) / 1_000_000;
        long analysisStart = System.nanoTime();
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(30)
                        .setStopWhenCycleDetectedAndNoImprovements(true)
                        .setFaultTolerant(true)
                        .setModificationViaReachability(true)
                        .setNullability(true)
                        .build());
        analyzer.analyze(order, prep.callGraph().graph());
        long analysisMs = (System.nanoTime() - analysisStart) / 1_000_000;
        // NULLABILITY_POLICY=diagnose: the diagnostics' policy (flow only, no contracts, closed world); else Kotlin's.
        // NULLABILITY_WORLD=open: the open world (timefold's diagnostics); NULLABILITY_TEST_NULLS=off: withoutTestNulls
        NullabilityPass.Policy policy = "diagnose".equals(System.getenv("NULLABILITY_POLICY"))
                ? NullabilityPass.Policy.NULL_MARKED_FLOW_ONLY.withoutContracts()
                        .withWorld(NullabilityPass.World.CLOSED)
                : NullabilityPass.Policy.KOTLIN;
        if ("open".equals(System.getenv("NULLABILITY_WORLD"))) policy = policy.withWorld(NullabilityPass.World.OPEN);
        if ("off".equals(System.getenv("NULLABILITY_TEST_NULLS"))) policy = policy.withoutTestNulls();
        // NULLABILITY_SIDES=on,NOX,NOY+NOZ: one analysis, then one pass per side with those gates off besides the
        // environment's (Switches), each written to build/<corpus>-nullability-causes-<side>.txt; without it, one pass
        // to build/<corpus>-nullability-causes.txt. The phases' times go to build/<corpus>-nullability-timing.txt.
        String sides = System.getenv("NULLABILITY_SIDES");
        List<String> timing = new java.util.ArrayList<>(List.of("parse " + parseMs + " ms", "prep " + prepMs + " ms",
                "analysis " + analysisMs + " ms"));
        for (String side : sides == null ? List.of("") : List.of(sides.split(","))) {
            Switches.offForNextPasses(side.isEmpty() || "on".equals(side) ? Set.of() : Set.of(side.split("\\+")));
            long passStart = System.nanoTime();
            NullabilityPass.Report report;
            try {
                report = new NullabilityPass(policy).go(order);
            } finally {
                Switches.offForNextPasses(Set.of());
            }
            long passMs = (System.nanoTime() - passStart) / 1_000_000;
            // NULLMARKEDDEBUG=<file>: each pass rewrites it; keep one per side
            String boundsDump = System.getenv("NULLMARKEDDEBUG");
            if (boundsDump != null && sides != null && java.nio.file.Files.exists(Path.of(boundsDump))) {
                java.nio.file.Files.copy(Path.of(boundsDump), Path.of(boundsDump + "-" + side),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> lines = lines(report, corpus);
            timing.add("pass " + (side.isEmpty() ? "on" : side) + " " + passMs + " ms");
            String suffix = side.isEmpty() ? "" : "-" + side;
            java.nio.file.Files.write(Path.of("build/" + corpusName + "-nullability-causes" + suffix + ".txt"), lines);
            org.junit.jupiter.api.Assertions.assertFalse(lines.isEmpty());
        }
        java.nio.file.Files.write(Path.of("build/" + corpusName + "-nullability-timing.txt"), timing);
        timing.forEach(t -> System.err.println("NULLTIMING " + corpusName + " " + t));
    }

    private static List<String> lines(NullabilityPass.Report report, JavaToKotlinRatchet.Corpus corpus) {
        List<String> lines = new java.util.ArrayList<>();
        report.verdicts().forEach((info, pt) -> {
            String n = info.fullyQualifiedName();
            for (int i = 0; i < pt.parameters().size(); i++) {
                if (pt.parameters().get(i).nullable() == io.codelaser.maddi.cst.api.type.NullableState.NULLABLE) {
                    lines.add(n + " [" + i + "] :: " + report.explain(new NullabilityPass.Arg(info, i)));
                }
            }
            if (pt.nullable() == io.codelaser.maddi.cst.api.type.NullableState.NULLABLE) {
                lines.add(n + " TOP :: " + report.explain(info));
            }
        });
        // a superclass's nullable type arguments (NullabilityFacts.parentClass), with what reaches them
        report.parentClasses().forEach((type, parent) -> {
            for (int i = 0; i < parent.parameters().size(); i++) {
                if (parent.parameters().get(i).nullable() == io.codelaser.maddi.cst.api.type.NullableState.NULLABLE) {
                    lines.add(type.fullyQualifiedName() + " SUPER [" + i + "] :: "
                              + report.explain(new NullabilityPass.Arg(new NullabilityPass.AnonymousThis(type), i)));
                }
            }
        });
        // array locals with nullable elements, and every local of a method named by -Dcauses.method
        String probe = System.getProperty("causes.method", "@@none");
        report.locals().forEach((local, pt) -> {
            String where = local.methodInfo().fullyQualifiedName();
            boolean nullableElements = pt.arrays() > 0 && pt.componentType().nullable()
                    == io.codelaser.maddi.cst.api.type.NullableState.NULLABLE;
            for (int i = 0; i < pt.parameters().size(); i++) {
                if (pt.parameters().get(i).nullable() == io.codelaser.maddi.cst.api.type.NullableState.NULLABLE) {
                    lines.add(where + " LOCAL " + local.name() + " [" + i + "] :: "
                              + report.explain(new NullabilityPass.Arg(local, i)));
                }
            }
            if (nullableElements) {
                lines.add(where + " LOCAL " + local.name() + " ELEMENTS :: "
                          + report.explain(new NullabilityPass.Content(local)));
            } else if (!probe.isEmpty() && where.contains(probe)) {
                lines.add(where + " LOCAL " + local.name() + " " + pt + " " + pt.nullable()
                          + (pt.arrays() > 0 ? " elements " + pt.componentType().nullable() : ""));
            }
        });
        java.util.Collections.sort(lines);
        // CAUSES_CALLS=<method>:<callee>: in that method, each call to that callee with its argument links
        String calls = java.util.Objects.requireNonNullElse(System.getenv("CAUSES_CALLS"), "");
        if (calls.contains(":")) {
            String method = calls.substring(0, calls.indexOf(':'));
            String callee = calls.substring(calls.indexOf(':') + 1);
            corpus.types().stream().flatMap(io.codelaser.maddi.cst.api.info.TypeInfo::recursiveSubTypeStream)
                    .flatMap(io.codelaser.maddi.cst.api.info.TypeInfo::constructorAndMethodStream)
                    .filter(mi -> mi.name().equals(method) && mi.methodBody() != null)
                    .forEach(mi -> mi.methodBody().visit(e -> {
                        if (e instanceof io.codelaser.maddi.cst.api.expression.MethodCall mc
                            && mc.methodInfo() != null && callee.equals(mc.methodInfo().name())) {
                            lines.add("CALL " + mc.source().compact2() + " " + mc.methodInfo().fullyQualifiedName()
                                      + " args=" + mc.analysis().getOrNull(
                                    io.codelaser.maddi.modification.link.impl.LinkComputerImpl.LINKED_VARIABLES_ARGUMENTS,
                                    io.codelaser.maddi.modification.link.impl.LinkComputerImpl.ListOfLinksImpl.class));
                        }
                        return true;
                    }));
        }
        return lines;
    }
}
