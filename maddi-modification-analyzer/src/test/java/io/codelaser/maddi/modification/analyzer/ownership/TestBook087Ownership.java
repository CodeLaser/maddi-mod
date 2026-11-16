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

package io.codelaser.maddi.modification.analyzer.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;

/**
 * The examples of road-to-immutability §087, "Ownership": the book states these verdicts.
 */
public class TestBook087Ownership extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            public class X {
                static final List<Object> registry = new ArrayList<>();

                public static class Lines {
                    private final List<String> lines = new ArrayList<>();

                    List<String> copy() { return new ArrayList<>(lines); }
                    List<String> sorted() {
                        List<String> result = copy();
                        Collections.sort(result);
                        return result;
                    }
                    List<String> orEmpty(List<String> list) { return list == null ? new ArrayList<>() : list; }
                    List<String> registered() {
                        List<String> result = new ArrayList<>();
                        registry.add(result);
                        return result;
                    }

                    int countEmpty(List<String> list) {
                        int n = 0;
                        for (String s : list) if (s.isEmpty()) n++;
                        return n;
                    }
                    void addAll(Collection<String> c) { lines.addAll(c); }
                    void add(String line) { lines.add(line); }
                    Runnable printer(List<String> list) { return () -> list.forEach(System.out::println); }
                }

                public static class Document {
                    private final List<String> lines = new ArrayList<>();
                    private StringBuilder buffer;
                    private final List<String> tags;
                    private final Map<String, String> index = new HashMap<>();

                    public Document(List<String> tags) { this.tags = tags; }
                    public void append(String s) {
                        if (buffer == null) buffer = new StringBuilder();
                        buffer.append(s);
                        lines.add(s);
                    }
                    public Map<String, String> index() { return Collections.unmodifiableMap(index); }
                    public int size() { return lines.size() + tags.size() + buffer.length(); }
                }

                public record Token(String text) { }

                public static class Parser {
                    private final List<Token> tokens;
                    private Parser(List<Token> tokens) { this.tokens = tokens; }
                    public static Parser of(String source) {
                        List<Token> tokens = new ArrayList<>();
                        tokens.add(new Token(source));
                        return new Parser(tokens);
                    }
                    public int size() { return tokens.size(); }
                }
            }
            """;

    @DisplayName("§087's examples")
    @Test
    public void test() throws IOException {
        AnalyzerBundle bundle = buildAnalyzerBundle();
        TypeInfo x = bundle.javaInspector().parse("a.b.X", INPUT);
        List<Info> analysisOrder = bundle.prepAnalyzer().doPrimaryType(x);
        new IteratingAnalyzerImpl(bundle.javaInspector(), new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setModificationViaReachability(true).setOwnership(true).build())
                .analyze(analysisOrder);
        new OwnershipPass().go(analysisOrder);
        Map<String, String> actual = new TreeMap<>();
        x.recursiveSubTypeStream().forEach(t -> {
            t.fields().forEach(fi -> actual.put(t.simpleName() + "." + fi.name(),
                    fi.analysis().getOrDefault(PropertyImpl.OWNED_FIELD, ValueImpl.BoolImpl.FALSE).isTrue()
                            ? "owned" : "-"));
            t.methods().forEach(mi -> {
                actual.put(t.simpleName() + "." + mi.name() + "()",
                        mi.analysis().getOrDefault(PropertyImpl.FRESH_METHOD, ValueImpl.BoolImpl.FALSE).isTrue()
                                ? "fresh" : "-");
                mi.parameters().forEach(pi -> actual.put(t.simpleName() + "." + mi.name() + "(" + pi.name() + ")",
                        pi.analysis().getOrDefault(PropertyImpl.BORROWED_PARAMETER, ValueImpl.BoolImpl.FALSE).isTrue()
                                ? "borrowed" : "-"));
            });
        });
        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
                Map.entry("Lines.copy()", "fresh"),
                Map.entry("Lines.sorted()", "fresh"),
                Map.entry("Lines.orEmpty()", "-"),
                Map.entry("Lines.registered()", "-"),
                Map.entry("Lines.countEmpty(list)", "borrowed"),
                Map.entry("Lines.addAll(c)", "borrowed"),
                Map.entry("Lines.add(line)", "-"),
                Map.entry("Lines.printer(list)", "-"),
                Map.entry("Lines.lines", "owned"),
                Map.entry("Document.lines", "owned"),
                Map.entry("Document.buffer", "owned"),
                Map.entry("Document.tags", "-"),
                Map.entry("Document.index", "-"),
                Map.entry("Parser.tokens", "owned")));
        actual.keySet().retainAll(expected.keySet());
        assertEquals(expected, actual);
    }
}
