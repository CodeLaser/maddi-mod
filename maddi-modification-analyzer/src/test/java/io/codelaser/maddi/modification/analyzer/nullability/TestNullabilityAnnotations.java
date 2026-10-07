package io.codelaser.maddi.modification.analyzer.nullability;

import io.codelaser.maddi.cst.api.element.SourceSet;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import io.codelaser.maddi.modification.prepwork.io.NullabilityDecorator;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Annotated Java out (docs/design/nullability.md D1, T5a): the analyzer with {@code nullability} on writes the B2
 * properties, and {@link NullabilityDecorator} prints them in an annotation flavour.
 */
public class TestNullabilityAnnotations extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Map;
            class X {
                private String lazy;
                private final String fin;
                private String[] arr;
                X(String fin) { this.fin = fin; }
                String getLazy() { if (lazy == null) lazy = "y"; return lazy; }
                String find(Map<String, String> map, String key) { return map.get(key); }
                int count(String s) { return s.length(); }
                void call() { System.out.println(count(null)); }
                String[] getArr() { return arr; }
                String[] fresh(int n) { return new String[n]; }
                @io.codelaser.maddi.annotation.NotNull String declared() { return null; }
            }
            """;

    private TypeInfo analyze() {
        TypeInfo x = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(x);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setNullability(true).build()).analyze(ao);
        return x;
    }

    private String print(TypeInfo x, NullabilityDecorator.Options options) {
        SourceSet sourceSet = javaInspector.mainSources();
        return javaInspector.print2(x.compilationUnit(), new NullabilityDecorator(runtime, options, null),
                javaInspector.importComputer(4, sourceSet));
    }

    @DisplayName("the analyzer writes the properties; JSpecify under @NullMarked writes only @Nullable")
    @Test
    public void jspecifyNullMarked() {
        TypeInfo x = analyze();
        assertEquals("Q", x.getFieldByName("lazy", true).analysis()
                .getOrDefault(PropertyImpl.NULLABILITY_FIELD, ValueImpl.NullabilityImpl.UNSPECIFIED).toString());
        assertEquals("N(U,U)", x.findUniqueMethod("find", 2).parameters().getFirst().analysis()
                .getOrDefault(PropertyImpl.NULLABILITY_PARAMETER, ValueImpl.NullabilityImpl.UNSPECIFIED).toString());
        // in a TYPE_USE flavour, declaration position annotates an array's ELEMENTS: 'fresh' returns a non-null
        // array of nullable elements; a nullable array ('arr', 'getArr') cannot be said there and is left out. A
        // source annotation is a contract and is left alone
        assertEquals("""
                package a.b;
                import java.util.Map;
                import org.jspecify.annotations.Nullable;
                class X {
                    @Nullable private String lazy;
                    private final String fin;
                    private String [] arr;
                    X(String fin) { this.fin = fin; }
                    String getLazy() { if (lazy == null) { lazy = "y"; } return lazy; }
                    @Nullable String find(Map<String, String> map, String key) { return map.get(key); }
                    int count(@Nullable String s) { return s.length(); }
                    void call() { System.out.println(count(null)); }
                    String [] getArr() { return arr; }
                    @Nullable String [] fresh(int n) { return new String[n]; }
                    @io.codelaser.maddi.annotation.NotNull String declared() { return null; }
                }
                """, print(x, NullabilityDecorator.Options.JSPECIFY_NULL_MARKED));
    }

    @DisplayName("a declaration flavour with non-null annotations: JSR-305, for code outside a @NullMarked scope")
    @Test
    public void jsr305WithNonNull() {
        TypeInfo x = analyze();
        // a declaration annotation on an array speaks about the array: written ('fresh' is a non-null array; its
        // nullable elements cannot be said in this flavour); the source's contract stays
        assertEquals("""
                package a.b;
                import java.util.Map;
                import javax.annotation.Nonnull;
                import javax.annotation.Nullable;
                class X {
                    @Nullable private String lazy;
                    @Nonnull private final String fin;
                    @Nullable private String [] arr;
                    X(@Nonnull String fin) { this.fin = fin; }
                    @Nonnull String getLazy() { if (lazy == null) { lazy = "y"; } return lazy; }
                    @Nullable String find(@Nonnull Map<String, String> map, @Nonnull String key) { return map.get(key); }
                    int count(@Nullable String s) { return s.length(); }
                    void call() { System.out.println(count(null)); }
                    @Nullable String [] getArr() { return arr; }
                    @Nonnull String [] fresh(int n) { return new String[n]; }
                    @io.codelaser.maddi.annotation.NotNull String declared() { return null; }
                }
                """, print(x, new NullabilityDecorator.Options(NullabilityDecorator.Flavour.JSR305, true)));
    }

    @Language("java")
    private static final String LIBRARY = """
            package a.b;
            public class L {
                private String name = "";
                public void setName(String name) { this.name = name; }
                public int length(String s) { return s.length(); }
                public String greet(String s, String t) { return t.trim() + s; }
                public String getName() { return name; }
            }
            """;

    @DisplayName("open world under @NullMarked: an undecided method is @NullUnmarked, an undecided field @Nullable")
    @Test
    public void openWorldNullUnmarked() {
        TypeInfo l = javaInspector.parse("a.b.L", LIBRARY);
        List<Info> ao = prepWork(l);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10).setNullability(true).setNullabilityOpenWorld(true).build()).analyze(ao);
        // setName's parameter may be null from outside, and so may the field it is stored in; 'length' and 't'
        // dereference their parameter (a precondition); 'greet' still has 's' undecided: explicit @NonNull for 't'
        assertEquals("""
                package a.b;
                import org.jspecify.annotations.NonNull;
                import org.jspecify.annotations.NullUnmarked;
                import org.jspecify.annotations.Nullable;
                public class L {
                    @Nullable private String name = "";
                    @NullUnmarked public void setName(String name) { this.name = name; }
                    public int length(String s) { return s.length(); }
                    @NullUnmarked @NonNull public String greet(String s, @NonNull String t) { return t.trim() + s; }
                    @NullUnmarked public String getName() { return name; }
                }
                """, print(l, NullabilityDecorator.Options.JSPECIFY_NULL_MARKED));
    }
}
