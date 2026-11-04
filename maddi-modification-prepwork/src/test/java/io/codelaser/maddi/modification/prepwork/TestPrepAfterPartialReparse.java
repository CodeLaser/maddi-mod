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

package io.codelaser.maddi.modification.prepwork;

import static io.codelaser.maddi.inspection.api.integration.JavaInspector.InvalidationState.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.statement.Statement;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.prepwork.variable.VariableData;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl;

/**
 * Prep on the objects a per-file re-parse leaves: re-parsed (INVALID) and rewired (REWIRE) types, next to kept ones.
 * Every variable prep records must be built on the LIVE objects, or the prep output of one generation keeps the
 * previous one reachable (docs/design/incremental-and-overlay.md §7 item 4: on fernflower, a rewired
 * {@code StructContext.instanceOf} held {@code ClassNameConstants.JAVA_LANG_OBJECT} of an older generation).
 */
public class TestPrepAfterPartialReparse {

    @TempDir
    Path root;

    @Language("java")
    private static final String CONSTS = """
            package c;
            public interface Consts {
                String X = "x";
            }
            """;

    @Language("java")
    private static final String USES = """
            package c;
            public class Uses {
                private final Edited edited = new Edited();
                public boolean m(String s) { return Consts.X.equals(s); }
                public int other() { return edited.v(); }
            }
            """;

    @Language("java")
    private static final String EDITED = """
            package c;
            public class Edited {
                public int v() { return 1; }
            }
            """;

