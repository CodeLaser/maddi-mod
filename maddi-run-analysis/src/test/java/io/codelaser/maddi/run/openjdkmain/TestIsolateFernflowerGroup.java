package io.codelaser.maddi.run.openjdkmain;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.common.util.IsolateClass;
import io.codelaser.maddi.run.j2k.JavaToKotlinRatchet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi#180: a group isolate out of a real corpus. fernflower's {@code DomHelper} is isolated on its own,
 * the corpus types that isolate reaches are added to the set, and the group's tree has to compile with javac against
 * the JDK alone. The group holds interfaces ({@code IMatchable}) and uses JetBrains' type-use annotations, the two
 * shapes the unit tests of {@code IsolateClass} did not have.
 */
@Tag("slow")
public class TestIsolateFernflowerGroup {

    @BeforeAll
    public static void beforeAll() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }

    @Test
    public void domHelperGroup() throws Exception {
        JavaToKotlinRatchet.Corpus corpus = JavaToKotlinRatchet.parse("fernflower");
        Map<String, TypeInfo> byFqn = corpus.types().stream()
                .collect(Collectors.toMap(TypeInfo::fullyQualifiedName, t -> t));
        TypeInfo domHelper = byFqn.get("org.jetbrains.java.decompiler.modules.decompiler.DomHelper");

        IsolateClass isolateClass = new IsolateClass(corpus.javaInspector());
        IsolateClass.Result alone = isolateClass.isolate(domHelper);
        List<TypeInfo> group = new ArrayList<>(List.of(domHelper));
        alone.reachedPerType().values().stream().flatMap(Set::stream)
                .map(TypeInfo::fullyQualifiedName).sorted().map(byFqn::get)
                .filter(t -> t != null && t != domHelper).forEach(group::add);

        IsolateClass.Result result = new IsolateClass(corpus.javaInspector()).isolate(group);
        Map<MethodInfo, String> memberSources = new LinkedHashMap<>();
        for (MethodInfo original : result.markers().values()) {
            memberSources.put(original, verbatim(original));
        }
        Map<String, String> tree = isolateClass.print(result, memberSources);

        Path out = Path.of("build/isolate/fernflower-domhelper");
        if (Files.exists(out)) {
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, String> e : tree.entrySet()) {
            Path p = out.resolve("src").resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
            files.add(p);
        }
        List<String> errors = javac(files, out.resolve("classes"));
        errors.forEach(System.out::println);
        System.out.println(group.size() + " isolated, " + tree.size() + " files, " + errors.size() + " errors");
        assertEquals(0, errors.size(), "javac errors, printed above");
    }

    private static List<String> javac(List<Path> files, Path classes) throws IOException {
        Files.createDirectories(classes);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
            compiler.getTask(null, fm, diagnostics, List.of("-d", classes.toString(), "-proc:none", "-Xmaxerrs", "1000"),
                    null, fm.getJavaFileObjectsFromPaths(files)).call();
        }
        return diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> (d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName() + ":"
                                                        + d.getLineNumber() + ": ") + d.getMessage(null))
                .toList();
    }

    /** The member's own lines, cut out of its source file by its recorded span. */
    private static String verbatim(MethodInfo methodInfo) {
        try {
            String[] lines = Files.readString(Path.of(methodInfo.primaryType().compilationUnit().uri()))
                    .split("\n", -1);
            var source = methodInfo.source();
            StringBuilder sb = new StringBuilder();
            for (int line = source.beginLine(); line <= source.endLine(); line++) {
                if (line > source.beginLine()) sb.append('\n');
                sb.append(lines[line - 1]);
            }
            return sb.toString();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
