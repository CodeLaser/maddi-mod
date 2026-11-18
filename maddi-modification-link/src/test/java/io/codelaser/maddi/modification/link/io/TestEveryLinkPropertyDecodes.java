package io.codelaser.maddi.modification.link.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Property;
import io.codelaser.maddi.modification.link.impl.MethodLinkedVariablesImpl;
import io.codelaser.maddi.modification.prepwork.MethodAnalyzer;
import io.codelaser.maddi.modification.prepwork.PrepAnalyzer;
import io.codelaser.maddi.modification.prepwork.callgraph.ComputePartOfConstructionFinalField;
import io.codelaser.maddi.modification.prepwork.callgraph.LazyCacheField;
import io.codelaser.maddi.modification.prepwork.variable.impl.LinksImpl;
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableInfoImpl;

/**
 * maddi-mod's side of maddi's TestEveryWritablePropertyDecodes: a property maddi-mod declares, which the results
 * writer can put in a file, must be known to the restore codec ({@link LinkCodec.P}). An unknown key costs the whole
 * file: "Have no property object for key lazyCacheField" lost six hibernate-core types' results.
 */
public class TestEveryLinkPropertyDecodes {

    private static final List<Class<?>> DECLARING = List.of(MethodLinkedVariablesImpl.class, LinksImpl.class,
            ComputePartOfConstructionFinalField.class, LazyCacheField.class, PrepAnalyzer.class, MethodAnalyzer.class,
            VariableInfoImpl.class);

    // never in a file: WriteAnalysisResults.NEVER_SERIALISED, a value that does not stream (encode returns null), and
    // the per-variable properties, which live on variable infos rather than on an element's analysis
    private static final Set<String> NOT_WRITTEN = Set.of("typePrepped", "localVariablesOfEnclosingMethod",
            "getSetAssumptions", "unmodifiedVariable", "structurallyUnmodifiedVariable", "downcastVariable");

    @Test
    public void everyPropertyResolves() throws IllegalAccessException {
        List<String> unresolvable = new ArrayList<>();
        int checked = 0;
        for (Class<?> clazz : DECLARING) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !Property.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Property property = (Property) field.get(null);
                if (property == null || NOT_WRITTEN.contains(property.key())) continue;
                ++checked;
                if (new LinkCodec.P().get(property.key()) != property) {
                    unresolvable.add(clazz.getSimpleName() + "." + field.getName() + " (" + property.key() + ")");
                }
            }
        }
        assertTrue(checked >= 4, "expected to check maddi-mod's properties, saw " + checked);
        assertEquals(List.of(), unresolvable, "unknown to LinkCodec.P: add them to its PROPERTY_MAP");
    }

    // the list above is the whole of it: every class of maddi-mod that declares a property is in it
    @Test
    public void declaringClassesAreComplete() throws IOException {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Set<String> declaring = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/"))
                    .toList()) {
                String text = Files.readString(p);
                if (text.matches("(?s).*static final Property \\w+ = new PropertyImpl\\(.*")) {
                    declaring.add(p.getFileName().toString().replace(".java", ""));
                }
            }
        }
        Set<String> known = new TreeSet<>();
        DECLARING.forEach(c -> known.add(c.getSimpleName()));
        assertEquals(known, declaring);
    }
}
