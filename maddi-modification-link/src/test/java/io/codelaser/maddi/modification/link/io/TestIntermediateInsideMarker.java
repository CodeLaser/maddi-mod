package io.codelaser.maddi.modification.link.io;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.modification.prepwork.io.WriteAnalysisResults;
import io.codelaser.maddi.util.Trie;

/**
 * A constructor reference's functional interface ('ArrayList::new') returns a synthetic intermediate '$__rv', and the
 * marker's definition carries it. The writer refused every summary holding such a marker ("engine-internal variable
 * leaked into persistence: $__rv3", 399 methodLinks on hibernate-core, Hibernate.bag() and friends), so a restored run
 * lacked what the cold one had. The codec writes an intermediate by name and type.
 */
public class TestIntermediateInsideMarker extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.function.*;
            class X {
                interface TR { }
                interface TJ { TR joined(); }
                interface TG { TR primary(); List<TJ> refJoins(); List<TG> joins(); }
                static <T> T q(TG g, Function<TR, T> action) {
                    T r = action.apply(g.primary());
                    if (r != null) return r;
                    for (TJ j : g.refJoins()) {
                        T n = action.apply(j.joined());
                        if (n != null) return n;
                    }
                    for (TG k : g.joins()) {
                        T m = q(k, action);
                        if (m != null) return m;
                    }
                    return null;
                }
                public static final class CI<C> {
                    private final Supplier<C> detached;
                    private final Supplier<C> created;
                    private CI(Supplier<C> detached, Supplier<C> created) { this.detached = detached; this.created = created; }
                    public C createDetachedInstance() { return detached.get(); }
                    public C createNewInstance() { return created.get(); }
                }
                public static <U> CI<Collection<U>> bag() { return new CI<>(ArrayList::new, ArrayList::new); }
                public static <U> CI<List<U>> list() { return new CI<>(LinkedList::new, ArrayList::new); }
                public static <U> CI<Set<U>> set() { return new CI<>(TreeSet::new, HashSet::new); }
                @SuppressWarnings("unchecked")
                public static <C> CI<C> collection(Class<C> collectionClass) {
                    if (collectionClass == List.class) return (CI<C>) list();
                    else if (collectionClass == Set.class) return (CI<C>) set();
                    else if (collectionClass == Collection.class) return (CI<C>) bag();
                    else throw new IllegalArgumentException("illegal");
                }
                static <T> T q2(TG g, Function<TR, T> action) {
                    return action.apply(g.primary());
                }
            }
            """;

    @TempDir
    private Path tempRoot;

    private static final List<String> METHODS = List.of("bag", "list", "set", "collection");

    private static List<String> links(TypeInfo X) {
        return METHODS.stream().map(n -> n + " " + X.findUniqueMethod(n, n.equals("collection") ? 1 : 0).analysis()
                .getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class)).toList();
    }

    @Test
    public void test() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        prepWork(X);
        new LinkComputerImpl(javaInspector).doPrimaryType(X);
        List<String> cold = links(X);
        assertTrue(cold.getFirst().contains("$_fi"), "not vacuous: bag() carries the constructor references' markers");

        Trie<TypeInfo> typeTrie = new Trie<>();
        typeTrie.add(X.fullyQualifiedName().split("\\."), X);
        Path dest = Files.createTempDirectory(tempRoot, "intermediate");
        new WriteAnalysisResults(runtime).write(dest.toFile(), typeTrie, new LinkCodec(javaInspector).codec());
        String written = Files.readString(new File(dest.toFile(), "ABX.json").toPath());
        assertTrue(written.contains("\"I\""), "the intermediate is written: " + written);

        javaInspector.invalidateAllSources();
        TypeInfo X1 = javaInspector.parse("a.b.X", INPUT);
        LoadAnalysisResults load = new LoadAnalysisResults(javaInspector.runtime(), javaInspector.mainSources());
        assertEquals(1, load.go(new LinkCodec(javaInspector).restoreCodec(), written));
        // a stored marker carries one 'M'
        assertEquals(cold.stream().map(l -> l.replaceAll("(\\$_[a-z]+\\d+)", "$1M")).toList(), links(X1));
    }
}
