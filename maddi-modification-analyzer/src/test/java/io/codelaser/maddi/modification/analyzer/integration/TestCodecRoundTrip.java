/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2026, CodeLaser BV, https://github.com/CodeLaser/maddi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.codelaser.maddi.modification.analyzer.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.IteratingAnalyzer;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.io.LinkCodec;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.modification.prepwork.io.WriteAnalysisResults;
import io.codelaser.maddi.util.Trie;

/**
 * Everything an analysis leaves on a type's elements is written by the link codec and read back by the restore codec.
 * The shapes are the ones the codec audit (CODECAUDIT, maddi-run-analysis) found it could not write or read on
 * fernflower and guava (2026-11-12): a link through an object creation, a method call, a char, long or double
 * constant, a binary operator, an inline conditional, an assignment, a class literal or an array's length; an anonymous, local or lambda type named in a link or an implementation;
 * a modified array element beside the deep-only list; a type parameter of a nested type; a virtual container type
 * (Map's §KV), whose fields it owns and whose definition must not depend on the order of the file.
 */
public class TestCodecRoundTrip extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;
            import java.util.function.Supplier;
            class X {
                private final Object[] arr = new Object[10];
                private final List<String> list = new ArrayList<>();
                private final Supplier<String> supplier = new Supplier<>() {
                    @Override
                    public String get() { return list.isEmpty() ? "" : list.get(0); }
                };
                private final Supplier<List<String>> lambda = () -> list;
                private final char c = 'x';
                private final long l = 3L;
                private final double d = 1.5;

                void set(int i, Object o) { arr[i] = o; }
                Object pick(int i, boolean b) { return b ? arr[i] : arr[i + 1]; }
                Object at(int i) { return arr[i * 2 + (int) l]; }
                List<String> copy() { List<String> r = new ArrayList<>(list); return r; }
                String first() { return list.get(0).toString(); }
                Runnable task() {
                    class Local implements Runnable {
                        @Override
                        public void run() { list.add("x"); }
                    }
                    return new Local();
                }
                static class Inner<T> {
                    T t;
                    Inner(T t) { this.t = t; }
                    T get() { return t; }
                    <U> U convert(U u) { return u; }
                }
                Inner<String> inner() { return new Inner<>(list.get(0)); }
                Object atChar() { return arr['a' - 97]; }
                Object atLong() { return arr[(int) 3L]; }
                Object atDouble() { return arr[(int) 1.5]; }
                Object atEither(boolean b) { return arr[b ? 0 : 1]; }
                Object atSize() { return arr[list.size()]; }
                X self() { return this; }
                Object viaCall() { return self().arr; }
                void touch(List<String>[] ls, int i) { ls[i].add("x"); }
                Object last() { return arr[arr.length - 1]; }
                Object assigned(int i) { return arr[i = i + 1]; }
                Object byClass() { return arr[String.class.getName().length()]; }
                static <K, V> Map<K, V> copyMap(Map<K, V> m) { return new HashMap<>(m); }
                static <K, V> V first(Map<K, V> m, K k) { return m.get(k); }
                static <T, R> R apply(Function<T, R> f, T t) { return f.apply(t); }
            }
            """;

    @DisplayName("the link codec writes every value of an analysis, and the restore codec reads it back")
    @Test
    public void test(@TempDir Path dir) throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> analysisOrder = prepWork(X);
        IteratingAnalyzer iterating = new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder()
                        .setMaxIterations(20)
                        .setModificationViaReachability(true) // object creations in the links
                        .build());
        iterating.analyze(analysisOrder);

        List<String> notWritten = new ArrayList<>();
        Trie<TypeInfo> trie = new Trie<>();
        trie.add(X.fullyQualifiedName().split("\\."), X);
        WriteAnalysisResults writer = new WriteAnalysisResults(runtime)
                .setEncodeFailureListener((key, e) -> notWritten.add(key + ": " + e));
        writer.write(dir.toFile(), trie, new LinkCodec(javaInspector).codec());
        assertEquals(List.of(), notWritten);
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(f -> f.toString().endsWith(".json")));
        }
        String written;
        try (var files = Files.list(dir)) {
            written = Files.readString(files.filter(f -> f.toString().endsWith(".json")).findFirst().orElseThrow());
        }
        // not vacuous: the shapes the codec could not write before are in the file
        for (String shape : List.of("[\"O\"", "\"A", "binaryOperator", "methodCall", "charConstant", "longConstant",
                "doubleConstant", "inlineConditional", "arrayLength", "assignment", "classLiteral", "\"U")) {
            assertTrue(written.contains(shape), () -> "expected " + shape + " in the written results");
        }

        // goDir, not goDirTolerant: a file that does not read is a failure here
        int loaded = new LoadAnalysisResults(runtime, javaInspector.mainSources())
                .goDir(new LinkCodec(javaInspector).restoreCodec(), dir.toFile());
        assertEquals(1, loaded);
    }
}
