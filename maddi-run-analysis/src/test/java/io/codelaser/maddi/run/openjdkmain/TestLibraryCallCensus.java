package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.cst.api.expression.ConstructorCall;
import io.codelaser.maddi.cst.api.expression.MethodCall;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Which library members Java corpora call, and what the analysis reads each call as: the worklist for the
 * annotated APIs, ranked on evidence. The Java counterpart of the Kotlin census ({@code -Dmaddi.libraryCallDump},
 * maddi-run-kotlin's RunMixedPrepAnalyzer). Parse only, no analysis: after the default results load
 * ({@link LoadAnalysisResults#ANALYZED_RESULTS}), every library method a corpus's source calls gets the shallow
 * defaults the link computer would give it, so "modifies" is what the analysis will read.
 * <p>
 * {@code CENSUS=<corpus>,<corpus>} chooses the test-oss corpora (each must be built). Per corpus,
 * {@code build/census/<corpus>.txt} holds one line per member, most-called first:
 * {@code <calls> <hinted|DEFAULT> <library> <member> <parameter types> <modifies>}, where modifies is "this" for a
 * modifying instance method and the index of every parameter read as modified ("-": none). A parameter whose
 * modifications are ignored (a function argument) does not count. {@code build/census/summary.txt} adds the
 * corpora up per library: calls, members, calls to members without hints, and calls that read an argument or the
 * receiver as modified, which is what hints would have to correct.
 */
@Tag("slow")
public class TestLibraryCallCensus {
    private static final String DEFAULT_CORPORA = "langchain4j,fernflower,timefold-solver,jenkins,activemq,retrofit,nacos";

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }

    // library -> {calls, members, calls without hints, modifying calls}, per corpus and over all of them
    private final Map<String, Map<String, long[]>> perCorpus = new TreeMap<>();

    @Test
    public void test() throws Exception {
        String corpora = Objects.requireNonNullElse(System.getenv("CENSUS"), DEFAULT_CORPORA);
        Path out = Path.of("build/census");
        Files.createDirectories(out);
        List<String> skipped = new ArrayList<>();
        for (String corpusName : corpora.split(",")) {
            // a corpus that does not parse (a stale jar, a missing build) is reported, not fatal: the census is a ranking
            try {
                census(corpusName.strip(), out);
            } catch (Exception | AssertionError e) {
                skipped.add(corpusName.strip() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
            }
            System.gc();
        }
        Map<String, long[]> total = new TreeMap<>();
        Map<String, Integer> corporaPerLibrary = new HashMap<>();
        perCorpus.forEach((_, libs) -> libs.forEach((lib, c) -> {
            long[] t = total.computeIfAbsent(lib, _ -> new long[4]);
            for (int i = 0; i < 4; i++) t[i] += c[i];
            corporaPerLibrary.merge(lib, 1, Integer::sum);
        }));
        List<String> lines = new ArrayList<>();
        lines.add("# corpora: " + String.join(", ", perCorpus.keySet()));
        skipped.forEach(s -> lines.add("# skipped " + s));
        lines.add("# library\tcorpora\tcalls\tmembers(sum)\tcallsWithoutHints\tmodifyingCalls");
        total.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[3], a.getValue()[3]))
                .forEach(e -> lines.add(e.getKey() + "\t" + corporaPerLibrary.get(e.getKey()) + "\t"
                                        + Arrays.stream(e.getValue()).mapToObj(Long::toString)
                                                .collect(Collectors.joining("\t"))));
        perCorpus.forEach((corpus, libs) -> {
            lines.add("");
            lines.add("## " + corpus);
            libs.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[3], a.getValue()[3]))
                    .limit(25)
                    .forEach(e -> lines.add(e.getKey() + "\t" + Arrays.stream(e.getValue())
                            .mapToObj(Long::toString).collect(Collectors.joining("\t"))));
        });
        Files.write(out.resolve("summary.txt"), lines);
        assertFalse(total.isEmpty());
    }

    private void census(String corpusName, Path out) throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse(corpusName);
        var javaInspector = corpus.javaInspector();
        AnalysisEngineImpl engine = new AnalysisEngineImpl();
        engine.resultsLoader(javaInspector.runtime(), javaInspector.mainSources()).load(LoadAnalysisResults.ANALYZED_RESULTS);

        Map<MethodInfo, Integer> calls = new HashMap<>();
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        Stream.concat(corpus.types().stream(), corpus.testTypes().stream())
                .forEach(t -> countLibraryCalls(t, calls, seen));
        engine.applyShallowDefaults(javaInspector.runtime(), calls.keySet());

        Map<String, long[]> libs = new TreeMap<>();
        List<String> lines = calls.entrySet().stream()
                .sorted(Map.Entry.<MethodInfo, Integer>comparingByValue().reversed()
                        .thenComparing(e -> e.getKey().fullyQualifiedName()))
                .map(e -> {
                    MethodInfo m = e.getKey();
                    boolean hinted = m.analysis().haveAnalyzedValueFor(PropertyImpl.ANNOTATED_API);
                    String modifies = modifies(m);
                    String lib = library(m.typeInfo());
                    long[] c = libs.computeIfAbsent(lib, _ -> new long[4]);
                    c[0] += e.getValue();
                    c[1]++;
                    if (!hinted) c[2] += e.getValue();
                    if (!"-".equals(modifies)) c[3] += e.getValue();
                    return e.getValue() + "\t" + (hinted ? "hinted" : "DEFAULT") + "\t" + lib + "\t"
                           + m.fullyQualifiedName() + "\t" + m.parameters().stream()
                                   .map(p -> p.parameterizedType().toString().replaceFirst("^Type ", ""))
                                   .collect(Collectors.joining(", ")) + "\t" + modifies;
                })
                .toList();
        Files.write(out.resolve(corpusName + ".txt"), lines);
        perCorpus.put(corpusName, libs);
    }

    private static String modifies(MethodInfo m) {
        List<String> list = new ArrayList<>();
        if (!m.isStatic() && !m.isConstructor()
            && !m.analysis().getOrDefault(PropertyImpl.NON_MODIFYING_METHOD, ValueImpl.BoolImpl.FALSE).isTrue()) {
            list.add("this");
        }
        for (ParameterInfo p : m.parameters()) {
            if (!p.analysis().getOrDefault(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue()
                && !p.analysis().getOrDefault(PropertyImpl.IGNORE_MODIFICATIONS_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue()) {
                list.add(String.valueOf(p.index()));
            }
        }
        return list.isEmpty() ? "-" : String.join(",", list);
    }

    // the jar (or jmod) the type was loaded from, without its version; the package's first three segments when the
    // source set says nothing
    private static String library(TypeInfo typeInfo) {
        var sourceSet = typeInfo.primaryType().compilationUnit().sourceSet();
        String name = sourceSet == null ? null : sourceSet.name();
        if (name == null || name.isBlank()) {
            String[] parts = typeInfo.primaryType().packageName().split("\\.");
            return String.join(".", Arrays.copyOf(parts, Math.min(3, parts.length)));
        }
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("\\.jar$", "");
        return name.replaceFirst("-\\d[\\w.\\-]*$", "");
    }

    private static void countLibraryCalls(TypeInfo type, Map<MethodInfo, Integer> calls, Map<Object, Boolean> seen) {
        if (seen.put(type, true) != null) return;
        type.subTypes().forEach(sub -> countLibraryCalls(sub, calls, seen));
        Stream.concat(type.constructors().stream(), type.methodStream()).forEach(m -> {
            if (seen.put(m, true) != null || m.methodBody() == null) return;
            m.methodBody().visit(e -> {
                MethodInfo callee = e instanceof MethodCall mc ? mc.methodInfo()
                        : e instanceof ConstructorCall cc ? cc.constructor() : null;
                if (callee != null && callee.typeInfo().primaryType().compilationUnit().externalLibrary()) {
                    calls.merge(callee, 1, Integer::sum);
                }
                return true;
            });
        });
    }
}
