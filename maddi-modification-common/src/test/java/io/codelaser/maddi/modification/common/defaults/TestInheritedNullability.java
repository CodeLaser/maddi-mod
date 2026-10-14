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

package io.codelaser.maddi.modification.common.defaults;

import io.codelaser.maddi.modification.common.CommonTest;
import io.codelaser.maddi.cst.api.info.MethodInfo;
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.cst.api.type.NullableState;
import io.codelaser.maddi.cst.api.type.ParameterizedType;
import io.codelaser.maddi.cst.impl.analysis.ValueImpl.NullabilityImpl;
import io.codelaser.maddi.inspection.api.integration.JavaInspector;
import io.codelaser.maddi.inspection.resource.SourceSetImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static io.codelaser.maddi.modification.common.defaults.ShallowMethodAnalyzer.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The override rule's type-argument states do not depend on the order of {@code overrides()}, a set (maddi-mod#47):
 * several overridden declarations of the same shape are combined position by position.
 */
public class TestInheritedNullability {

    private ParameterizedType function;
    private ParameterizedType array;

    @BeforeEach
    public void beforeEach() throws IOException {
        JavaInspector javaInspector = CommonTest.javaInspectorFactory().withSources(SourceSetImpl.testProtocolSourceSet());
        TypeInfo typeInfo = javaInspector.parse(Map.of("a.P", """
                        package a;
                        import java.util.function.BiFunction;
                        class P {
                            void compute(Object key, BiFunction<Object, Object, Object> f) { }
                            Object[] toArray() { return null; }
                        }
                        """), new JavaInspector.ParseOptions.Builder()
                        .setFailFast(true)
                        .setIgnoreModule(true)
                        .build())
                .parseResult().findType("a.P");
        MethodInfo compute = typeInfo.findUniqueMethod("compute", 2);
        function = compute.parameters().get(1).parameterizedType();
        array = typeInfo.findUniqueMethod("toArray", 0).returnType();
    }

    private String inherit(ParameterizedType pt, List<NullableState> priority, String... shapes) {
        List<Declared> declared = java.util.Arrays.stream(shapes)
                .map(s -> new Declared(pt, NullabilityImpl.fromShape(s))).toList();
        return ((NullabilityImpl) inherited(NullableState.NONNULL, pt, declared, priority)).shape();
    }

    // java.security.Provider.compute: Properties.compute N(U,Q,U), Map.compute N(U,Q,Q)
    @Test
    public void parameterNullableWins() {
        assertEquals("N(U,Q,Q)", inherit(function, PARAMETER_PRIORITY, "N(U,Q,U)", "N(U,Q,Q)"));
        assertEquals("N(U,Q,Q)", inherit(function, PARAMETER_PRIORITY, "N(U,Q,Q)", "N(U,Q,U)"));
        assertEquals("N(Q,N,Q)", inherit(function, PARAMETER_PRIORITY, "N(Q,N,U)", "N(U,N,Q)"));
    }

    @Test
    public void returnNonNullWins() {
        assertEquals("N(N)", inherit(array, RETURN_PRIORITY, "N(Q)", "N(N)"));
        assertEquals("N(N)", inherit(array, RETURN_PRIORITY, "N(N)", "N(Q)"));
    }

    @Test
    public void onlyOneDeclaresArguments() {
        assertEquals("N(Q)", inherit(array, RETURN_PRIORITY, "N", "N(Q)"));
        assertEquals("N", inherit(array, RETURN_PRIORITY, "N"));
    }
}
