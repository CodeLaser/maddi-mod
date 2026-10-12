package io.codelaser.maddi.modification.prepwork.io;

import io.codelaser.maddi.cst.api.analysis.Codec;
import io.codelaser.maddi.cst.api.element.CompilationUnit;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.runtime.Runtime;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.api.resource.InputConfiguration;
import io.codelaser.maddi.inspection.resource.InputConfigurationImpl;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import io.codelaser.maddi.cst.api.element.SourceSet;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Analysis hints (AAPI) must be loaded only for the modules present on the classpath. With java.desktop and
 * java.net.http absent from the classpath, loading the JDK archive must not throw (it used to throw
 * UnsupportedOperationException "Cannot find javax.swing.text.JTextComponent"), must skip the swing/awt/http hints,
 * and must not have loaded those types — while java.util hints still load normally.
 */
public class TestModuleGatedHints {

    private JavaInspector inspectorWithoutDesktop() throws Exception {
        SourceSet javaBase = SourceSetImpl.javaBase();
        JavaInspector ji = new io.codelaser.maddi.inspection.openjdk.JavaInspectorImpl();
        ji.preload("java.base::java.util.");
        // deliberately NOT preloading java.desktop / java.net.http, and NOT on the classpath below
        InputConfiguration ic = new InputConfigurationImpl.Builder()
                .addSourceSets(SourceSetImpl.testProtocolSourceSet())
                .addClassPathParts(javaBase) // java.base only
                .build();
        ji.initialize(ic);
        ji.onlyPreload();
        return ji;
    }

    @Test
    public void hintsGatedByClasspath() throws Exception {
        JavaInspector ji = inspectorWithoutDesktop();
        LoadAnalysisResults lar = new LoadAnalysisResults(ji.runtime(), SourceSetImpl.testProtocolSourceSet());

        // must NOT throw despite the archive containing javax.swing / java.awt / java.net.http hints
        int loaded = assertDoesNotThrow(() -> lar.go(List.of(LoadAnalysisResults.ANALYZED_RESULTS_JDK)));

        assertTrue(loaded > 0, "some java.base hints must have loaded");
        assertTrue(lar.skippedPrimaryTypes() > 0, "swing/awt/http hints must have been skipped");

        List<TypeInfo> typesLoaded = ji.compiledTypesManager().typesLoaded(true);
        assertTrue(typesLoaded.stream().anyMatch(t -> "java.util.List".equals(t.fullyQualifiedName())),
                "java.util.List must be loaded (hints applied)");
        assertFalse(typesLoaded.stream().anyMatch(t -> t.packageName().startsWith("javax.swing")),
                "no swing type should have been loaded (module absent from classpath)");
    }

    private static String results(String... primaryTypes) {
        StringBuilder sb = new StringBuilder("[\n{\"format\": \"" + WriteAnalysisResults.FORMAT_NAME
                                             + "\", \"version\": " + WriteAnalysisResults.FORMAT_VERSION + "}");
        for (String fqn : primaryTypes) {
            sb.append(",\n\n{\"name\": \"T").append(fqn).append("\", \"data\":{\"annotatedApi\":1}}");
        }
        return sb.append("\n]\n").toString();
    }

    // a file of which no primary type is on the classpath is skipped before its JSON is parsed (libs/assertj, ~1 MB,
    // in a project without AssertJ); one with at least one present type loads as before
    @Test
    public void fileWithoutTypesOnClasspathNotParsed() throws Exception {
        JavaInspector ji = inspectorWithoutDesktop();
        LoadAnalysisResults lar = new LoadAnalysisResults(ji.runtime(), SourceSetImpl.testProtocolSourceSet());
        Codec codec = new PrepWorkCodec(ji.runtime(), SourceSetImpl.testProtocolSourceSet()).codec();

        // invalid JSON after the entries: the parser would throw, so a 0 here means it never ran
        String absent = results("org.assertj.core.api.Assertions", "org.assertj.core.api.ListAssert")
                        + "{ not json";
        assertEquals(0, lar.go(codec, absent));
        assertEquals(2, lar.skippedPrimaryTypes());

        assertEquals(1, lar.go(codec, results("org.assertj.core.api.Assertions", "java.util.List")));
        assertEquals(3, lar.skippedPrimaryTypes());
    }

    // CodeLaser/maddi-mod#37: a library a project carries in two versions is loaded twice, one TypeInfo per jar. The
    // results reach both copies, also when the source set of request reaches neither (the gate must not skip the
    // file). The second copy comes from a runtime that reports both: one javac class path cannot load two.
    @Test
    public void everyLoadedCopyTakesTheResults() throws Exception {
        JavaInspector ji = inspectorWithoutDesktop();
        Runtime real = ji.runtime();
        TypeInfo v1 = libraryType(real, "lib-1.0.jar");
        TypeInfo v2 = libraryType(real, "lib-2.0.jar");
        Runtime twoCopies = (Runtime) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Runtime.class},
                (_, method, args) -> {
                    if ("loadedCopies".equals(method.getName()) && "com.example.lib.Thing".equals(args[0])) {
                        return List.of(v1, v2);
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        LoadAnalysisResults lar = new LoadAnalysisResults(twoCopies, SourceSetImpl.testProtocolSourceSet());
        Codec codec = new PrepWorkCodec(twoCopies, SourceSetImpl.testProtocolSourceSet()).codec();

        assertEquals(1, lar.go(codec, results("com.example.lib.Thing")));
        assertEquals(0, lar.skippedPrimaryTypes());
        assertEquals(1, lar.decoratedExtraCopies());
        assertTrue(v1.analysis().haveAnalyzedValueFor(PropertyImpl.ANNOTATED_API));
        assertTrue(v2.analysis().haveAnalyzedValueFor(PropertyImpl.ANNOTATED_API));
    }

    private static TypeInfo libraryType(Runtime runtime, String jar) {
        SourceSet sourceSet = new SourceSetImpl.Builder().setName(jar).setUri(URI.create("file:/" + jar))
                .setLibrary(true).setExternalLibrary(true).build();
        CompilationUnit cu = runtime.newCompilationUnitBuilder().setPackageName("com.example.lib")
                .setSourceSet(sourceSet).build();
        TypeInfo typeInfo = runtime.newTypeInfo(cu, "Thing");
        cu.setTypes(List.of(typeInfo));
        typeInfo.builder().setTypeNature(runtime.typeNatureClass()).setParentClass(runtime.objectParameterizedType())
                .computeAccess().commit();
        return typeInfo;
    }
}
