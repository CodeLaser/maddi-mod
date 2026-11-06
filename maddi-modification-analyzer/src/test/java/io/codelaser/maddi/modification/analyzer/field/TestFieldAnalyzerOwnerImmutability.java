package io.codelaser.maddi.modification.analyzer.field;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.codelaser.maddi.cst.api.analysis.Value;
import io.codelaser.maddi.cst.api.info.FieldInfo;
import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.impl.analysis.PropertyImpl;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl;
import io.codelaser.maddi.modification.analyzer.CommonTest;

/// The shallow owner rule lifts a field's immutability to its owner's only when the owner is at least
/// immutable with hidden content: such an owner guarantees immutable content in all its fields. A
/// FINAL_FIELDS owner only guarantees that its fields are not reassigned; it says nothing about the object a
/// field holds, so a field of a mutable type stays mutable.
public class TestFieldAnalyzerOwnerImmutability extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.Iterator;
            import java.util.function.Function;
            import io.codelaser.maddi.annotation.FinalFields;
            @FinalFields
            abstract class B {
                private final Iterator<String> iterator;
                private final Function<String, String> function;
                B(Iterator<String> iterator, Function<String, String> function) {
                    this.iterator = iterator;
                    this.function = function;
                }
                public Iterator<String> getIterator() {
                    return iterator;
                }
                public Function<String, String> getFunction() {
                    return function;
                }
                abstract void run();
            }
            """;

    @DisplayName("a FINAL_FIELDS owner does not lift a mutable field")
    @Test
    public void test() {
        TypeInfo B = javaInspector.parse("a.b.B", INPUT);
        List<Info> ao = prepWork(B);
        analyzer.go(ao);
        Value.Immutable owner = (Value.Immutable) B.analysis().getOrDefault(PropertyImpl.IMMUTABLE_TYPE,
                ValueImpl.ImmutableImpl.MUTABLE);
        assertEquals(ValueImpl.ImmutableImpl.FINAL_FIELDS, owner);
        for (String name : List.of("iterator", "function")) {
            FieldInfo field = B.getFieldByName(name, true);
            Value.Immutable imm = (Value.Immutable) field.analysis().getOrDefault(PropertyImpl.IMMUTABLE_FIELD,
                    ValueImpl.ImmutableImpl.MUTABLE);
            assertEquals(ValueImpl.ImmutableImpl.MUTABLE, imm, name);
            assertTrue(field.isFinal(), name);
        }
    }
}
