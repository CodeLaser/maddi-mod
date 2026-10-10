/*
 * maddi: a modification analyzer for duplication detection and immutability.
 * Copyright 2020-2025, Bart Naudts, https://github.com/CodeLaser/maddi
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details. You should have received a copy of the GNU Lesser General Public
 * License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.codelaser.maddi.modification.analyzer.modification;

import io.codelaser.maddi.cst.api.info.Info;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import io.codelaser.maddi.modification.analyzer.impl.IteratingAnalyzerImpl;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CodeLaser/maddi-mod#24 R2. A fluent builder setter assigns its argument to a field and returns {@code this}. The
 * next call in the chain modifies the builder, and the reachability pass carried that modification through the
 * setter's return value to the argument. In langchain4j, {@code JsonSchemaElementJsonUtils.fromMap}'s {@code map}
 * was modified through {@code JsonAnyOfSchema.Builder.anyOf(List)}. Rebinding the builder's fields modifies the
 * builder, not the objects it now references. {@code Accumulator.add}, which does modify the list it holds, is the
 * sound counter-case.
 */
public class TestBuilderFieldAssignment extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import java.util.List;
            import java.util.Map;
            class R2 {
                static class Schema {
                    private final List<String> anyOf;
                    private final String description;
                    private Schema(Builder b) { anyOf = b.anyOf; description = b.description; }
                    static Builder builder() { return new Builder(); }
                    List<String> anyOf() { return anyOf; }
                    String description() { return description; }
                    static class Builder {
                        private List<String> anyOf;
                        private String description;
                        Builder anyOf(List<String> anyOf) { this.anyOf = anyOf; return this; }
                        Builder description(String description) { this.description = description; return this; }
                        Schema build() { return new Schema(this); }
                    }
                }
                static Schema fromList(List<String> list) {
                    return Schema.builder().anyOf(list).description("x").build();
                }
                @SuppressWarnings("unchecked")
                static Schema fromMap(Map<String, Object> map) {
                    List<String> l = (List<String>) map.get("anyOf");
                    return Schema.builder().anyOf(l).description("x").build();
                }
                static class Accumulator {
                    private List<String> target;
                    Accumulator into(List<String> target) { this.target = target; return this; }
                    Accumulator add(String s) { target.add(s); return this; }
                }
                static void addTo(List<String> list) {
                    new Accumulator().into(list).add("x");
                }
            }
            """;

    private static MethodInfo method(TypeInfo typeInfo, String name) {
        return typeInfo.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow();
    }

    private String verdicts(boolean modReach) {
        TypeInfo X = javaInspector.parse("a.b.R2", INPUT);
        List<Info> ao = prepWork(X);
        new IteratingAnalyzerImpl(javaInspector, new IteratingAnalyzerImpl.ConfigurationBuilder()
                .setMaxIterations(10)
                .setModificationViaReachability(modReach)
                .build()).analyze(ao);
        return "fromList=" + method(X, "fromList").parameters().getFirst().isUnmodified()
               + " fromMap=" + method(X, "fromMap").parameters().getFirst().isUnmodified()
               + " schemaAnyOf=" + X.findSubType("Schema").getFieldByName("anyOf", true).isUnmodified()
               + " addTo=" + method(X, "addTo").parameters().getFirst().isUnmodified();
    }

    private static final String EXPECTED = "fromList=true fromMap=true schemaAnyOf=true addTo=false";

    @DisplayName("a builder setter's argument is not modified by the next call on the builder: fixpoint")
    @Test
    public void fixpoint() {
        assertEquals(EXPECTED, verdicts(false));
    }

    @DisplayName("a builder setter's argument is not modified by the next call on the builder: reachability")
    @Test
    public void reachability() {
        assertEquals(EXPECTED, verdicts(true));
    }
}
