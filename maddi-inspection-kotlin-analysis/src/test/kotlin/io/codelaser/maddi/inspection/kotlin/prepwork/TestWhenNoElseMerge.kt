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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestSwitchNoDefaultMerge`: `when` completeness must be
 * exhaustiveness-aware. A variable assigned in every arm is definitely assigned after the `when` (gets the `1=M`
 * merge marker) only if the `when` is exhaustive — it has an explicit `else`, or it is an exhaustive `is`-`when`
 * over a `sealed` hierarchy. A classic constant `when` (int labels) without `else` is not exhaustive, so the
 * variable is NOT definitely assigned afterwards.
 */
class TestWhenNoElseMerge : CommonKotlinPrep() {

    private fun analyse(input: String): VariableData {
        val x = parse("X.kt", input)
        val m = x.findUniqueMethod("m", 1)
        doMethod(m)
        return VariableDataImpl.of(m)
    }

    private fun local(vd: VariableData, simpleName: String): VariableInfo =
        vd.variableInfoStream().filter { it.variable().simpleName() == simpleName }.findFirst().orElseThrow()

    // r is pre-initialised so that the source compiles; the point is the ABSENCE of the '1=M' merge marker.
    private val noElse = """
        package a.b
        class X {
            fun m(x: Int): Int {
                var r = 0
                when (x) {
                    1 -> r = 10
                    2 -> r = 20
                }
                return r
            }
        }
    """.trimIndent()

    @Test
    fun testNoElse() {
        val vd = analyse(noElse)
        val r = local(vd, "r")
        assertEquals("D:0, A:[0, 1.0.0, 1.1.0]", r.assignments().toString())
    }

    private val withElse = """
        package a.b
        class X {
            fun m(x: Int): Int {
                var r: Int
                when (x) {
                    1 -> r = 10
                    2 -> r = 20
                    else -> r = 0
                }
                return r
            }
        }
    """.trimIndent()

    @Test
    fun testWithElse() {
        val vd = analyse(withElse)
        val r = local(vd, "r")
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1.2.0, 1=M]", r.assignments().toString())
        assertTrue(r.hasBeenDefined("2"))
    }

    private val exhaustiveSealed = """
        package a.b
        class X {
            sealed interface S
            class A : S
            class B : S
            fun m(s: S): Int {
                var r: Int
                when (s) {
                    is A -> r = 1
                    is B -> r = 2
                }
                return r
            }
        }
    """.trimIndent()

    @Test
    fun testExhaustiveSealed() {
        val vd = analyse(exhaustiveSealed)
        val r = local(vd, "r")
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1=M]", r.assignments().toString())
        assertTrue(r.hasBeenDefined("2"))
    }
}
