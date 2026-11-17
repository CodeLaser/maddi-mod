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

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.link.CommonTest;
import io.codelaser.maddi.modification.link.impl.LinkComputerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.io.LoadAnalysisResults;
import io.codelaser.maddi.modification.prepwork.io.WriteAnalysisResults;
import io.codelaser.maddi.util.Trie;

/**
 * Two LinkCodec round-trip losses found by restoring a whole project's analysis (nacos, 2026-11-17):
 * <ul>
 *     <li>{@code super} came back as {@code this}: the codec wrote a This variable as its type only, without its
 *     {@code writeSuper} flag ({@code addRepeatedField←super*.§buildert}).</li>
 *     <li>a marker variable's name grew by one {@code M} per round trip ({@code $_ce0M}, {@code $_ce0MM}): the
 *     encoder appended the suffix that keeps stored markers apart from generated ones, also to a marker that was read
 *     back and already carried it.</li>
 * </ul>
 */
public class TestCodecSuperAndMarkers extends CommonTest {

    @TempDir
    private Path tempRoot;

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.*;
            import java.util.function.*;
            public class X {
                public static class Base {
                    protected final List<String> items = new ArrayList<>();
                    public Base add(String s) { items.add(s); return this; }
                }
                public static class Sub extends Base {
                    private boolean shutdown;
                    private Object body;
                    @Override public Sub add(String s) { super.add(s); return this; }
                    public Base viaSuper(String s) { return super.add(s); }
                    public void stop() { this.shutdown = true; }
                    public void clearBody() { this.body = "empty"; }
                    public void each(Consumer<String> c) { items.forEach(c); }
                    public Runnable clearer() { return () -> items.clear(); }
                    public Object maybe(boolean b) { return b ? body : null; }
                    public Object swap(Object o) { Object old = body; body = o == null ? null : o; return old; }
                }
            }
            """;

    // every method's links (empty ones are the default: not written, absent after a load), and every link-valued
    // property of the fields ('this.shutdown←$_ce0')
    private static List<String> links(TypeInfo X) {
        List<String> out = new ArrayList<>();
        X.recursiveSubTypeStream().forEach(t -> {
            t.constructorAndMethodStream().forEach(mi -> {
                MethodLinkedVariablesImpl mlv = mi.analysis().getOrNull(METHOD_LINKS, MethodLinkedVariablesImpl.class);
                out.add(t.simpleName() + "." + mi.name() + " " + (mlv == null || mlv.isDefault() ? "-" : mlv));
            });
            t.fields().forEach(fi -> fi.analysis().propertyValueStream()
                    .filter(pv -> pv.property().key().toLowerCase().contains("link") && !pv.value().isDefault())
                    .forEach(pv -> out.add(t.simpleName() + "." + fi.name() + " " + pv.property().key() + " "
                                           + pv.value())));
        });
        return out;
    }

    private String write(TypeInfo X) throws IOException {
        Trie<TypeInfo> typeTrie = new Trie<>();
        typeTrie.add(X.fullyQualifiedName().split("\\."), X);
        Path dest = Files.createTempDirectory(tempRoot, "superMarkers");
        new WriteAnalysisResults(runtime).write(dest.toFile(), typeTrie, new LinkCodec(javaInspector).codec());
        return Files.readString(new File(dest.toFile(), "ABX.json").toPath());
    }

    private TypeInfo load(String written) {
        javaInspector.invalidateAllSources();
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        LoadAnalysisResults load = new LoadAnalysisResults(javaInspector.runtime(), javaInspector.mainSources());
        assertEquals(1, load.go(new LinkCodec(javaInspector).restoreCodec(), written));
        assertEquals(0, load.skippedUnresolvableHints());
        return X;
    }

    @DisplayName("super and marker variables survive a write and a restore, twice")
    @Test
    public void roundTrip() throws IOException {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        prepWork(X);
        new LinkComputerImpl(javaInspector).doPrimaryType(X);
        List<String> cold = links(X);
        String joined = String.join("\n", cold);
        // not vacuous: the shapes are there
        assertTrue(joined.contains("super"), joined);
        assertTrue(joined.contains("$_fi"), joined);
        assertTrue(joined.contains("$_ce"), joined);

        String written1 = write(X);
        TypeInfo X1 = load(written1);
        List<String> once = links(X1);
        // a stored marker carries one 'M' (kept apart from the markers a run generates); nothing else changes
        assertEquals(joined.replaceAll("(\\$_[a-z]+\\d+)(?!M)", "$1M"), String.join("\n", once));

        // and a second round trip changes nothing more
        String written2 = write(X1);
        assertEquals(written1, written2);
        TypeInfo X2 = load(written2);
        assertEquals(String.join("\n", once), String.join("\n", links(X2)));
    }
}
