package io.codelaser.maddi.modification.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.EnumMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;

// bytecode models a type-parameter bound 'T extends X' with an EXTENDS wildcard on the bound; the printer must
// not render that as the invalid '<T extends ? extends X>' (this surfaced when IsolateMethod stubbed such a
// method from a compiled library). Inner type-argument wildcards (e.g. '? super T') must still be printed.
public class TestTypeBoundWildcardPrinting extends CommonIsolateMethodTest {

    @DisplayName("bytecode-loaded bounded generic prints without a leading bound wildcard")
    @Test
    public void boundWildcardNotPrinted() {
        parse("a.b.X", "package a.b; class X {}"); // initialize the inspector
        TypeInfo enumMap = javaInspector.compiledTypesManager().type(EnumMap.class);
        assertEquals("K extends Enum<K>", enumMap.typeParameters().getFirst()
                .print(javaInspector.runtime().qualificationSimpleNames(), true).toString());

        TypeInfo collections = javaInspector.compiledTypesManager().type(Collections.class);
        MethodInfo sort = collections.methodStream()
                .filter(m -> m.name().equals("sort") && m.parameters().size() == 1)
                .findFirst().orElseThrow();
        // inner '? super T' is preserved, only the bound's own leading wildcard is dropped
        assertEquals("T extends Comparable<? super T>", sort.typeParameters().getFirst()
                .print(javaInspector.runtime().qualificationSimpleNames(), true).toString());
    }
}
