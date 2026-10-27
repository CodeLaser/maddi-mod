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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestAssignmentsReturn`. The Kotlin source uses `package a.b`, so
 * the FQN-bearing oracle strings (`a.b.X.method(char)`, …) copy over verbatim from the Java test.
 */
class TestAssignmentsReturn : CommonKotlinPrep() {

    // Java: `int i; if(c=='?'){ return 0; } else { i = 1; } return i;`
    private val input1 = """
        package a.b
        class X {
            fun method(c: Char): Int {
                var i: Int
                if (c == '?') {
                    return 0
                } else {
                    i = 1
                }
                return i
            }
        }
    """.trimIndent()

    @Test
    fun test1() {
        val x = parse("X.kt", input1)
        val method = x.findUniqueMethod("method", 1)
        doMethod(method)

        val vdMethod = VariableDataImpl.of(method)
        assertNotNull(vdMethod)
        assertEquals("a.b.X.method(char), a.b.X.method(char):0:c, i", vdMethod.knownVariableNamesToString())
        assertEquals("[i, a.b.X.method(char):0:c, a.b.X.method(char)]", vdMethod.knownVariableNames().toString())

        val iVi = vdMethod.variableInfo("i")
        assertEquals("2", iVi.reads().toString())
        assertEquals("D:0, A:[1.1.0, 1=M]", iVi.assignments().toString())
    }
}
