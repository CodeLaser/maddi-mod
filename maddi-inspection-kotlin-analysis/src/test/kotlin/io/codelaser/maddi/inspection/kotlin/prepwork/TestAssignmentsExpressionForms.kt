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

package io.codelaser.maddi.inspection.kotlin.prepwork

import io.codelaser.maddi.modification.prepwork.variable.ReturnVariable
import io.codelaser.maddi.modification.prepwork.variable.VariableData
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestAssignmentsExpressionForms` (ternary cases). Java's `?:` is
 * Kotlin's `if/else` expression. Not ported: `a = b = 5` (assignment is not an expression in Kotlin) and
 * `a[i++] = b[j++]` with a multi-declarator `int i=0, j=0` (which would split into two statements and shift
 * every index, breaking the verbatim oracle).
 */
class TestAssignmentsExpressionForms : CommonKotlinPrep() {

    private fun analyse(input: String, params: Int): VariableData {
        val x = parse("X.kt", input)
        val m = x.findUniqueMethod("m", params)
        doMethod(m)
        return VariableDataImpl.of(m)
    }

    private fun v(vd: VariableData, simpleName: String): VariableInfo =
        vd.variableInfoStream().filter { it.variable().simpleName() == simpleName }.findFirst().orElseThrow()

    private fun rv(vd: VariableData): VariableInfo =
        vd.variableInfoStream().filter { it.variable() is ReturnVariable }.findFirst().orElseThrow()

    @Test
    fun testTernary() {
        // `int r = c ? a : b; return r;`
        val vd = analyse("package a.b; class X { fun m(c: Boolean, a: Int, b: Int): Int { val r = if (c) a else b; return r } }", 3)
        assertEquals("0", v(vd, "c").reads().toString())
        assertEquals("0", v(vd, "a").reads().toString())
        assertEquals("0", v(vd, "b").reads().toString())
        assertEquals("D:0, A:[0]", v(vd, "r").assignments().toString())
        assertEquals("D:-, A:[1]", rv(vd).assignments().toString())
    }

    @Test
    fun testNestedTernary() {
        // `return x > 0 ? 1 : x < 0 ? -1 : 0;`
        val vd = analyse("package a.b; class X { fun m(x: Int): Int { return if (x > 0) 1 else if (x < 0) -1 else 0 } }", 1)
        assertEquals("0", v(vd, "x").reads().toString())
        assertEquals("D:-, A:[0]", rv(vd).assignments().toString())
    }
}
