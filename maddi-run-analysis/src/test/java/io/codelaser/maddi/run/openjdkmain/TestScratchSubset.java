package io.codelaser.maddi.run.openjdkmain;

import ch.qos.logback.classic.Level;
import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.Summary;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.run.config.ShippedAnalysisResults;
import io.codelaser.maddi.run.config.util.JsonStreaming;
import io.codelaser.maddi.util.corpus.Corpora;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/*
 CodeLaser/maddi-mod#158, a reduction instrument: one corpus is parsed in full, and the analysis runs over the
 elements whose fully qualified name starts with one of the given prefixes only. Everything else keeps what the
 shipped analysis results and the shallow path give it.
 SCRATCH_CORPUS (e.g. jenkins), SCRATCH_PKGS (comma-separated prefixes), SCRATCH_SKIP (comma-separated substrings to
 leave out), SCRATCH_ITER (default 60), SCRATCH_NULL (presence: nullability on). Without SCRATCH_CORPUS: skipped.
 */
@Tag("slow")
public class TestScratchSubset {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    }

    @Test
    public void test() throws IOException {
        String corpus = System.getenv("SCRATCH_CORPUS");
        Assumptions.assumeTrue(corpus != null, "SCRATCH_CORPUS not set");
        Corpora.oss(corpus).requireConfig();
        InputConfigurationImpl inputConfiguration = JsonStreaming.objectMapper()
                .readValue(Corpora.oss(corpus).config().toFile(), InputConfigurationImpl.class);
        JavaInspector javaInspector = new JavaInspectorImpl(true, false);
        javaInspector.setJdkInternals(true);
        javaInspector.initialize(inputConfiguration);
        javaInspector.preload("java.base::java.util");
        Summary summary = javaInspector.parse(new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setFailFast(false).setParallel(true).setIgnoreModule(true).build());
        AnalysisEngineImpl engine = new AnalysisEngineImpl();
        engine.resultsLoader(javaInspector.runtime(), javaInspector.mainSources())
                .load(ShippedAnalysisResults.available(Main.class));
        var parseResult = summary.parseResult();
        PrepOutcome prep = engine.prep(new PrepRequest(javaInspector.runtime(), Set.copyOf(parseResult.primaryTypes()),
                parseResult.sourceSetToModuleInfoMap().values(), _ -> false, true, true));
        List<Info> full = new ComputeAnalysisOrder().go(prep.callGraph().graph(), true);
        String[] prefixes = System.getenv("SCRATCH_PKGS").split(",");
        String[] skips = System.getenv().getOrDefault("SCRATCH_SKIP", "").split(",");
        List<Info> order = full.stream().filter(i -> {
            String fqn = i.fullyQualifiedName();
            for (String skip : skips) if (!skip.isEmpty() && fqn.contains(skip)) return false;
            for (String prefix : prefixes) if (fqn.startsWith(prefix)) return true;
            return false;
        }).toList();
        System.out.println("SCRATCH order " + order.size() + " of " + full.size());
        IteratingAnalyzerImpl analyzer = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(Integer.parseInt(System.getenv().getOrDefault("SCRATCH_ITER", "60")))
                        .setFaultTolerant(true)
                        .setNullability(System.getenv("SCRATCH_NULL") != null)
                        .build());
        analyzer.analyze(order, prep.callGraph().graph());
        System.out.println("SCRATCH certified=" + analyzer.certifiedWithoutFrozenValues());
    }
}
