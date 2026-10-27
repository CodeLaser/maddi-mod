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
 * Kotlin port of `modification-prepwork` `TestAssignmentsTry`, cases `test1` and `test2`: a local assigned in
 * both the try body and the catch body has both arms plus their merge (`D:0, A:[1.0.0, 1.1.0, 1=M]`); a
 * finally-only local is defined at its declaration and assigned in the finally arm (`D:1, A:[2.2.0, 2=M]`).
 *
 * The Kotlin source mirrors the Java faithfully: the try body assigns `c = input[i]` (Kotlin's indexed-get on a
 * String, which resolves to a real call so the receiver `input` is read at `1.0.0`, matching Java's
 * `c = in.charAt(i)`), and the trailing `println` resolves to `kotlin.io.ConsoleKt.println` so its argument reads
 * are tracked.
 *
 * The Java suite's remaining cases have no faithful Kotlin form: try-with-resources becomes a `use { }` call, not
 * a resource header; multi-catch resource re-use has no Kotlin equivalent; and the nested-`for` cases use a
 * C-style `for` whose block structure Kotlin's `for (x in …)` does not reproduce.
 */
class TestAssignmentsTry : CommonKotlinPrep() {

    private fun analyse(input: String): VariableData {
        val x = parse("X.kt", input)
        val m = x.findUniqueMethod("method", 2)
        doMethod(m)
        return VariableDataImpl.of(m)
    }

    private fun v(vd: VariableData, simpleName: String): VariableInfo =
        vd.variableInfoStream().filter { it.variable().simpleName() == simpleName }.findFirst().orElseThrow()

    @Test
    fun test1() {
        val vd = analyse("""
            package a.b
            class X {
                fun method(input: String, i: Int): Char {
                    val c: Char
                    try {
                        c = input[i]
                    } catch (e: IndexOutOfBoundsException) {
                        c = '2'
                    }
                    println("c is " + c)
                    return c
                }
            }
        """.trimIndent())
        val c = v(vd, "c")
        // 0: val c, 1: try, 1.0.0: try body, 1.1.0: catch body, 1=M: merge; 2: println (reads c), 3: return
        assertEquals("D:0, A:[1.0.0, 1.1.0, 1=M]", c.assignments().toString())
        assertEquals("2, 3", c.reads().toString())
        // `input[i]` resolves to a real call, so its receiver `input` is read where the try body assigns c
        assertEquals("1.0.0", v(vd, "input").reads().toString())
    }

    @Test
    fun test2() {
        val vd = analyse("""
            package a.b
            class X {
                fun method(input: String, i: Int): Char {
                    val c: Char
                    val d: Char
                    try {
                        c = input[i]
                    } catch (e: IndexOutOfBoundsException) {
                        c = '2'
                    } finally {
                        d = '9'
                    }
                    println("d is " + d + ", c is " + c)
                    return c
                }
            }
        """.trimIndent())
        val c = v(vd, "c")
        // 0: val c, 1: val d, 2: try, 2.0.0 try / 2.1.0 catch / 2.2.0 finally, 2=M merge; 3: println reads c&d, 4: return
        assertEquals("D:0, A:[2.0.0, 2.1.0, 2=M]", c.assignments().toString())
        assertEquals("3, 4", c.reads().toString())
        val d = v(vd, "d")
        assertEquals("D:1, A:[2.2.0, 2=M]", d.assignments().toString())
        assertEquals("3", d.reads().toString())
    }
}
