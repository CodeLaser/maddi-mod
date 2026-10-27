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

/** Kotlin port of `modification-prepwork` `TestAssignmentsDoWhile`: do-while reads/assignments + merge. */
class TestAssignmentsDoWhile : CommonKotlinPrep() {

    // Java: `int sum=0; int i=0; do { sum += i; i++; } while (i < n); return sum;`
    private val input = """
        package a.b
        class X {
            fun m(n: Int): Int {
                var sum = 0
                var i = 0
                do {
                    sum += i
                    i++
                } while (i < n)
                return sum
            }
        }
    """.trimIndent()

    private fun local(vd: VariableData, simpleName: String): VariableInfo =
        vd.variableInfoStream().filter { it.variable().simpleName() == simpleName }.findFirst().orElseThrow()

    private fun rv(vd: VariableData): VariableInfo =
        vd.variableInfoStream().filter { it.variable() is ReturnVariable }.findFirst().orElseThrow()

    @Test
    fun test() {
        val x = parse("X.kt", input)
        val m = x.findUniqueMethod("m", 1)
        doMethod(m)
        val vd = VariableDataImpl.of(m)

        val sum = local(vd, "sum")
        assertEquals("2.0.0, 3", sum.reads().toString())
        assertEquals("D:0, A:[0, 2.0.0, 2=M]", sum.assignments().toString())

        val i = local(vd, "i")
        // condition 'i < n' is read at 2:E (post-body evaluation); 'i++' reads+assigns at 2.0.1
        assertEquals("2.0.0, 2.0.1, 2:E", i.reads().toString())
        assertEquals("D:1, A:[1, 2.0.1, 2=M]", i.assignments().toString())

        val n = local(vd, "n")
        assertEquals("2:E", n.reads().toString())
        assertEquals("D:-, A:[]", n.assignments().toString())

        assertEquals("D:-, A:[3]", rv(vd).assignments().toString())
    }
}
