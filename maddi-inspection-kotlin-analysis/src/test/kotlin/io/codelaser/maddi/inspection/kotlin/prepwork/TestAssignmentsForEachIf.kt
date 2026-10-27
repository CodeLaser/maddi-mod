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

import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import io.codelaser.maddi.cst.api.statement.IfElseStatement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestAssignmentsForEachIf`: a for-each with a nested
 * if/else-if/else. Stresses statement-index fidelity — the deep nested indices (`1.0.0.1.0.1.0`, …) must
 * match Java, which they only do if Kotlin's `else if` nests the same way (else block holding one `if`).
 */
class TestAssignmentsForEachIf : CommonKotlinPrep() {

    private val input1 = """
        package a.b
        import java.io.IOException
        class X {
            fun method(array: Array<String>): String {
                val sb = StringBuilder()
                for (s in array) {
                    if (s.length < 2) {
                        sb.append(s)
                    } else if (s.length > 20) {
                        throw IOException("too long")
                    } else {
                        return sb.toString()
                    }
                }
                return SEEN_THEM_ALL
            }
            companion object { const val SEEN_THEM_ALL = "seen them all" }
        }
    """.trimIndent()

    @Test
    fun test1() {
        val x = parse("X.kt", input1)
        val method = x.findUniqueMethod("method", 1)
        doMethod(method)
        val array = method.parameters().first()

        val s0 = method.methodBody().statements()[0]
        val vd0 = VariableDataImpl.of(s0)
        assertEquals("D:0, A:[0]", vd0.variableInfo("sb").assignments().toString())
        assertFalse(vd0.isKnown(array.fullyQualifiedName()))

        val s1 = method.methodBody().statements()[1]
        val s100 = s1.block().statements()[0] as IfElseStatement
        val s10000 = s100.block().statements()[0]

        val vd10000 = VariableDataImpl.of(s10000)
        assertEquals("D:0, A:[0]", vd10000.variableInfo("sb").assignments().toString())
        assertEquals("1.0.0.0.0", vd10000.variableInfo("sb").reads().toString())
        assertEquals("D:-, A:[]", vd10000.variableInfo(array.fullyQualifiedName()).assignments().toString())
        assertEquals("1-E", vd10000.variableInfo(array.fullyQualifiedName()).reads().toString())

        val s10010 = s100.elseBlock().statements()[0] as IfElseStatement
        val s1001010 = s10010.elseBlock().statements()[0]

        val vd1001010 = VariableDataImpl.of(s1001010)
        assertEquals("D:0, A:[0]", vd1001010.variableInfo("sb").assignments().toString())
        assertEquals("1.0.0.1.0.1.0", vd1001010.variableInfo("sb").reads().toString())
        assertEquals("1-E", vd1001010.variableInfo(array.fullyQualifiedName()).reads().toString())

        // first merge (the inner if)
        val vd10010 = VariableDataImpl.of(s10010)
        assertEquals("1.0.0.1.0.1.0", vd10010.variableInfo("sb").reads().toString())
        assertEquals("D:0, A:[0]", vd10010.variableInfo("sb").assignments().toString())

        // second merge (the outer if)
        val vd100 = VariableDataImpl.of(s100)
        assertEquals("1.0.0.0.0, 1.0.0.1.0.1.0", vd100.variableInfo("sb").reads().toString())
        assertEquals("D:0, A:[0]", vd100.variableInfo("sb").assignments().toString())

        // third merge (the for-each)
        val vd1 = VariableDataImpl.of(s1)
        assertEquals("D:0, A:[0]", vd1.variableInfo("sb").assignments().toString())
        assertEquals("D:-, A:[]", vd1.variableInfo(array.fullyQualifiedName()).assignments().toString())

        val vdMethod = VariableDataImpl.of(method)
        assertNotNull(vdMethod)
        val rvVi = vdMethod.variableInfo(method.fullyQualifiedName())
        assertEquals("D:-, A:[1.0.0.1.0.0.0, 1.0.0.1.0.1.0, 1.0.0.1.0=M, 2]", rvVi.assignments().toString())
        assertTrue(rvVi.hasBeenDefined("2=M"))
        assertTrue(rvVi.hasBeenDefined("3.0.0"))
    }
}
