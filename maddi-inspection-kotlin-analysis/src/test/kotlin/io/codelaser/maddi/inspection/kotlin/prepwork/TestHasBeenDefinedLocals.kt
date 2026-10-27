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
import io.codelaser.maddi.modification.prepwork.variable.impl.VariableDataImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin port of `modification-prepwork` `TestHasBeenDefinedLocals`. The control-flow shapes (if/else, do-while,
 * while, try/catch) translate structurally 1:1 to Kotlin, so the assignment-index and `hasBeenDefined` oracle
 * strings copy over verbatim.
 */
class TestHasBeenDefinedLocals : CommonKotlinPrep() {

    private fun analyse(input: String): VariableData {
        val x = parse("X.kt", input)
        val m = x.findUniqueMethod("m", 1)
        doMethod(m)
        return VariableDataImpl.of(m)
    }

    // assigned in both if-branches -> defined after the merge
    private val bothBranches = """
        package a.b
        class X {
            fun m(c: Boolean): Int {
                var x: Int
                if (c) x = 1
                else x = 2
                return x
            }
        }
    """.trimIndent()

    @Test
    fun testBothBranches() {
        val vd = analyse(bothBranches)
        val x = vd.variableInfo("x")
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1=M]", x.assignments().toString())
        assertFalse(x.hasBeenDefined("0"))     // declared, not yet assigned
        assertTrue(x.hasBeenDefined("1.0.0"))  // inside then-branch
        assertTrue(x.hasBeenDefined("1.1.0"))  // inside else-branch
        assertTrue(x.hasBeenDefined("2"))      // after if: both branches assign
    }

    // assigned in one if-branch only -> NOT defined after
    private val oneBranch = """
        package a.b
        class X {
            fun m(c: Boolean) {
                var x: Int
                if (c) {
                    x = 1
                    println(x)
                }
            }
        }
    """.trimIndent()

    @Test
    fun testOneBranch() {
        val vd = analyse(oneBranch)
        val x = vd.variableInfo("x")
        assertEquals("D:0, A:[1.0.0]", x.assignments().toString())
        assertTrue(x.hasBeenDefined("1.0.1"))   // inside then-branch, after the assignment
        assertFalse(x.hasBeenDefined("2"))      // fictitious index after the if: not defined (no else)
    }

    // assigned in do-while body -> defined after (body runs at least once)
    private val doWhile = """
        package a.b
        class X {
            fun m(c: Boolean): Int {
                var x: Int
                do {
                    x = 1
                } while (c)
                return x
            }
        }
    """.trimIndent()

    @Test
    fun testDoWhileBody() {
        val vd = analyse(doWhile)
        val x = vd.variableInfo("x")
        assertEquals("D:0, A:[1.0.0, 1=M]", x.assignments().toString())
        assertTrue(x.hasBeenDefined("2")) // contrast with while: do-body is unconditional, hence a merge
    }

    // assigned in while body -> NOT defined after (body may run zero times)
    private val whileBody = """
        package a.b
        class X {
            fun m(c: Boolean) {
                var x: Int
                while (c) {
                    x = 1
                    println(x)
                }
            }
        }
    """.trimIndent()

    @Test
    fun testWhileBody() {
        val vd = analyse(whileBody)
        val x = vd.variableInfo("x")
        assertEquals("D:0, A:[1.0.0]", x.assignments().toString()) // no merge: conditional loop
        assertTrue(x.hasBeenDefined("1.0.1"))  // inside the body, after the assignment
        assertFalse(x.hasBeenDefined("2"))     // fictitious index after the loop: not defined
    }

    // assigned in try and in all catch clauses -> defined after
    private val tryCatch = """
        package a.b
        class X {
            fun m(n: Int): Int {
                var x: Int
                try {
                    x = n / n
                } catch (e: RuntimeException) {
                    x = 0
                }
                return x
            }
        }
    """.trimIndent()

    @Test
    fun testTryCatch() {
        val vd = analyse(tryCatch)
        val x = vd.variableInfo("x")
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1=M]", x.assignments().toString())
        assertTrue(x.hasBeenDefined("2"))
    }
}
