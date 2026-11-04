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

package io.codelaser.maddi.run.openjdkmain;

import static io.codelaser.maddi.inspection.api.integration.JavaInspector.InvalidationState.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.codelaser.maddi.callgraph.ComputeAnalysisOrder;
import io.codelaser.maddi.callgraph.ComputeCallGraph;
import io.codelaser.maddi.callgraph.PrimaryTypeUseGraph;
import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.InfoMapView;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.variable.FieldReference;
import io.codelaser.maddi.cst.api.variable.Variable;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.parser.ParseResult;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.io.AnalysisFingerprint;
import io.codelaser.maddi.modification.prepwork.variable.Links;

/**
 * A rewired subclass whose linked variables name an inherited private field: the field is declared in {@code Base}
 * (main), the subclass {@code Sub} (another source set) reaches it only through {@code Base}'s methods, and its
 * {@code METHOD_LINKS} carry it. Carrying {@code Sub}'s analysis onto its rewired copy must re-point the field, not
 * fail on it (zuul: BaseServerStartup.addrsToChannelInitializers, through ServerStartup). The same for the link
 * engine's virtual fields of a type that declares none, {@code Bag}'s hidden content as an {@code ArrayList<String>}
 * (zuul: PushRegistrationHandler.§$, a netty handler).
 */
public class TestRewireInheritedPrivateField {

    @Language("java")
    private static final String BASE = """
            package a.b;
            import java.util.HashMap;
            import java.util.Map;
            public class Base {
                private Map<String, String> map = new HashMap<>();
                protected void put(String k, String v) { map.put(k, v); }
                protected Map<String, String> map() { return map; }
            }
            """;
    @Language("java")
    private static final String SUB = """
            package c.d;
            import a.b.Base;
            import java.util.Map;
            public class Sub extends Base {
                public void add(String k) { put(k, k); }
                public Map<String, String> get() { return map(); }
            }
            """;

    // extends a library container: its own hidden content is a virtual field of Bag (§$s), which Bag does not declare
    @Language("java")
    private static final String BAG = """
            package c.d;
            import a.b.Base;
            import java.util.ArrayList;
            public class Bag extends ArrayList<String> {
                public String first() { return get(0); }
                public void addName(Base base) { add(base.toString()); }
                public Bag self() { return this; }
            }
            """;

    @TempDir
    Path root;

    private InputConfiguration inputConfiguration() {
        SourceSet main = new SourceSetImpl.Builder().setName("main")
                .setSourceDirectories(List.of(root.resolve("main-src")))
                .setUri(root.resolve("main-classes").toUri()).build();
        SourceSet dependent = new SourceSetImpl.Builder().setName("dependent")
                .setSourceDirectories(List.of(root.resolve("dep-src")))
                .setUri(root.resolve("dep-src").toUri())
                .setDependencies(List.of(main)).build();
        return new InputConfigurationImpl.Builder()
                .addSourceSets(main, dependent)
                .addClassPath(InputConfigurationImpl.DEFAULT_MODULES).build();
    }