    @DisplayName("after each partial re-parse, prep's variables are the live fields, whichever type was edited")
    @Test
    public void test() throws IOException {
        Path src = root.resolve("src");
        write(src, "c/Consts.java", CONSTS);
        write(src, "c/Uses.java", USES);
        write(src, "c/Edited.java", EDITED);
        SourceSet set = new SourceSetImpl.Builder().setName("main").setSourceDirectories(List.of(src))
                .setUri(src.toUri()).build();
        JavaInspector inspector = inspector(set);
        ParseResult pr = inspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        new PrepAnalyzer(inspector.runtime()).doPrimaryTypes(Set.copyOf(pr.primaryTypes()));
        assertLive(pr, "the cold parse");

        // as on fernflower: the constants' own file, then a neighbour, then the constants again
        String[][] rounds = {{"c/Consts.java", "c.Consts"}, {"c/Edited.java", "c.Edited"},
                {"c/Consts.java", "c.Consts"}, {"c/Edited.java", "c.Edited"}};
        for (int round = 0; round < rounds.length; round++) {
            String file = rounds[round][0];
            String fqn = rounds[round][1];
            String content = switch (fqn) {
                case "c.Consts" -> CONSTS;
                default -> EDITED;
            };
            write(src, file, "// round " + round + "\n" + content);
            JavaInspector.ParseOptions options = new JavaInspector.ParseOptions.Builder()
                    .setDetailedSources(true).setFailFast(true)
                    .setInvalidated(ti -> ti.fullyQualifiedName().equals(fqn) ? INVALID
                            : ti.fullyQualifiedName().equals("c.Uses") ? REWIRE : UNCHANGED)
                    .build();
            ParseResult before = pr;
            pr = inspector.parse(Map.of(), options).parseResult();
            TypeInfo uses = pr.findType("c.Uses");
            assertNotSame(before.findType("c.Uses"), uses, "Uses is rewired");
            // carried, not recomputed: the rewired type arrives prepped, its methods holding VariableData
            assertTrue(uses.analysis().haveAnalyzedValueFor(PrepAnalyzer.PREPPED), "the prep marker is carried");
            assertNotNull(uses.findUniqueMethod("m", 1).analysis()
                    .getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class), "VariableData is carried");
            PrepAnalyzer prep = new PrepAnalyzer(inspector.runtime());
            prep.doPrimaryTypes(Set.copyOf(pr.primaryTypes()));
            // the whole universe passed, only the re-parsed type prepped: "these, into that" (§7 item 6)
            assertEquals(1, prep.typesPrepped(), "round " + round + ": only " + fqn);
            assertEquals(2, prep.typesCarried(), "round " + round + ": Uses rewired, the third kept");
            assertLive(pr, "round " + round + " (" + fqn + " edited)");
            assertEquals(prepDump(coldPrep(set)), prepDump(pr), "round " + round + ": warm prep as a cold one");
        }
    }

    @Language("java")
    private static final String HOLDER = """
            package g;
            public class Holder {
                private int x;
                public int getX() { return x; }
            }
            """;

    @Language("java")
    private static final String HOLDER_NOT_A_GETTER = """
            package g;
            public class Holder {
                private int x;
                public int getX() { return x + 1; }
            }
            """;

    @Language("java")
    private static final String CALLER = """
            package g;
            public class Caller {
                public int m(Holder h) { return h.getX(); }
            }
            """;

    /*
     The one cross-type input of a type's own prep: a call to a getter is recorded as a read of the field. Holder is
     edited so that getX() stops being a getter; Caller, rewired, must not keep the prep data that says otherwise.
     */
    @DisplayName("a callee that stops being a getter: its rewired caller's prep data follows")
    @Test
    public void testGetterStatusChange() throws IOException {
        Path src = root.resolve("g-src");
        write(src, "g/Holder.java", HOLDER);
        write(src, "g/Caller.java", CALLER);
        SourceSet set = new SourceSetImpl.Builder().setName("main").setSourceDirectories(List.of(src))
                .setUri(src.toUri()).build();
        JavaInspector inspector = inspector(set);
        ParseResult pr = inspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        new PrepAnalyzer(inspector.runtime()).doPrimaryTypes(Set.copyOf(pr.primaryTypes()));

        write(src, "g/Holder.java", HOLDER_NOT_A_GETTER);
        JavaInspector.ParseOptions options = new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setFailFast(true)
                .setInvalidated(ti -> ti.fullyQualifiedName().equals("g.Holder") ? INVALID
                        : ti.fullyQualifiedName().equals("g.Caller") ? REWIRE : UNCHANGED)
                .build();
        pr = inspector.parse(Map.of(), options).parseResult();
        PrepAnalyzer prep = new PrepAnalyzer(inspector.runtime());
        prep.doPrimaryTypes(Set.copyOf(pr.primaryTypes()));
        assertEquals(1, prep.typesPrepped(), "Holder");
        assertEquals(1, prep.typesCarried(), "Caller");
        assertEquals(1, prep.typesReprepped(), "Caller: getX is no longer a getter");
        assertEquals(prepDump(coldPrep(set)), prepDump(pr), "warm prep as a cold one");
    }

    private static ParseResult coldPrep(SourceSet set) {
        JavaInspector cold = inspector(set);
        ParseResult pr = cold.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        new PrepAnalyzer(cold.runtime()).doPrimaryTypes(Set.copyOf(pr.primaryTypes()));
        return pr;
    }

    /* Every method's and every statement's VariableData, described (VariableDataImpl.toString), by name and index. */
    private static Map<String, String> prepDump(ParseResult pr) {
        Map<String, String> map = new TreeMap<>();
        for (TypeInfo type : pr.primaryTypes()) {
            for (MethodInfo mi : type.constructorsAndMethods()) {
                map.put(mi.fullyQualifiedName(), String.valueOf(mi.analysis()
                        .getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class)));
                mi.methodBody().visit(e -> {
                    if (e instanceof Statement st) {
                        map.put(mi.fullyQualifiedName() + "@" + st.source().index(), String.valueOf(st.analysis()
                                .getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class)));
                    }
                    return true;
                });
            }
        }
        return map;
    }

    /* Every field reference in every method's VariableData belongs to the type the parse result holds. */
    private static void assertLive(ParseResult pr, String when) {
        List<String> stale = new ArrayList<>();
        for (TypeInfo type : pr.primaryTypes()) {
            for (MethodInfo mi : type.constructorsAndMethods()) {
                VariableData vd = mi.analysis().getOrNull(VariableDataImpl.VARIABLE_DATA, VariableDataImpl.class);
                if (vd == null) continue;
                vd.variableInfoContainerStream().forEach(vic -> {
                    if (vic.variable() instanceof FieldReference fr) {
                        TypeInfo owner = fr.fieldInfo().owner().primaryType();
                        if (pr.findType(owner.fullyQualifiedName()) != owner) {
                            stale.add(mi.fullyQualifiedName() + ": " + fr);
                        }
                    }
                });
            }
        }
        assertTrue(stale.isEmpty(), when + ": prep recorded fields of a replaced type: " + stale);
    }

    private static JavaInspector inspector(SourceSet set) {
        try {
            JavaInspector inspector = new JavaInspectorImpl(true, false);
            InputConfiguration inputConfiguration = new InputConfigurationImpl.Builder()
                    .addSourceSets(set)
                    .addClassPath(InputConfigurationImpl.DEFAULT_MODULES)
                    .build();
            inspector.initialize(inputConfiguration);
            return inspector;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void write(Path sourceRoot, String relative, String content) throws IOException {
        Path file = sourceRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
