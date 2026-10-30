package io.codelaser.maddi.modification.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.common.util.IsolateClass;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.codelaser.maddi.modification.common.CommonTest.javaInspectorFactory;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CodeLaser/maddi#180, found isolating fernflower's {@code DomHelper} with the 33 types it reaches: four shapes a
 * group isolate did not compile. An isolated interface came out as a class; an annotation stub lost its
 * {@code @Target}, so a type-use annotation in the verbatim text was "not applicable in this type context"; a stub
 * below a parent without a no-argument constructor called an implicit {@code super()} that does not exist; and a stub
 * below an isolated abstract class did not implement its abstract methods. The gate is {@code TestIsolateClass5Group}'s.
 */
public class TestIsolateClass8Natures {
    protected JavaInspector javaInspector;
    protected IsolateClass isolateClass;

    @BeforeAll
    public static void beforeAll() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    }

    @BeforeEach
    public void beforeEach() throws IOException {
        javaInspector = javaInspectorFactory().withSources(SourceSetImpl.testProtocolSourceSet());
        isolateClass = new IsolateClass(javaInspector);
    }

    @Language("java")
    private static final String MATCHABLE = """
            package m;
            public interface Matchable {
                int LIMIT = 3;
                Object find(int index);
                default boolean matches(int index) { return find(index) != null; }
            }
            """;

    @Language("java")
    private static final String NODE = """
            package m;
            public class Node implements Matchable {
                @Override
                public Object find(int index) { return index < LIMIT ? this : null; }
            }
            """;

    @Language("java")
    private static final String NULLABLE = """
            package n;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Retention(RetentionPolicy.CLASS)
            @Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
            public @interface Nullable {
            }
            """;

    @Language("java")
    private static final String TYPE_USE = """
            package m;
            import n.Nullable;
            import java.util.List;
            public class TypeUse {
                public @Nullable String[] names(List<@Nullable String> in) { return in.toArray(new String[0]); }
            }
            """;

    @Language("java")
    private static final String FULL_STREAM = """
            package s;
            import java.io.ByteArrayInputStream;
            import java.io.DataInputStream;
            public class FullStream extends DataInputStream {
                public FullStream(byte[] bytes) { super(new ByteArrayInputStream(bytes)); }
                public void discard(int n) { }
            }
            """;

    @Language("java")
    private static final String READER = """
            package m;
            import s.FullStream;
            public class Reader {
                public void skip(byte[] bytes) { new FullStream(bytes).discard(2); }
            }
            """;

    @Language("java")
    private static final String MEMBER = """
            package t;
            public abstract class Member {
                protected final int flags;
                protected Member(int flags) { this.flags = flags; }
                protected abstract String kind();
                public String describe() { return kind() + flags; }
            }
            """;

    @Language("java")
    private static final String CLAZZ = """
            package t;
            public class Clazz extends Member {
                public Clazz() { super(1); }
                @Override protected String kind() { return "class"; }
                public String name() { return "C"; }
            }
            """;

    @Language("java")
    private static final String HOLDER = """
            package t;
            public class Holder {
                private Clazz clazz;
                public String name() { return clazz.name() + clazz.describe(); }
            }
            """;

    private static final Map<String, String> SOURCES = Map.of("m.Matchable", MATCHABLE, "m.Node", NODE,
            "n.Nullable", NULLABLE, "m.TypeUse", TYPE_USE, "s.FullStream", FULL_STREAM, "m.Reader", READER,
            "t.Member", MEMBER, "t.Clazz", CLAZZ, "t.Holder", HOLDER);

    private Map<String, String> isolate(String... fqns) {
        var parsed = javaInspector.parse(SOURCES,
                new JavaInspector.ParseOptions.Builder().setDetailedSources(true).setFailFast(true).build());
        List<TypeInfo> types = java.util.Arrays.stream(fqns).map(parsed.parseResult()::findType).toList();
        IsolateClass.Result r = isolateClass.isolate(types);
        Map<MethodInfo, String> memberSources = new LinkedHashMap<>();
        for (MethodInfo original : r.markers().values()) {
            memberSources.put(original, verbatim(SOURCES.get(original.primaryType().fullyQualifiedName()), original));
        }
        Map<String, String> tree = isolateClass.print(r, memberSources);
        tree.forEach((path, source) -> System.out.println("===== " + path + "\n" + source));
        return tree;
    }

    private void assertCompiles(Map<String, String> tree) {
        Map<String, String> byFqn = new LinkedHashMap<>();
        tree.forEach((path, source) ->
                byFqn.put(path.substring(0, path.length() - ".java".length()).replace('/', '.'), source));
        javaInspector.invalidateAllSources();
        var summary = javaInspector.parse(byFqn, new JavaInspector.ParseOptions.Builder()
                .setFailFast(true).build());
        assertEquals(byFqn.keySet().size(), summary.types().size(),
                "expected " + byFqn.keySet() + " but got " + summary.types());
    }

    private static String verbatim(String unitSource, MethodInfo methodInfo) {
        String[] lines = unitSource.split("\n", -1);
        var source = methodInfo.source();
        StringBuilder sb = new StringBuilder();
        for (int line = source.beginLine(); line <= source.endLine(); line++) {
            if (line > source.beginLine()) sb.append('\n');
            sb.append(lines[line - 1]);
        }
        return sb.toString();
    }

    @DisplayName("an isolated interface stays an interface, with its fields initialized")
    @Test
    public void isolatedInterface() {
        Map<String, String> tree = isolate("m.Node", "m.Matchable");
        String matchable = tree.get("m/Matchable.java");
        assertTrue(matchable.contains("public interface Matchable"), matchable);
        assertFalse(matchable.contains("abstract class"), matchable);
        assertTrue(tree.get("m/Node.java").contains("class Node implements Matchable"), tree.get("m/Node.java"));
        assertCompiles(tree);
    }

    @DisplayName("an annotation stub keeps its @Target, so it applies in a type-use position")
    @Test
    public void annotationStubKeepsTarget() {
        Map<String, String> tree = isolate("m.TypeUse");
        String nullable = tree.get("n/Nullable.java");
        assertTrue(nullable.contains("@Target"), nullable);
        assertTrue(nullable.contains("TYPE_USE"), nullable);
        assertTrue(nullable.contains("@Retention"), nullable);
        assertCompiles(tree);
    }

    @DisplayName("an isolated annotation type stays an annotation type, with its @Target")
    @Test
    public void isolatedAnnotationType() {
        Map<String, String> tree = isolate("m.TypeUse", "n.Nullable");
        String nullable = tree.get("n/Nullable.java");
        assertTrue(nullable.contains("@interface Nullable"), nullable);
        assertTrue(nullable.contains("TYPE_USE"), nullable);
        assertCompiles(tree);
    }

    @DisplayName("a stub below a JDK parent without a no-argument constructor passes it what it needs")
    @Test
    public void stubBelowJdkParent() {
        Map<String, String> tree = isolate("m.Reader");
        String stream = tree.get("s/FullStream.java");
        assertTrue(stream.contains("super((InputStream) null)"), stream);
        assertCompiles(tree);
    }

    @DisplayName("a stub below an isolated abstract class implements its abstract methods and calls its constructor")
    @Test
    public void stubBelowIsolatedAbstractParent() {
        Map<String, String> tree = isolate("t.Holder", "t.Member");
        String clazz = tree.get("t/Clazz.java");
        assertTrue(clazz.contains("class Clazz extends Member"), clazz);
        assertTrue(clazz.contains("String kind()"), clazz);
        assertTrue(clazz.contains("super(0)"), clazz);
        assertCompiles(tree);
    }
}