    private static void compile(List<Path> files, Path outputDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir.toFile()));
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            assertTrue(compiler.getTask(null, fm, null, List.of(), null, units).call(), "could not compile main");
        }
    }

    private static ComputeCallGraph analyze(JavaInspector javaInspector, ParseResult pr) {
        PrepAnalyzer prep = new PrepAnalyzer(javaInspector.runtime(),
                new PrepAnalyzer.Options.Builder().setFaultTolerant(true).build());
        ComputeCallGraph ccg = prep.doPrimaryTypesReturnComputeCallGraph(Set.copyOf(pr.primaryTypes()),
                pr.sourceSetToModuleInfoMap().values(), t -> false, false);
        List<Info> order = new ComputeAnalysisOrder().go(ccg.graph(), false);
        new IteratingAnalyzerImpl(javaInspector,
                new IteratingAnalyzerImpl.ConfigurationBuilder().setMaxIterations(20)
                        .setStopWhenCycleDetectedAndNoImprovements(true).build()).analyze(order, ccg.graph());
        return ccg;
    }

    /* every field the carried METHOD_LINKS of a type's methods name */
    private static List<FieldInfo> fieldsIn(TypeInfo type) {
        return type.constructorAndMethodStream()
                .map(m -> m.analysis().getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS,
                        MethodLinkedVariablesImpl.class))
                .filter(Objects::nonNull)
                .flatMap(mlv -> Stream.concat(Stream.of(mlv.ofReturnValue()), mlv.ofParameters().stream()))
                .flatMap(Links::stream)
                .flatMap(l -> Stream.of(l.from(), l.to()))
                .flatMap(Variable::variableStreamDescendIntoScope)
                .filter(v -> v instanceof FieldReference)
                .map(v -> ((FieldReference) v).fieldInfo())
                .toList();
    }

    @DisplayName("an inherited private field, and a virtual field, in a rewired type's linked variables are re-pointed")
    @Test
    public void test() throws IOException {
        Path mainSrc = Files.createDirectories(root.resolve("main-src/a/b"));
        Path baseFile = Files.writeString(mainSrc.resolve("Base.java"), BASE);
        Path depSrc = Files.createDirectories(root.resolve("dep-src/c/d"));
        Files.writeString(depSrc.resolve("Sub.java"), SUB);
        Files.writeString(depSrc.resolve("Bag.java"), BAG);
        compile(List.of(baseFile), Files.createDirectories(root.resolve("main-classes")));

        JavaInspector javaInspector = new JavaInspectorImpl(true, false);
        javaInspector.initialize(inputConfiguration());
        ParseResult pr0 = javaInspector.parse(Map.of(), JavaInspectorImpl.DETAILED_SOURCES).parseResult();
        ComputeCallGraph ccg0 = analyze(javaInspector, pr0);
        TypeInfo oldSub = pr0.findType("c.d.Sub");
        Map<String, String> linksBefore = new TreeMap<>();
        oldSub.constructorAndMethodStream().forEach(m -> linksBefore.put(m.name(), String.valueOf(m.analysis()
                .getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS, MethodLinkedVariablesImpl.class))));
        // the shape: get() returns Base's private field, which its links name as a field of Sub
        assertEquals("[] --> get←this.map", linksBefore.get("get"));

        Files.writeString(baseFile, "// a comment\n\n" + BASE);
        JavaInspector.ReloadResult rr = javaInspector.reloadSources(inputConfiguration(), Map.of());
        Set<TypeInfo> changed = rr.sourceHasChanged();
        Set<TypeInfo> dependents = new PrimaryTypeUseGraph(ccg0.graph()).dependentsOf(changed);
        JavaInspector.Invalidated inv = ti ->
                changed.contains(ti) ? INVALID : dependents.contains(ti) ? REWIRE : UNCHANGED;
        ParseResult pr1 = javaInspector.parse(new JavaInspector.ParseOptions.Builder()
                .setDetailedSources(true).setInvalidated(inv).build()).parseResult();
        TypeInfo newSub = pr1.findType("c.d.Sub");
        assertNotSame(oldSub, newSub, "Sub was rewired");

        InfoMapView view = javaInspector.lastRewireInfoMap();
        oldSub.constructorAndMethodStream().forEach(oldMethod -> view.methodInfo(oldMethod).analysis()
                .setAll(oldMethod.analysis().rewire(view, AnalysisFingerprint.CROSS_TYPE_DERIVED_ONLY)));

        TypeInfo oldBag = pr0.findType("c.d.Bag");
        TypeInfo newBag = pr1.findType("c.d.Bag");
        assertNotSame(oldBag, newBag, "Bag was rewired");
        Map<String, String> bagBefore = new TreeMap<>();
        oldBag.constructorAndMethodStream().forEach(m -> bagBefore.put(m.name(), String.valueOf(m.analysis()
                .getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS, MethodLinkedVariablesImpl.class))));
        oldBag.constructorAndMethodStream().forEach(oldMethod -> view.methodInfo(oldMethod).analysis()
                .setAll(oldMethod.analysis().rewire(view, AnalysisFingerprint.CROSS_TYPE_DERIVED_ONLY)));
        Map<String, String> bagAfter = new TreeMap<>();
        newBag.constructorAndMethodStream().forEach(m -> bagAfter.put(m.name(), String.valueOf(m.analysis()
                .getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS, MethodLinkedVariablesImpl.class))));
        assertEquals(bagBefore, bagAfter, "Bag's links are carried as they were");
        List<FieldInfo> virtual = fieldsIn(newBag).stream().filter(f -> f.name().startsWith("§")).toList();
        assertTrue(virtual.stream().anyMatch(f -> f.owner() == newBag),
                "a virtual field of Bag, owned by the rewired Bag: " + virtual);
        virtual.forEach(f -> assertNotSame(oldBag, f.owner(), () -> f + " is owned by the old Bag"));

        Map<String, String> linksAfter = new TreeMap<>();
        newSub.constructorAndMethodStream().forEach(m -> linksAfter.put(m.name(), String.valueOf(m.analysis()
                .getOrNull(MethodLinkedVariablesImpl.METHOD_LINKS, MethodLinkedVariablesImpl.class))));
        assertEquals(linksBefore, linksAfter, "the links are carried as they were");
        // and every field they name belongs to the new generation: Base.map itself, or Base.map seen through Sub
        TypeInfo newBase = pr1.findType("a.b.Base");
        List<FieldInfo> fields = fieldsIn(newSub).stream().filter(f -> "map".equals(f.name())).toList();
        assertFalse(fields.isEmpty());
                assertTrue(fields.stream().anyMatch(f -> f.owner() == newSub), "Base.map seen through Sub: " + fields);
        fields.forEach(f -> assertTrue(f.owner() == newSub || f.owner() == newBase,
                () -> f + " is owned by an old generation's " + f.owner()));
        }
}
