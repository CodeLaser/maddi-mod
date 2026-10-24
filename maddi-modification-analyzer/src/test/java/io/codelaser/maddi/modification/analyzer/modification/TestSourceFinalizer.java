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
import io.codelaser.maddi.cst.api.info.TypeInfo;
import io.codelaser.maddi.modification.analyzer.CommonTest;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @Finalizer} is a pure contract: nothing computes it. On a source method {@code SourceContractMaterializer}
 * writes it into {@code analysis()}, where {@code MethodInfo.isFinalizer()} reads it (CodeLaser/maddi#167: the Rust
 * printer's {@code self} by value).
 */
public class TestSourceFinalizer extends CommonTest {

    @Language("java")
    private static final String INPUT = """
            package a.b;
            import io.codelaser.maddi.annotation.rare.Finalizer;
            public class X {
                private final StringBuilder sb = new StringBuilder();
                public X add(String s) { sb.append(s); return this; }
                @Finalizer
                public String build() { return sb.toString(); }
            }
            """;

    @DisplayName("a source method's @Finalizer is materialized")
    @Test
    public void test() {
        TypeInfo X = javaInspector.parse("a.b.X", INPUT);
        List<Info> ao = prepWork(X);
        analyzer.go(ao);
        assertTrue(X.findUniqueMethod("build", 0).isFinalizer());
        assertFalse(X.findUniqueMethod("add", 1).isFinalizer());
    }
}
