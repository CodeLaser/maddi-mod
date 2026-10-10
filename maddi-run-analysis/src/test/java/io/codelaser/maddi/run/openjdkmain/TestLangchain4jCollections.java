package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.analysis.api.PrepOutcome;
import io.codelaser.maddi.analysis.api.PrepRequest;
import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.run.analysis.AnalysisEngineImpl;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The modification analysis on langchain4j-core, without printing: every collection-typed field, parameter and
 * method return, with what the analysis says about it. The Kotlin translation reads the structural verdict
 * (CodeLaser/maddi-mod#25) to choose a read-only
 * {@code List}/{@code Map} over {@code MutableList}/{@code MutableMap}. Written to
 * {@code build/langchain4j-collections.txt}: a summary per kind first, then one line per declaration. The
 * modification side's instrument for the second J2K corpus; the J2K ratchet is the printer's.
 */
@Tag("slow")
public class TestLangchain4jCollections {
    private static final String JDK_HINTS =
            "../../maddi/maddi-aapi-archive/src/main/resources/io/codelaser/maddi/aapi/archive/analyzedPackageFiles/jdk";
    private static final Set<String> COLLECTIONS = Set.of("java.util.List", "java.util.Set", "java.util.Map",
            "java.util.Collection", "java.util.Deque", "java.util.Queue", "java.util.SortedMap", "java.util.SortedSet",
            "java.util.NavigableMap", "java.util.NavigableSet", "java.lang.Iterable");

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    public void test() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("langchain4j");
        JavaInspector javaInspector = corpus.javaInspector();
        AnalysisEngineImpl engine = new AnalysisEngineImpl();
        engine.resultsLoader(javaInspector.runtime(), javaInspector.mainSources()).load(List.of(JDK_HINTS));
        var parseResult = corpus.summary().parseResult();
        PrepOutcome prep = engine.prep(new PrepRequest(javaInspector.runtime(), Set.copyOf(parseResult.primaryTypes()),
                parseResult.sourceSetToModuleInfoMap().values(), _ -> false, true, true));
        List<Info> order = new ComputeAnalysisOrder().go(prep.callGraph().graph(), true);
        IteratingAnalyzer analyzer = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(30)
                        .setStopWhenCycleDetectedAndNoImprovements(true)
                        .setFaultTolerant(true)
                        // MODREACH_OFF: the fixpoint's own verdicts, without the reachability pass's final word
                        .setModificationViaReachability(System.getenv("MODREACH_OFF") == null)
                        .build());
        analyzer.analyze(order, prep.callGraph().graph());

        List<String> lines = new ArrayList<>();
        Map<String, int[]> summary = new TreeMap<>(); // kind -> {unmodified, modified/unknown}
        corpus.types().stream().flatMap(TypeInfo::recursiveSubTypeStream).forEach(t -> {
            for (FieldInfo fi : t.fields()) {
                if (!isCollection(fi.type())) continue;
                count(summary, "FIELD", fi.isUnmodified());
                count(summary, "FIELD-STRUCTURAL", fi.isStructurallyUnmodified());
                lines.add("FIELD  " + fi.fullyQualifiedName() + " : " + fi.type().simpleString() + " |"
                          + values(fi, PropertyImpl.FINAL_FIELD, PropertyImpl.UNMODIFIED_FIELD,
                        PropertyImpl.STRUCTURALLY_UNMODIFIED_FIELD, PropertyImpl.IMMUTABLE_FIELD,
                        PropertyImpl.INDEPENDENT_FIELD));
            }
            t.constructorAndMethodStream().forEach(mi -> {
                if (mi.isSyntheticConstructor()) return;
                for (ParameterInfo pi : mi.parameters()) {
                    if (!isCollection(pi.parameterizedType())) continue;
                    count(summary, "PARAM", pi.isUnmodified());
                    count(summary, "PARAM-STRUCTURAL", pi.isStructurallyUnmodified());
                    lines.add("PARAM  " + pi.fullyQualifiedName() + " : " + pi.parameterizedType().simpleString()
                              + " | overrides=" + !mi.overrides().isEmpty() + degraded(mi) + values(pi,
                            PropertyImpl.UNMODIFIED_PARAMETER, PropertyImpl.STRUCTURALLY_UNMODIFIED_PARAMETER,
                            PropertyImpl.PARAMETER_ASSIGNED_TO_FIELD,
                            PropertyImpl.IMMUTABLE_PARAMETER, PropertyImpl.INDEPENDENT_PARAMETER));
                }
                if (!mi.isConstructor() && isCollection(mi.returnType())) {
                    lines.add("RETURN " + mi.fullyQualifiedName() + " : " + mi.returnType().simpleString()
                              + " | overrides=" + !mi.overrides().isEmpty() + degraded(mi) + values(mi,
                            PropertyImpl.NON_MODIFYING_METHOD, PropertyImpl.STRUCTURALLY_NON_MODIFYING_METHOD,
                            PropertyImpl.GET_SET_FIELD,
                            PropertyImpl.IMMUTABLE_METHOD, PropertyImpl.INDEPENDENT_METHOD));
                    summary.computeIfAbsent("RETURN", _ -> new int[2])[0]++;
                }
            });
        });
        Collections.sort(lines);
        // -Dprobe.method=<name> (or PROBE_METHOD, which a --no-daemon build passes on): per statement of that method, each parameter's UNMODIFIED_VARIABLE and links
        String probe = System.getProperty("probe.method", java.util.Objects.requireNonNullElse(System.getenv("PROBE_METHOD"), ""));
        if (!probe.isEmpty()) {
            corpus.types().stream().flatMap(TypeInfo::recursiveSubTypeStream)
                    .flatMap(TypeInfo::constructorAndMethodStream)
                    .filter(mi -> mi.name().equals(probe) && mi.methodBody() != null)
                    .peek(mi -> {
                        var mlv = mi.analysis().getOrNull(
                                io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS,
                                io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.class);
                        lines.add("PROBE " + mi.fullyQualifiedName() + " modified=" + (mlv == null ? "-" : mlv.modified()));
                        for (ParameterInfo pi : mi.parameters()) {
                            lines.add("PROBE " + pi.name() + " throughPassedFunction=" + pi.analysis().getOrNull(
                                    PropertyImpl.MODIFIED_THROUGH_PASSED_FUNCTION, Value.class));
                        }
                    })
                    .forEach(mi -> mi.methodBody().visit(e -> {
                        if (e instanceof io.codelaser.maddi.cst.api.statement.Statement st) {
                            var vd = io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl.of(st);
                            if (vd != null) {
                                for (ParameterInfo pi : mi.parameters()) {
                                    if (!vd.isKnown(pi.fullyQualifiedName())) continue;
                                    var vi = vd.variableInfo(pi.fullyQualifiedName());
                                    lines.add("PROBE " + mi.name() + " @" + st.source().compact2() + " "
                                              + pi.name() + " unmodified=" + vi.analysis().getOrNull(
                                            io.codelaser.maddi.modification.prepwork.variable.impl.VariableInfoImpl.UNMODIFIED_VARIABLE,
                                            Value.class) + " links=" + vi.linkedVariablesOrEmpty());
                                }
                            }
                        }
                        return true;
                    }));
        }
        List<String> out = new ArrayList<>();
        summary.forEach((kind, c) -> out.add(kind.equals("RETURN") ? "# RETURN total=" + c[0]
                : "# " + kind + " unmodified=" + c[0] + " modified/unknown=" + c[1]));
        out.addAll(lines);
        Files.write(Path.of("build/langchain4j-collections.txt"), out);
        assertFalse(lines.isEmpty());
    }

    private static boolean isCollection(ParameterizedType pt) {
        return pt.arrays() == 0 && pt.typeInfo() != null && COLLECTIONS.contains(pt.typeInfo().fullyQualifiedName());
    }

    private static void count(Map<String, int[]> summary, String kind, boolean unmodified) {
        summary.computeIfAbsent(kind, _ -> new int[2])[unmodified ? 0 : 1]++;
    }

    // a method whose analysis gave up (fault tolerance): its parameters keep the conservative 'modified'
    private static String degraded(MethodInfo mi) {
        return mi.analysis().getOrDefault(PropertyImpl.DEGRADED_ANALYSIS_METHOD,
                io.codelaser.maddi.cst.impl.analysis.ValueImpl.BoolImpl.FALSE).isTrue() ? " DEGRADED" : "";
    }

    private static String values(Info info, Property... properties) {
        StringBuilder sb = new StringBuilder();
        for (Property p : properties) {
            Value v = info.analysis().getOrNull(p, Value.class);
            sb.append(' ').append(p.key()).append('=').append(v == null ? "-" : v);
        }
        return sb.toString();
    }
}
