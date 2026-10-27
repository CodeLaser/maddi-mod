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

import io.codelaser.maddi.modification.prepwork.variable.VariableData
import io.codelaser.maddi.modification.prepwork.variable.VariableInfo
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestAssignmentsFieldAccess`: a compound assignment (`+=`) on an
 * instance field reads *and* assigns its target.
 *
 * The Java suite's other cases have no faithful Kotlin form and are not ported: an indexed compound assignment
 * (`a[i] += v`) desugars through Kotlin's `get`/`set` operator convention, so no `a[i]` dependent variable is
 * produced; a nested field assignment (`a.b.c = ...`) resolves through property getter/setter calls, so there is
 * no `a`/`b`/`c` field-variable to track; and the static-field case would need a `companion object`, which
 * changes the enclosing structure.
 */
class TestAssignmentsFieldAccess : CommonKotlinPrep() {

    private fun analyse(input: String, params: Int): VariableData {
        val x = parse("X.kt", input)
        val m = x.findUniqueMethod("m", params)
        doMethod(m)
        return VariableDataImpl.of(m)
    }

    private fun v(vd: VariableData, simpleName: String): VariableInfo =
        vd.variableInfoStream().filter { it.variable().simpleName() == simpleName }.findFirst().orElseThrow()

    @Test
    fun testCompoundField() {
        val vd = analyse("package a.b; class X { var f: Int = 0; fun m(d: Int) { this.f += d } }", 1)
        val f = v(vd, "f")
        assertEquals("0", f.reads().toString())       // += reads f
        assertEquals("D:-, A:[0]", f.assignments().toString())
        assertEquals("0", v(vd, "d").reads().toString())
        assertEquals("0", v(vd, "this").reads().toString())
    }
}
