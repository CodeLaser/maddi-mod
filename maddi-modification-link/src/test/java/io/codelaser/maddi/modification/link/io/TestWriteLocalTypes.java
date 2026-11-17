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

package io.codelaser.maddi.modification.link.io;

import static io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl.METHOD_LINKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.ParameterInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.cst.io.CodecImpl;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.modification.prepwork.io.WriteAnalysisResults;
import io.codelaser.maddi.util.Trie;

/**
 * Format version 2: the anonymous, local and lambda types in a type's method bodies and field initializers are
 * written with their methods and parameters, and read back onto a fresh parse. A consumer that loads an analysis
 * instead of computing it (dedup's two-step engine) needs a lambda's parameters and links as much as a method's.
 */
public class TestWriteLocalTypes extends CommonTest {

    @TempDir
    private Path tempRoot;

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.function.*;
            public class X {
                private final List<String> list = new ArrayList<>();
                private final Runnable field = new Runnable() { public void run() { list.clear(); } };
                public void m(List<String> in) {
                    Consumer<String> c = s -> list.add(s);
                    class Local { int count(List<String> l) { return l.size(); } }
                    Comparator<String> cmp = new Comparator<>() {
                        public int compare(String a, String b) { return a.compareTo(b); }
                    };
                    in.forEach(c);
                    new Local().count(in);
                    in.sort(cmp);
                }
            }
            """;

    // the lambda is gone, so the local types after it move up one place: not resolvable by name and index
    @Language("java")
    private static final String INPUT_CHANGED = """
            package a.b;
            import java.util.*;
            import java.util.function.*;
            public class X {
                private final List<String> list = new ArrayList<>();
                private final Runnable field = new Runnable() { public void run() { list.clear(); } };
                public void m(List<String> in) {
                    class Local { int count(List<String> l) { return l.size(); } }
                    new Local().count(in);
                }
            }
            """;

    // per local type, per method: its verdict, its links, and each parameter's verdict
    private static List<String> snapshot(TypeInfo X) {
        List<String> out = new ArrayList<>();
        for (TypeInfo local : CodecImpl.localTypes(X)) {
            for (MethodInfo mi : local.constructorAndMethodStream().toList()) {
                // a FALSE is the default and not written: it reads back as absent
                StringBuilder sb = new StringBuilder(local.simpleName()).append('.').append(mi.name())
                        .append(" nonModifying=").append(bool(mi.analysis().getOrNull(PropertyImpl.NON_MODIFYING_METHOD,
                                ValueImpl.BoolImpl.class)))
                        .append(" links=").append(links(mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class)));
                for (ParameterInfo pi : mi.parameters()) {
                    sb.append(' ').append(pi.name()).append(":unmodified=")
                            .append(bool(pi.analysis().getOrNull(PropertyImpl.UNMODIFIED_PARAMETER, ValueImpl.BoolImpl.class)));
                }
                out.add(sb.toString());
            }
        }
        return out;
    }

    // empty links are the default, not written either
    private static String links(MethodLinkedVariablesImpl mlv) {
        return mlv == null || mlv.isDefault() ? "-" : mlv.toString();
    }

    private static boolean bool(ValueImpl.BoolImpl b) {
        return b != null && b.isTrue();
    }

    private String write(TypeInfo X, Codec codec) throws IOException {
        Trie<TypeInfo> typeTrie = new Trie<>();
        typeTrie.add(X.fullyQualifiedName().split("\\."), X);
        Path dest = Files.createTempDirectory(tempRoot, "localTypes");
        new WriteAnalysisResults(runtime).write(dest.toFile(), typeTrie, codec);
        return Files.readString(new File(dest.toFile(), "ABX.json").toPath());
    }

    @DisplayName("anonymous, local and lambda types round-trip")
    @Test
    public void roundTrip() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        prepWork(X);
        new LinkComputerImpl(javaInspector).doPrimaryType(X);

        List<TypeInfo> locals = CodecImpl.localTypes(X);
        assertEquals(4, locals.size(), locals.toString()); // the field's Runnable, the lambda, Local, the Comparator
        List<String> before = snapshot(X);
        // not vacuous: the method-body types have links and parameters claimed unmodified (the field initializer's
        // anonymous class is not analysed by doPrimaryType: no values of its own)
        assertEquals(3, before.stream().filter(s -> !s.contains("links=-")).count(), String.join("\n", before));
        assertEquals(4, before.stream().mapToInt(s -> s.split("unmodified=true", -1).length - 1).sum(),
                String.join("\n", before));

        Codec codec = new LinkCodec(javaInspector).codec();
        String written = write(X, codec);
        assertTrue(written.startsWith("[\n{\"format\": \"maddi-analysis-results\", \"version\": 2}"), written);
        // in context: simple name and place in CodecImpl.localTypes; the field initializer's anonymous class, without
        // values, is left out as any element without data is
        for (String local : List.of("A$1(0)", "ALocal(1)", "A$3(2)")) {
            assertTrue(written.contains("\"name\": \"" + local + "\""), local + " in " + written);
        }

        javaInspector.invalidateAllSources();
        TypeInfo X2 = javaInspector.parse("a.b.X", INPUT);
        LoadAnalysisResults load = new LoadAnalysisResults(javaInspector.runtime(), javaInspector.mainSources());
        assertEquals(1, load.go(new LinkCodec(javaInspector).restoreCodec(), written));
        assertEquals(0, load.skippedUnresolvableHints());
        assertEquals(String.join("\n", before), String.join("\n", snapshot(X2)));
    }

    @DisplayName("a local type that cannot be found by name and place is reported, not dropped silently")
    @Test
    public void changedSource() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        prepWork(X);
        new LinkComputerImpl(javaInspector).doPrimaryType(X);
        String written = write(X, new LinkCodec(javaInspector).codec());

        javaInspector.invalidateAllSources();
        javaInspector.parse("a.b.X", INPUT_CHANGED);
        LoadAnalysisResults load = new LoadAnalysisResults(javaInspector.runtime(), javaInspector.mainSources());
        assertEquals(1, load.go(new LinkCodec(javaInspector).restoreCodec(), written));
        // the lambda and the anonymous Comparator have no counterpart: their entries are counted as unresolvable
        assertTrue(load.skippedUnresolvableHints() >= 2, "skipped " + load.skippedUnresolvableHints());
    }
}
